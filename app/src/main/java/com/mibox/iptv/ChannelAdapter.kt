package com.mibox.iptv

import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class ChannelAdapter(
    private var channels: MutableList<Channel>,
    private val onChannelSelected: (Channel, Int) -> Unit,
    private val onOrderChanged: (List<Channel>) -> Unit,
    private val onChannelDeleted: (Channel, Int, List<Channel>) -> Unit,
    private val onChannelLongPressed: (Channel, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var selectedIndex: Int = 0

    // Category filter: `visible` holds indices into `channels` for the rows currently shown.
    // Reorder and delete modes always run unfiltered, so display positions equal real indices there.
    private var filterToken: String? = null
    private var visible: List<Int> = emptyList()
    var onFilterReset: (() -> Unit)? = null
    var onRowFocused: ((Int) -> Unit)? = null

    init {
        rebuildVisible()
    }

    private fun rebuildVisible() {
        visible = channels.indices.filter { ChannelCategories.matches(channels[it], filterToken) }
    }

    fun setFilter(token: String?) {
        if (filterToken == token) return
        filterToken = token
        rebuildVisible()
        notifyDataSetChanged()
    }

    private fun resetFilterForEditing() {
        if (filterToken == null) return
        filterToken = null
        rebuildVisible()
        onFilterReset?.invoke()
    }

    /** Row position of a channel index in the (possibly filtered) list, or -1 when it is filtered out. */
    fun displayPositionOf(channelIndex: Int): Int = visible.indexOf(channelIndex)
    var isReorderMode: Boolean = false
        private set
    var isDeleteMode: Boolean = false
        private set
    var grabbedPosition: Int = RecyclerView.NO_POSITION
        private set

    fun getChannels(): List<Channel> = channels

    fun channelAtDisplayPosition(position: Int): Channel? = visible.getOrNull(position)?.let { channels.getOrNull(it) }

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels.toMutableList()
        renumberChannels()
        rebuildVisible()
        notifyDataSetChanged()
    }

    fun setReorderMode(enabled: Boolean) {
        if (isReorderMode == enabled) return
        isReorderMode = enabled
        if (enabled) {
            isDeleteMode = false
            resetFilterForEditing()
        }
        if (!enabled && grabbedPosition != RecyclerView.NO_POSITION) {
            val prevGrabbed = grabbedPosition
            grabbedPosition = RecyclerView.NO_POSITION
            notifyItemChanged(prevGrabbed)
        }
        notifyDataSetChanged()
    }

    fun setDeleteMode(enabled: Boolean) {
        if (isDeleteMode == enabled) return
        isDeleteMode = enabled
        if (enabled) {
            isReorderMode = false
            grabbedPosition = RecyclerView.NO_POSITION
            resetFilterForEditing()
        }
        notifyDataSetChanged()
    }

    fun removeChannelAt(position: Int) {
        if (position !in channels.indices || channels.size <= 1) return
        val removed = channels.removeAt(position)
        renumberChannels()
        if (selectedIndex >= channels.size) {
            selectedIndex = channels.size - 1
        } else if (position < selectedIndex) {
            selectedIndex -= 1
        }
        rebuildVisible()
        notifyDataSetChanged()
        onChannelDeleted(removed, position, channels)
    }

    fun toggleGrab(position: Int) {
        if (position !in channels.indices) return
        val prev = grabbedPosition
        grabbedPosition = if (grabbedPosition == position) {
            RecyclerView.NO_POSITION
        } else {
            position
        }
        if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
        if (grabbedPosition != RecyclerView.NO_POSITION) notifyItemChanged(grabbedPosition)
    }

    fun moveGrabbed(direction: Int): Int {
        if (grabbedPosition == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        val targetPos = grabbedPosition + direction
        if (targetPos in channels.indices) {
            Collections.swap(channels, grabbedPosition, targetPos)
            val oldPos = grabbedPosition
            grabbedPosition = targetPos
            renumberChannels()
            notifyItemMoved(oldPos, targetPos)
            notifyItemChanged(oldPos)
            notifyItemChanged(targetPos)
            onOrderChanged(channels)
            return targetPos
        }
        return grabbedPosition
    }

    private fun renumberChannels() {
        channels.forEachIndexed { index, channel ->
            channel.number = index + 1
        }
    }

    fun setSelectedIndex(index: Int) {
        if (index !in channels.indices || index == selectedIndex) return
        val previousIndex = selectedIndex
        selectedIndex = index
        val previousPos = displayPositionOf(previousIndex)
        if (previousPos >= 0) notifyItemChanged(previousPos)
        val newPos = displayPositionOf(selectedIndex)
        if (newPos >= 0) notifyItemChanged(newPos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val realIndex = visible[position]
        holder.bind(
            channel = channels[realIndex],
            isPlaying = (realIndex == selectedIndex),
            isGrabbed = (realIndex == grabbedPosition),
            isReordering = isReorderMode,
            isDeleting = isDeleteMode
        )
    }

    override fun getItemCount(): Int = visible.size

    fun getBadgeMonogram(channel: Channel): String {
        return when (channel.id) {
            "trt1" -> "TRT1"
            "trt2" -> "TRT2"
            "trtturk" -> "TRTT"
            "trtavaz" -> "AVAZ"
            "trthaber" -> "TRTH"
            "trtworld" -> "TRTW"
            "trtbelgesel" -> "TRTB"
            "trtgenc" -> "TRTG"
            "trtspor" -> "SPOR"
            "trtspor2" -> "YLDZ"
            "a2" -> "A2"
            "atv" -> "ATV"
            "ahaber" -> "AHBR"
            "apara" -> "APRA"
            "aspor" -> "ASPR"
            "teve2" -> "TV2"
            "tv4" -> "TV4"
            "tv85" -> "8.5"
            "dizitv" -> "DİZİ"
            "ailetv" -> "AİLE"
            "kultursanat" -> "SNT"
            "kanald" -> "KNLD"
            "startv" -> "STAR"
            "showtv" -> "SHOW"
            "nowtv" -> "NOW"
            "tv8" -> "TV8"
            "beyaztv" -> "BYZ"
            "kanal7" -> "KNL7"
            "tv360" -> "360"
            "cnbce" -> "CNBC"
            "discovery_channel" -> "DISC"
            "discovery_science" -> "DSCI"
            "discovery_turbo" -> "TRBO"
            "dmax" -> "DMAX"
            "tlc" -> "TLC"
            "animal_planet" -> "ANML"
            "discovery_id" -> "D-ID"
            "belgeseltv" -> "BELG"
            "dogatv" -> "DOĞA"
            "tarihtv" -> "TRH"
            "inwonder" -> "SCIE"
            "bfm_tech" -> "TECH"
            "cnnturk" -> "CNN"
            "ntv" -> "NTV"
            "tv100" -> "100"
            "haberturk" -> "HT"
            "haberglobal" -> "GLBL"
            "tv24" -> "24"
            "bloomberght" -> "BHT"
            "ekoturk" -> "EKO"
            "benguturk" -> "BNG"
            "vavtv" -> "VAV"
            "diyanet_tv" -> "DİYN"
            else -> channel.name.replace(" HD", "").take(4).uppercase()
        }
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val numberText: TextView = itemView.findViewById(R.id.channelNumber)
        private val badgeText: TextView = itemView.findViewById(R.id.channelBadgeText)
        private val nameText: TextView = itemView.findViewById(R.id.channelName)
        private val groupText: TextView = itemView.findViewById(R.id.channelGroup)
        private val liveBadge: View = itemView.findViewById(R.id.channelLiveBadge)
        private val chevron: View = itemView.findViewById(R.id.focusChevron)
        private var showPlayingIndicator = false
        private val reorderIndicator: TextView = itemView.findViewById(R.id.reorderIndicator)
        private val playingIndicator: View = itemView.findViewById(R.id.playingIndicator)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val realIndex = visible[pos]
                    when {
                        isReorderMode -> toggleGrab(realIndex)
                        isDeleteMode -> removeChannelAt(realIndex)
                        else -> onChannelSelected(channels[realIndex], realIndex)
                    }
                }
            }

            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || isReorderMode || isDeleteMode) {
                    false
                } else {
                    val realIndex = visible[pos]
                    onChannelLongPressed(channels[realIndex], realIndex)
                    true
                }
            }

            // Focus: slight growth + elevation (cheap); the blue glass fill and ring come from the selector
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(160)
                    .start()
                applyFocusState(hasFocus)
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onRowFocused?.invoke(pos)
                }
            }
        }

        private var appliedFocus: Boolean? = null

        // Stitch focused row: px20 py14, number 26 Sora, logo 48x40, name Sora 19, sub 16 white.
        // Only re-applied when the focus state really changes (no work while scrolling/binding).
        private fun applyFocusState(hasFocus: Boolean) {
            liveBadge.visibility = View.GONE
            chevron.visibility = if (hasFocus) View.VISIBLE else View.GONE
            playingIndicator.visibility = if (showPlayingIndicator && !hasFocus) View.VISIBLE else View.GONE
            numberText.setTextColor(if (hasFocus) WHITE else NUMBER_IDLE)
            if (appliedFocus == hasFocus) return
            appliedFocus = hasFocus
            val d = itemView.resources.displayMetrics.density
            fun px(v: Int) = (v * d).toInt()
            if (hasFocus) {
                itemView.setPadding(px(20), px(14), px(20), px(14))
                numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                numberText.layoutParams.width = px(44)
                numberText.typeface = soraBold(itemView)
                badgeText.layoutParams.width = px(48)
                badgeText.layoutParams.height = px(40)
                badgeText.setBackgroundResource(R.drawable.bg_logo_pill_focused)
                badgeText.setTextColor(TINT)
                nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                nameText.typeface = soraBold(itemView)
                nameText.setTextColor(WHITE)
                groupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                groupText.setTextColor(WHITE)
            } else {
                itemView.setPadding(px(16), px(10), px(16), px(10))
                numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                numberText.layoutParams.width = px(34)
                numberText.typeface = soraBold(itemView)
                badgeText.layoutParams.width = px(44)
                badgeText.layoutParams.height = px(36)
                badgeText.setBackgroundResource(R.drawable.bg_logo_pill)
                badgeText.setTextColor(ON_SURFACE)
                nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                nameText.typeface = outfitBold(itemView)
                nameText.setTextColor(ON_SURFACE)
                groupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                groupText.setTextColor(VARIANT)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                nameText.lineHeight = px(if (hasFocus) 28 else 22)
                groupText.lineHeight = px(if (hasFocus) 24 else 20)
            }
            numberText.requestLayout()
        }

        fun bind(
            channel: Channel,
            isPlaying: Boolean,
            isGrabbed: Boolean,
            isReordering: Boolean,
            isDeleting: Boolean
        ) {
            numberText.text = String.format("%02d", channel.number)
            badgeText.text = getBadgeMonogram(channel)
            nameText.text = channel.name
            groupText.text = channel.group
            showPlayingIndicator = isPlaying && !isReordering && !isDeleting
            applyFocusState(itemView.hasFocus())
            itemView.isSelected = isPlaying && !isReordering && !isDeleting

            when {
                isReordering -> {
                    reorderIndicator.visibility = View.VISIBLE
                    reorderIndicator.setTextColor(0xFF5A9EFF.toInt())
                    if (isGrabbed) {
                        reorderIndicator.text = "▲ TAŞI ▼"
                        itemView.setBackgroundResource(R.drawable.bg_channel_card_grabbed)
                    } else {
                        reorderIndicator.text = "▲▼"
                        itemView.setBackgroundResource(R.drawable.bg_channel_card)
                    }
                }
                isDeleting -> {
                    reorderIndicator.visibility = View.VISIBLE
                    reorderIndicator.setTextColor(0xFFF87171.toInt())
                    reorderIndicator.text = "✕ SİL"
                    itemView.setBackgroundResource(R.drawable.bg_channel_card)
                }
                else -> {
                    reorderIndicator.visibility = View.GONE
                    itemView.setBackgroundResource(R.drawable.bg_channel_card)
                }
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val NUMBER_IDLE = 0xFF8E909A.toInt()
        const val ON_SURFACE = 0xFFDEE2F5.toInt()
        const val VARIANT = 0xFFC4C6D0.toInt()
        const val TINT = 0xFFADC7FD.toInt()

        private var sora: Typeface? = null
        private var outfit: Typeface? = null
        fun soraBold(v: View): Typeface =
            sora ?: Typeface.create(ResourcesCompat.getFont(v.context, R.font.sora), Typeface.BOLD).also { sora = it }
        fun outfitBold(v: View): Typeface =
            outfit ?: Typeface.create(ResourcesCompat.getFont(v.context, R.font.outfit), Typeface.BOLD).also { outfit = it }
    }
}
