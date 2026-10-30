package com.mibox.iptv

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mibox.iptv.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var currentChannelIndex = 0
    private var hasRetriedCurrentChannel = false
    private var streamResolveJob: Job? = null
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private var lastRowPosition = 0 // last focused row (display position)
    private var previousChannelId: String? = null
    private var hasStartedPlayback = false
    private var channelMenuTargetPos = RecyclerView.NO_POSITION

    private val hideBannerHandler = Handler(Looper.getMainLooper())
    private val hideBannerRunnable = Runnable {
        hideInfoBanner()
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR"))
    private val dateFormat = SimpleDateFormat("d MMMM EEEE", Locale.forLanguageTag("tr-TR"))
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15_000)
        }
    }

    /**
     * Layouts are authored on a 1920 px wide canvas (the Stitch design) with 1dp = 1 design px.
     * Force the density so that this holds on every screen (e.g. 1080p TV and Waydroid) and ignore font scale.
     */
    override fun attachBaseContext(newBase: Context) {
        val dm = newBase.resources.displayMetrics
        val widthPx = maxOf(dm.widthPixels, dm.heightPixels)
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = Math.round(160f * widthPx / 1920f).coerceAtLeast(60)
        config.fontScale = 1f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChannelRepository.installGlobalSslTrust()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChannels()
        setupPlayer()
        setupBlur()
        setupDrawer()

        if (channels.isNotEmpty()) {
            val reqId = intent?.getStringExtra("channel_id")
            val reqIdx = if (reqId != null) channels.indexOfFirst { it.id == reqId } else -1
            val startIndex = if (reqIdx >= 0) reqIdx else ChannelOrderManager.getLastChannelIndex(this, channels)
            playChannel(startIndex)
            focusChannelRow(startIndex)
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        val reqId = intent?.getStringExtra("channel_id") ?: return
        val reqIdx = channels.indexOfFirst { it.id == reqId }
        if (reqIdx >= 0) {
            playChannel(reqIdx)
        }
    }

    private fun uiPrefs() = getSharedPreferences("ui_prefs", MODE_PRIVATE)

    private fun setupBlur() {
        BlurController.enabled = uiPrefs().getBoolean("blur_enabled", true)
        BlurController.setTexture(binding.playerView.videoSurfaceView as? android.view.TextureView)
    }

    private fun refreshBlurSummary() {
        binding.txtBlurStatus.text = if (BlurController.enabled) "Açık" else "Kapalı (düz saydam ton)"
    }

    private var isSettingsTabActive = false
    private var currentPinInput = StringBuilder()
    private var onPinSuccessAction: ((String) -> Unit)? = null
    private var isPinVerificationMode = false

    private fun setupChannels() {
        val defaultChannels = ChannelRepository.getDefaultChannels()
        channels = ChannelOrderManager.getOrderedChannels(this, defaultChannels)

        channelAdapter = ChannelAdapter(
            channels = channels.toMutableList(),
            onChannelSelected = { _, position ->
                channels = channelAdapter.getChannels()
                playChannel(position)
                hideDrawer()
            },
            onOrderChanged = { updatedList ->
                channels = updatedList
                ChannelOrderManager.saveChannelOrder(this@MainActivity, updatedList)
            },
            onChannelDeleted = { deletedChannel, deletedPos, updatedList ->
                val wasPlayingDeleted = (deletedPos == currentChannelIndex)
                if (deletedPos < currentChannelIndex) {
                    currentChannelIndex = (currentChannelIndex - 1).coerceAtLeast(0)
                } else if (currentChannelIndex >= updatedList.size) {
                    currentChannelIndex = (updatedList.size - 1).coerceAtLeast(0)
                }
                channels = updatedList
                ChannelOrderManager.markChannelDeleted(this@MainActivity, deletedChannel.id)
                ChannelOrderManager.saveChannelOrder(this@MainActivity, updatedList)
                        refreshSettingsSummary()

                focusChannelRow(deletedPos.coerceAtMost(channels.size - 1))

                if (wasPlayingDeleted && channels.isNotEmpty()) {
                    playChannel(currentChannelIndex)
                }
            },
            onChannelLongPressed = { channel, position ->
                openChannelMenu(channel, position)
            }
        )

        binding.channelsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
            setHasFixedSize(true)
        }

        // Category chips (derived from channel groups); selection filters the list
        categoryAdapter = CategoryAdapter { token ->
            channelAdapter.setFilter(token)
            lastRowPosition = 0
            binding.channelsRecyclerView.scrollToPosition(0)
            binding.chipsRecyclerView.scrollToPosition(categoryAdapter.selectedPosition)
        }
        channelAdapter.onRowFocused = {
            lastRowPosition = it
            // Prewarm the focused row if focus rests on it for 300 ms
            hideBannerHandler.removeCallbacks(focusPrewarmRunnable)
            hideBannerHandler.postDelayed(focusPrewarmRunnable, 300)
        }
        channelAdapter.onFilterReset = {
            lastRowPosition = 0
            categoryAdapter.resetSelection()
            binding.chipsRecyclerView.scrollToPosition(0)
        }
        binding.chipsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
            setHasFixedSize(true)
        }
        categoryAdapter.submit(ChannelCategories.build(channels))
        binding.chipsRecyclerView.scrollToPosition(0)

        setupChannelMenu()
        setupErrorView()
        setupSettingsTab()
        setupPinModal()
    }

    private fun setupSettingsTab() {
        binding.btnOpenSettings.setOnClickListener {
            if (isSettingsTabActive) {
                switchDrawerTab(showSettings = false)
                focusLastRow()
            } else {
                switchDrawerTab(showSettings = true)
            }
        }
        binding.btnTopSettings.setOnClickListener {
            if (!isDrawerVisible()) showDrawer()
            switchDrawerTab(showSettings = true)
        }

        binding.btnToggleBlur.setOnClickListener {
            BlurController.enabled = !BlurController.enabled
            uiPrefs().edit().putBoolean("blur_enabled", BlurController.enabled).apply()
            refreshBlurSummary()
        }

        binding.btnToggleDirection.setOnClickListener {
            val newDir = !ChannelOrderManager.isUpForNextChannel(this)
            ChannelOrderManager.setUpForNextChannel(this, newDir)
            refreshSettingsSummary()
        }

        binding.btnSettingsReorder.setOnClickListener {
            switchDrawerTab(showSettings = false)
            if (!channelAdapter.isReorderMode) {
                toggleReorderMode()
            }
            focusChannelRow(currentChannelIndex)
        }

        binding.btnSettingsDelete.setOnClickListener {
            switchDrawerTab(showSettings = false)
            if (!channelAdapter.isDeleteMode) {
                toggleDeleteMode()
            }
            focusChannelRow(currentChannelIndex)
        }

        binding.btnRestoreDeleted.setOnClickListener {
            val deletedIds = ChannelOrderManager.getDeletedChannelIds(this)
            if (deletedIds.isEmpty()) {
                android.widget.Toast.makeText(this, "Silinmiş kanal bulunmuyor.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requirePinIfConfigured("Silinen Kanalları Geri Yükle") {
                ChannelOrderManager.restoreDeletedChannels(this)
                reloadChannelsAfterReset("Silinen kanallar geri yüklendi.")
            }
        }

        binding.btnResetAll.setOnClickListener {
            requirePinIfConfigured("Tüm Listeyi ve Sıralamayı Sıfırla") {
                ChannelOrderManager.resetAllToDefaults(this)
                reloadChannelsAfterReset("Kanal listesi ve sıralama sıfırlandı.")
            }
        }

        binding.btnSetPin.setOnClickListener {
            val existingPin = ChannelOrderManager.getResetPin(this)
            if (existingPin != null) {
                openPinModal(
                    title = "Mevcut Şifreyi Doğrula",
                    subtitle = "Şifreyi değiştirmek için mevcut 4 haneli şifrenizi girin",
                    verifyMode = true
                ) {
                    openPinModal(
                        title = "Yeni Şifre Belirle",
                        subtitle = "Sıfırlama işlemleri için yeni 4 haneli şifre girin",
                        verifyMode = false
                    ) { newPin ->
                        ChannelOrderManager.setResetPin(this, newPin)
                        refreshSettingsSummary()
                        android.widget.Toast.makeText(this, "Sıfırlama şifresi güncellendi.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                openPinModal(
                    title = "Sıfırlama Şifresi Ata",
                    subtitle = "Sıfırlama işlemleri için 4 haneli şifre belirleyin",
                    verifyMode = false
                ) { newPin ->
                    ChannelOrderManager.setResetPin(this, newPin)
                    refreshSettingsSummary()
                    android.widget.Toast.makeText(this, "Sıfırlama şifresi kaydedildi.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnClearPin.setOnClickListener {
            requirePinIfConfigured("Şifre Korumasını Kaldır") {
                ChannelOrderManager.setResetPin(this, null)
                refreshSettingsSummary()
                android.widget.Toast.makeText(this, "Şifre koruması kaldırıldı.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        refreshSettingsSummary()
    }

    private fun switchDrawerTab(showSettings: Boolean) {
        if (showSettings == isSettingsTabActive) return
        isSettingsTabActive = showSettings

        // Settings need more room than the slim channel panel
        binding.channelListDrawer.layoutParams = binding.channelListDrawer.layoutParams.apply {
            width = resources.getDimensionPixelSize(
                if (showSettings) R.dimen.panel_width_settings else R.dimen.panel_width
            )
        }

        // The wide settings panel would sit under the info strip, so the strip steps aside meanwhile
        binding.bottomInfoBanner.visibility = if (showSettings) View.GONE else View.VISIBLE
        binding.btnOpenSettings.setText(if (showSettings) R.string.channels else R.string.settings)
        binding.btnOpenSettings.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (showSettings) R.drawable.ic_tv else R.drawable.ic_settings, 0, 0, 0
        )

        if (showSettings) {
            if (channelAdapter.isReorderMode) toggleReorderMode()
            if (channelAdapter.isDeleteMode) toggleDeleteMode()
            binding.chipsRecyclerView.visibility = View.GONE
            binding.channelsRecyclerView.visibility = View.GONE
            binding.settingsScrollView.visibility = View.VISIBLE
            binding.dpadHintText.setText(R.string.dpad_hint_settings)
            binding.dpadHintText.visibility = View.VISIBLE
            refreshSettingsSummary()
            binding.btnToggleDirection.post { binding.btnToggleDirection.requestFocus() }
        } else {
            binding.settingsScrollView.visibility = View.GONE
            binding.chipsRecyclerView.visibility = View.VISIBLE
            binding.channelsRecyclerView.visibility = View.VISIBLE
            binding.dpadHintText.visibility = View.GONE
        }
    }

    private fun refreshSettingsSummary() {
        refreshBlurSummary()
        val upIsNext = ChannelOrderManager.isUpForNextChannel(this)
        binding.txtDirectionStatus.text = if (upIsNext) {
            "▲ sonraki  ·  ▼ önceki"
        } else {
            "▼ sonraki  ·  ▲ önceki"
        }

        val deletedIds = ChannelOrderManager.getDeletedChannelIds(this)
        if (deletedIds.isEmpty()) {
            binding.txtDeletedSummary.text = "Hafızada silinmiş kanal bulunmuyor."
        } else {
            val allDefaults = ChannelRepository.getDefaultChannels()
            val deletedNames = allDefaults.filter { it.id in deletedIds }.joinToString(", ") { it.name.replace(" HD", "") }
            binding.txtDeletedSummary.text = "Hafızada ${deletedIds.size} silinmiş kanal tutuluyor:\n$deletedNames"
        }

        val hasPin = ChannelOrderManager.getResetPin(this) != null
        if (hasPin) {
            binding.txtSetPinTitle.text = "Sıfırlama şifresini değiştir"
            binding.txtPinStatus.text = "Şifre koruması AKTİF (Sıfırlama için şifre sorulur)"
            binding.txtPinStatus.setTextColor(getColor(R.color.glow))
            binding.btnClearPin.visibility = View.VISIBLE
        } else {
            binding.txtSetPinTitle.text = "Sıfırlama şifresi ata"
            binding.txtPinStatus.text = "Şifre atanmadı (Sıfırlama için şifre belirleyin)"
            binding.txtPinStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnClearPin.visibility = View.GONE
        }
    }

    private fun reloadChannelsAfterReset(toastMessage: String) {
        val currentId = channels.getOrNull(currentChannelIndex)?.id
        val defaultChannels = ChannelRepository.getDefaultChannels()
        channels = ChannelOrderManager.getOrderedChannels(this, defaultChannels)
        channelAdapter.setFilter(null)
        categoryAdapter.submit(ChannelCategories.build(channels))
        binding.chipsRecyclerView.scrollToPosition(0)
        channelAdapter.updateChannels(channels)
        val newIdx = if (currentId != null) channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0) else 0
        currentChannelIndex = newIdx
        channelAdapter.setSelectedIndex(currentChannelIndex)
        refreshSettingsSummary()
        android.widget.Toast.makeText(this, toastMessage, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun requirePinIfConfigured(actionTitle: String, onAuthorized: () -> Unit) {
        val savedPin = ChannelOrderManager.getResetPin(this)
        if (savedPin == null) {
            onAuthorized()
        } else {
            openPinModal(
                title = actionTitle,
                subtitle = "İşlemi onaylamak için 4 haneli şifrenizi girin",
                verifyMode = true
            ) {
                onAuthorized()
            }
        }
    }

    private fun setupPinModal() {
        val digitButtons = listOf(
            binding.pinKey0 to "0",
            binding.pinKey1 to "1",
            binding.pinKey2 to "2",
            binding.pinKey3 to "3",
            binding.pinKey4 to "4",
            binding.pinKey5 to "5",
            binding.pinKey6 to "6",
            binding.pinKey7 to "7",
            binding.pinKey8 to "8",
            binding.pinKey9 to "9"
        )
        for ((btn, digit) in digitButtons) {
            btn.setOnClickListener { appendPinDigit(digit) }
        }
        binding.pinKeyBack.setOnClickListener {
            if (currentPinInput.isNotEmpty()) {
                currentPinInput.deleteCharAt(currentPinInput.length - 1)
                updatePinDigitsDisplay()
            }
        }
        binding.pinKeyCancel.setOnClickListener {
            closePinModal()
        }
    }

    private fun openPinModal(
        title: String,
        subtitle: String,
        verifyMode: Boolean,
        onSuccess: (String) -> Unit
    ) {
        currentPinInput.clear()
        isPinVerificationMode = verifyMode
        onPinSuccessAction = onSuccess
        binding.pinModalTitle.text = title
        binding.pinModalSubtitle.text = subtitle
        binding.pinModalSubtitle.setTextColor(getColor(R.color.text_secondary))
        updatePinDigitsDisplay()
        binding.pinModalOverlay.visibility = View.VISIBLE
        binding.pinKey1.post { binding.pinKey1.requestFocus() }
    }

    private fun closePinModal() {
        binding.pinModalOverlay.visibility = View.GONE
        currentPinInput.clear()
        onPinSuccessAction = null
        if (isSettingsTabActive) {
            binding.btnSetPin.post { binding.btnSetPin.requestFocus() }
        }
    }

    private fun appendPinDigit(digit: String) {
        if (currentPinInput.length >= 4) return
        currentPinInput.append(digit)
        updatePinDigitsDisplay()

        if (currentPinInput.length == 4) {
            val entered = currentPinInput.toString()
            if (isPinVerificationMode) {
                val savedPin = ChannelOrderManager.getResetPin(this)
                if (entered == savedPin) {
                    val callback = onPinSuccessAction
                    binding.pinModalOverlay.visibility = View.GONE
                    currentPinInput.clear()
                    callback?.invoke(entered)
                } else {
                    currentPinInput.clear()
                    binding.pinModalSubtitle.text = "Hatalı şifre! Lütfen tekrar deneyin."
                    binding.pinModalSubtitle.setTextColor(0xFFF87171.toInt())
                    updatePinDigitsDisplay()
                }
            } else {
                val callback = onPinSuccessAction
                binding.pinModalOverlay.visibility = View.GONE
                currentPinInput.clear()
                callback?.invoke(entered)
            }
        }
    }

    private fun updatePinDigitsDisplay() {
        val chars = (0 until 4).map { idx ->
            if (idx < currentPinInput.length) "●" else "○"
        }
        binding.pinDigitsDisplay.text = chars.joinToString(" ")
    }

    private fun setupChannelMenu() {
        binding.channelMenuMove.setOnClickListener {
            val pos = channelMenuTargetPos
            closeChannelMenu()
            if (pos !in channelAdapter.getChannels().indices) return@setOnClickListener
            if (!channelAdapter.isReorderMode) toggleReorderMode()
            if (channelAdapter.grabbedPosition != pos) channelAdapter.toggleGrab(pos)
            focusChannelRow(pos)
        }
        binding.channelMenuDelete.setOnClickListener {
            val pos = channelMenuTargetPos
            closeChannelMenu()
            if (pos !in channelAdapter.getChannels().indices) return@setOnClickListener
            if (channelAdapter.getChannels().size <= 1) {
                android.widget.Toast.makeText(this, "Son kanal silinemez.", android.widget.Toast.LENGTH_SHORT).show()
                focusChannelRow(pos)
                return@setOnClickListener
            }
            channelAdapter.removeChannelAt(pos)
        }
    }

    private fun isChannelMenuVisible(): Boolean = binding.channelMenuOverlay.visibility == View.VISIBLE

    private fun openChannelMenu(channel: Channel, position: Int) {
        channelMenuTargetPos = position
        binding.channelMenuTitle.text = channel.name
        binding.channelMenuOverlay.visibility = View.VISIBLE
        binding.channelMenuMove.post { binding.channelMenuMove.requestFocus() }
    }

    private fun closeChannelMenu() {
        binding.channelMenuOverlay.visibility = View.GONE
        channelMenuTargetPos = RecyclerView.NO_POSITION
    }

    /** Focuses the row of the given channel index (mapped to its position in the filtered list). */
    private fun focusChannelRow(channelIndex: Int) {
        focusDisplayRow(channelAdapter.displayPositionOf(channelIndex).coerceAtLeast(0))
    }

    private fun focusCategoryChip() {
        val pos = categoryAdapter.selectedPosition
        binding.chipsRecyclerView.scrollToPosition(pos)
        binding.chipsRecyclerView.post {
            binding.chipsRecyclerView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    /** Focuses a row by display position: scrolls first, then focuses once the holder is bound. */
    private fun focusDisplayRow(position: Int, attempts: Int = 5) {
        val rv = binding.channelsRecyclerView
        val count = channelAdapter.itemCount
        if (count == 0) return
        val pos = position.coerceIn(0, count - 1)
        if (attempts == 5) rv.scrollToPosition(pos)
        rv.post {
            val holder = rv.findViewHolderForAdapterPosition(pos)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else if (attempts > 0) {
                focusDisplayRow(pos, attempts - 1)
            }
        }
    }

    /** Returns to the row the user focused last (first row if it is gone after a filter change). */
    private fun focusLastRow() {
        val count = channelAdapter.itemCount
        focusDisplayRow(if (lastRowPosition in 0 until count) lastRowPosition else 0)
    }

    /** Position of the focused row in the channel list, or NO_POSITION. */
    private fun focusedRowPosition(): Int {
        val child = binding.channelsRecyclerView.focusedChild ?: return RecyclerView.NO_POSITION
        return binding.channelsRecyclerView.getChildAdapterPosition(child)
    }

    private fun setupErrorView() {
        binding.btnRetry.setOnClickListener {
            playChannel(currentChannelIndex)
        }
        binding.btnNextChannel.setOnClickListener {
            playNextChannel()
        }
    }

    private fun showErrorView(message: String) {
        binding.bufferingLayout.visibility = View.GONE
        binding.errorMessage.text = message
        setPlaybackStatus(message)
        binding.errorLayout.visibility = View.VISIBLE
        if (!isDrawerVisible() && !isChannelMenuVisible()) {
            binding.btnRetry.post { binding.btnRetry.requestFocus() }
        }
    }

    private fun hideErrorView() {
        binding.errorLayout.visibility = View.GONE
    }

    private fun toggleReorderMode() {
        val newMode = !channelAdapter.isReorderMode
        channelAdapter.setReorderMode(newMode)

        if (newMode) {
            binding.dpadHintText.text = "OK Tut/Bırak · ▲▼ Taşı · Geri Çık"
            binding.dpadHintText.visibility = View.VISIBLE
        } else {
            binding.dpadHintText.visibility = View.GONE
            ChannelOrderManager.saveChannelOrder(this, channelAdapter.getChannels())
        }
    }

    private fun toggleDeleteMode() {
        val newMode = !channelAdapter.isDeleteMode
        channelAdapter.setDeleteMode(newMode)

        if (newMode) {
            binding.dpadHintText.text = "▲▼ Seç · OK Kanalı Sil · Geri Çık"
            binding.dpadHintText.visibility = View.VISIBLE
        } else {
            binding.dpadHintText.visibility = View.GONE
        }
    }

    // IPTV_TIMING: channel-open latency probe (reset per playChannel)
    private var timingChannelId: String? = null
    private var timingStartMs = 0L
    private var timingResolveMs = -1L
    private var timingReadyMs = -1L
    private var timingDone = true
    private var timingSegmentMs = -1L

    private fun setupPlayer() {
        ChannelRepository.installGlobalSslTrust()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(12000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        // Fast start: begin playback after ~1s of media; keep a modest steady-state buffer for live HLS
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 1000, 2500)
            .build()
        // Start on a conservative bandwidth estimate (small first segment); quality ramps up quickly
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(3_000_000L)
            .build()
        val trackSelector = DefaultTrackSelector(this, AdaptiveTrackSelection.Factory(5000, 25000, 25000, 0.7f))

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
            .apply {
                // Cap video resolution at 1920x1080 (Full HD) for smooth Mi Box 4 decoding
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(1920, 1080)
                    .build()

                playWhenReady = true
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    // Network-side milestone (valid even where the decoder cannot render, e.g. emulators)
                    override fun onLoadStarted(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
                    ) {
                        if (timingDone || timingSegmentMs >= 0 || mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                        timingSegmentMs = android.os.SystemClock.elapsedRealtime() - timingStartMs
                        Log.i("IPTV_TIMING", "$timingChannelId resolve=${timingResolveMs}ms segmentRequest=${timingSegmentMs}ms")
                    }
                })
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.bufferingLayout.visibility = View.VISIBLE
                                setPlaybackStatus(getString(R.string.buffering))
                                hideErrorView()
                            }
                            Player.STATE_READY -> {
                                if (!timingDone && timingReadyMs < 0) {
                                    timingReadyMs = android.os.SystemClock.elapsedRealtime() - timingStartMs
                                    Log.i("IPTV_TIMING", "$timingChannelId resolve=${timingResolveMs}ms ready=${timingReadyMs}ms (state ready)")
                                }
                                binding.bufferingLayout.visibility = View.GONE
                                setPlaybackStatus(getString(R.string.status_playing))
                                hideErrorView()
                                hasRetriedCurrentChannel = false
                            }
                            Player.STATE_ENDED -> {
                                binding.bufferingLayout.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {
                                binding.bufferingLayout.visibility = View.GONE
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (timingDone) return
                        timingDone = true
                        val first = android.os.SystemClock.elapsedRealtime() - timingStartMs
                        Log.i("IPTV_TIMING", "$timingChannelId resolve=${timingResolveMs}ms ready=${timingReadyMs}ms firstFrame=${first}ms")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val currentCh = channels.getOrNull(currentChannelIndex)
                        Log.e("IPTV_PLAYER", "Playback error on ${currentCh?.id} (${currentCh?.name}): ${error.errorCodeName}", error)

                        if (!hasRetriedCurrentChannel) {
                            hasRetriedCurrentChannel = true
                            val retryIdx = currentChannelIndex
                            streamResolveJob?.cancel()
                            streamResolveJob = lifecycleScope.launch {
                                delay(600)
                                if (currentChannelIndex == retryIdx && !isFinishing && !isDestroyed) {
                                    val channel = channels.getOrNull(retryIdx) ?: return@launch
                                    val freshUrl = if (channel.id == "teve2") {
                                        // Fallback to direct Duhnet stream if Daion failed
                                        "https://live.duhnet.tv/S2/HLS_LIVE/teve2np/playlist.m3u8"
                                    } else {
                                        ChannelRepository.resolveStreamUrl(channel, useCache = false)
                                    }
                                    exoPlayer?.apply {
                                        setMediaItem(MediaItem.fromUri(freshUrl))
                                        prepare()
                                        play()
                                    }
                                }
                            }
                            return
                        }
                        val currentName = currentCh?.name ?: "Kanal"
                        showErrorView("$currentName açılamadı.")
                    }
                })
            }

        binding.playerView.player = exoPlayer
    }

    private fun playChannel(index: Int) {
        channels = channelAdapter.getChannels()
        if (index !in channels.indices) return

        val channel = channels[index]
        val outgoing = channels.getOrNull(currentChannelIndex)
        if (hasStartedPlayback && outgoing != null && outgoing.id != channel.id) {
            previousChannelId = outgoing.id
        }
        hasStartedPlayback = true
        currentChannelIndex = index
        hasRetriedCurrentChannel = false

        ChannelOrderManager.saveLastChannelId(this, channel.id)
        channelAdapter.setSelectedIndex(index)
        showInfoBanner(channel)

        binding.bufferingLayout.visibility = View.VISIBLE
        hideErrorView()

        timingChannelId = channel.id
        timingStartMs = android.os.SystemClock.elapsedRealtime()
        timingResolveMs = -1L
        timingReadyMs = -1L
        timingDone = false
        timingSegmentMs = -1L

        streamResolveJob?.cancel()
        streamResolveJob = lifecycleScope.launch {
            val streamUrl = ChannelRepository.resolveStreamUrl(channel)
            timingResolveMs = android.os.SystemClock.elapsedRealtime() - timingStartMs
            Log.i("IPTV_PLAYER", "Playing ${channel.id} (${channel.name}) -> $streamUrl")
            if (currentChannelIndex != index || isFinishing || isDestroyed) return@launch
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                play()
            }
            // Warm the neighbours once this channel is under way (keeps the current load uncontended)
            delay(1500)
            if (currentChannelIndex == index) {
                schedulePrewarm(
                    channels.getOrNull((index + 1) % channels.size),
                    channels.getOrNull((index - 1 + channels.size) % channels.size)
                )
            }
        }
    }

    private var prewarmJob: Job? = null
    private val focusPrewarmRunnable = Runnable {
        channelAdapter.channelAtDisplayPosition(lastRowPosition)?.let { schedulePrewarm(it) }
    }

    /** Cancelable background warm-up; a new request replaces the previous one. */
    private fun schedulePrewarm(vararg targets: Channel?) {
        prewarmJob?.cancel()
        prewarmJob = lifecycleScope.launch {
            for (target in targets) {
                if (target == null || target.id == channels.getOrNull(currentChannelIndex)?.id) continue
                ChannelRepository.prewarm(target)
            }
        }
    }

    private fun playNextChannel() {
        channels = channelAdapter.getChannels()
        if (channels.isEmpty()) return
        val nextIndex = (currentChannelIndex + 1) % channels.size
        playChannel(nextIndex)
    }

    private fun playLastChannel() {
        channels = channelAdapter.getChannels()
        val prevId = previousChannelId ?: return
        val prevIdx = channels.indexOfFirst { it.id == prevId }
        if (prevIdx >= 0) playChannel(prevIdx)
    }

    private fun playPreviousChannel() {
        channels = channelAdapter.getChannels()
        if (channels.isEmpty()) return
        val prevIndex = if (currentChannelIndex - 1 < 0) channels.size - 1 else currentChannelIndex - 1
        playChannel(prevIndex)
    }

    private fun setupDrawer() {
        showDrawer()
    }

    private fun isDrawerVisible(): Boolean = binding.channelListDrawer.visibility == View.VISIBLE

    private fun showDrawer() {
        binding.channelListDrawer.visibility = View.VISIBLE
        // The info strip stays visible while the panel is open
        hideBannerHandler.removeCallbacks(hideBannerRunnable)
        channels.getOrNull(currentChannelIndex)?.let { updateInfoStrip(it) }
        binding.bottomInfoBanner.animate().cancel()
        binding.bottomInfoBanner.alpha = 1f
        binding.bottomInfoBanner.visibility = View.VISIBLE
        updateHints()
        focusChannelRow(currentChannelIndex)
    }

    private fun hideDrawer() {
        if (channelAdapter.isReorderMode) {
            toggleReorderMode()
        }
        if (channelAdapter.isDeleteMode) {
            toggleDeleteMode()
        }
        if (isSettingsTabActive) {
            switchDrawerTab(showSettings = false)
        }
        closeChannelMenu()
        binding.channelListDrawer.visibility = View.GONE
        binding.rootLayout.requestFocus()
        updateHints()
        // Let the strip linger for 3 seconds after the panel closes
        hideBannerHandler.removeCallbacks(hideBannerRunnable)
        hideBannerHandler.postDelayed(hideBannerRunnable, 3000)
        if (binding.errorLayout.visibility == View.VISIBLE) {
            binding.btnRetry.post { binding.btnRetry.requestFocus() }
        }
    }

    private fun updateInfoStrip(channel: Channel) {
        binding.bannerChannelNumber.text = String.format("%02d", channel.number)
        binding.bannerChannelName.text = channel.name
        binding.bannerTitle.text = channel.name
        binding.bannerGroup.text = channel.group
    }

    private fun setPlaybackStatus(text: String) {
        binding.bannerStatus.text = text
    }

    /** Remote hints: only keys that really do something in the current state. */
    private fun updateHints() {
        val drawer = isDrawerVisible()
        binding.hintOkLabel.text = if (drawer) "İzle" else "Kanal Listesi"
        binding.hintUpDownLabel.text = if (drawer) "Seç" else "Kanal Değiştir"
        val backVisibility = if (drawer) View.VISIBLE else View.GONE
        binding.hintBackKey.visibility = backVisibility
        binding.hintBackLabel.visibility = backVisibility
    }

    private fun updateClock() {
        val now = Date()
        binding.clockText.text = timeFormat.format(now)
        binding.dateText.text = dateFormat.format(now)
    }

    private fun showInfoBanner(channel: Channel) {
        hideBannerHandler.removeCallbacks(hideBannerRunnable)

        updateInfoStrip(channel)
        setPlaybackStatus(getString(R.string.buffering))
        if (binding.bottomInfoBanner.visibility != View.VISIBLE) {
            binding.bottomInfoBanner.alpha = 0f
            binding.bottomInfoBanner.visibility = View.VISIBLE
        }
        binding.bottomInfoBanner.animate().cancel()
        binding.bottomInfoBanner.animate().alpha(1f).setDuration(200).start()

        // Auto hide after 3 seconds unless the panel is open
        if (!isDrawerVisible()) {
            hideBannerHandler.postDelayed(hideBannerRunnable, 3000)
        }
    }

    private fun hideInfoBanner() {
        if (isFinishing || isDestroyed || isDrawerVisible()) return
        binding.bottomInfoBanner.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                if (!isDrawerVisible()) binding.bottomInfoBanner.visibility = View.GONE
            }
            .start()
    }

    /**
     * TV Remote (D-Pad) Key Handling
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (binding.pinModalOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closePinModal()
                return true
            }
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                val digit = (keyCode - KeyEvent.KEYCODE_0).toString()
                appendPinDigit(digit)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isChannelMenuVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                val pos = channelMenuTargetPos
                closeChannelMenu()
                focusChannelRow(pos)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isDrawerVisible() && isSettingsTabActive) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                switchDrawerTab(showSettings = false)
                binding.btnOpenSettings.requestFocus()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isDrawerVisible() && channelAdapter.isDeleteMode) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                toggleDeleteMode()
                return true
            }
        }

        if (isDrawerVisible() && channelAdapter.isReorderMode) {
            val grabbedPos = channelAdapter.grabbedPosition
            if (grabbedPos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val newPos = channelAdapter.moveGrabbed(-1)
                        binding.channelsRecyclerView.scrollToPosition(newPos)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val newPos = channelAdapter.moveGrabbed(1)
                        binding.channelsRecyclerView.scrollToPosition(newPos)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        channelAdapter.toggleGrab(grabbedPos)
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        channelAdapter.toggleGrab(grabbedPos)
                        return true
                    }
                }
            } else {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    toggleReorderMode()
                    return true
                }
            }
        }

        if (isDrawerVisible()) {
            val browsing = !channelAdapter.isReorderMode && !channelAdapter.isDeleteMode
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.chipsRecyclerView.hasFocus()) {
                        focusLastRow()
                        return true
                    }
                    // Wrap from the last row to the first one (never while moving/deleting)
                    val pos = focusedRowPosition()
                    if (browsing && pos != RecyclerView.NO_POSITION && pos == channelAdapter.itemCount - 1) {
                        focusDisplayRow(0)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (browsing && binding.channelsRecyclerView.hasFocus()) {
                        binding.btnOpenSettings.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (browsing && binding.btnOpenSettings.hasFocus()) {
                        focusLastRow()
                        return true
                    }
                    if (browsing && binding.channelsRecyclerView.hasFocus()) {
                        focusCategoryChip()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Wrap from the first row to the last one (never while moving/deleting)
                    if (browsing && focusedRowPosition() == 0 && channelAdapter.itemCount > 1) {
                        focusDisplayRow(channelAdapter.itemCount - 1)
                        return true
                    }
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (!isDrawerVisible()) {
                    if (ChannelOrderManager.isUpForNextChannel(this)) {
                        playNextChannel()
                    } else {
                        playPreviousChannel()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (!isDrawerVisible()) {
                    if (ChannelOrderManager.isUpForNextChannel(this)) {
                        playPreviousChannel()
                    } else {
                        playNextChannel()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!isDrawerVisible()) {
                    showDrawer()
                    return true
                }
            }
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!isDrawerVisible()) {
                    playLastChannel()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isDrawerVisible()) {
                    hideDrawer()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        exoPlayer?.play()
        clockHandler.post(clockRunnable)
        BlurController.start()
    }

    override fun onStop() {
        super.onStop()
        clockHandler.removeCallbacks(clockRunnable)
        BlurController.stop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        streamResolveJob?.cancel()
        prewarmJob?.cancel()
        hideBannerHandler.removeCallbacks(focusPrewarmRunnable)
        hideBannerHandler.removeCallbacks(hideBannerRunnable)
        BlurController.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}
