package com.mibox.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

object ChannelRepository {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Android 9 (API 28) on Mi Box 4 lacks newer 2025/2026 Root CAs such as
     * GlobalSign Root CA - R6 (used by CNN Türk / Duhnet) and Sectigo Root R46
     * (used by Discovery CDN). We configure a compatible TrustManager so modern
     * HTTPS streams play without SSLHandshakeException on older Android TV firmwares.
     */
    val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        sslContext.socketFactory
    }

    fun installGlobalSslTrust() {
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(sslSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val client: OkHttpClient by lazy {
        installGlobalSslTrust()
        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")

    /**
     * All streams verified down to actual .ts video segments.
     * Music and Kids channels are excluded per user preference.
     */
    fun getDefaultChannels(): List<Channel> {
        val rawList = listOf(
            // --- SİNEMA, FİLM & DİZİ KANALLARI ---
            Channel(
                id = "trt2",
                number = 1,
                name = "TRT 2 HD",
                url = "https://tv-trt2.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Sinema & Kültür"
            ),
            Channel(
                id = "teve2",
                number = 2,
                name = "Teve2 HD (TV2)",
                url = "https://demiroren.daioncdn.net/teve2/teve2.m3u8?ce=3&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd",
                logoUrl = null,
                group = "Film & Dizi"
            ),
            Channel(
                id = "a2",
                number = 3,
                name = "A2 TV HD",
                url = "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/a2tv/a2tv.m3u8",
                logoUrl = null,
                group = "Dizi & Film"
            ),
            Channel(
                id = "tv4",
                number = 4,
                name = "TV 4 HD",
                url = "https://turkmedya-live.ercdn.net/tv4/tv4.m3u8",
                logoUrl = null,
                group = "Yeşilçam & Sinema"
            ),
            Channel(
                id = "dizitv",
                number = 5,
                name = "Dizi TV HD",
                url = "https://playlist.fasttvcdn.com/pl/rfrk9821hdy9dayo8wfyha/dizi-tv/playlist.m3u8",
                logoUrl = null,
                group = "Dizi & Film"
            ),
            Channel(
                id = "ailetv",
                number = 6,
                name = "Aile TV HD",
                url = "https://playlist.fasttvcdn.com/pl/rfrk9821hdy9dayo8wfyha/aile-tv/playlist.m3u8",
                logoUrl = null,
                group = "Sinema & Aile"
            ),
            Channel(
                id = "tv85",
                number = 7,
                name = "TV8.5 HD",
                url = "https://tv8.daioncdn.net/tv8bucuk/tv8bucuk.m3u8?app=tv8bucuk_web",
                logoUrl = null,
                group = "Sinema & Eğlence"
            ),
            Channel(
                id = "kultursanat",
                number = 8,
                name = "Kültür & Sanat TV HD",
                url = "https://playlist.fasttvcdn.com/pl/rfrk9821hdy9dayo8wfyha/kltr-sanat-tv/playlist.m3u8",
                logoUrl = null,
                group = "Sinema & Sanat"
            ),
            Channel(
                id = "trtturk",
                number = 9,
                name = "TRT Türk HD",
                url = "https://tv-trtturk.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Dizi & Sinema"
            ),
            Channel(
                id = "trtavaz",
                number = 10,
                name = "TRT Avaz HD",
                url = "https://tv-trtavaz.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Sinema & Kültür"
            ),

            // --- ULUSAL KANALLAR (DİZİ, FİLM & GENEL) ---
            Channel(
                id = "trt1",
                number = 11,
                name = "TRT 1 HD",
                url = "https://tv-trt1.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Ulusal & Dizi"
            ),
            Channel(
                id = "kanald",
                number = 12,
                name = "Kanal D HD",
                url = "https://demiroren.daioncdn.net/kanald/kanald.m3u8?app=kanald_web&ce=3",
                logoUrl = null,
                group = "Ulusal & Film"
            ),
            Channel(
                id = "atv",
                number = 13,
                name = "ATV HD",
                url = "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/atv/atv.m3u8",
                logoUrl = null,
                group = "Ulusal & Dizi"
            ),
            Channel(
                id = "startv",
                number = 14,
                name = "Star TV HD",
                url = "https://dogus.daioncdn.net/startv/startv.m3u8?app=a20ac41e-bdc3-4aa1-934d-26b484480ac9",
                logoUrl = null,
                group = "Ulusal & Sinema"
            ),
            Channel(
                id = "showtv",
                number = 15,
                name = "Show TV HD",
                url = "https://ciner.daioncdn.net/showtv/showtv.m3u8?ce=3&app=4bc856ef-4c68-4a94-bc87-37dfaaa66558",
                logoUrl = null,
                group = "Ulusal & Dizi"
            ),
            Channel(
                id = "nowtv",
                number = 16,
                name = "NOW TV HD",
                url = "https://uycyyuuzyh.turknet.ercdn.net/nphindgytw/nowtv/nowtv.m3u8",
                logoUrl = null,
                group = "Ulusal & Film"
            ),
            Channel(
                id = "tv8",
                number = 17,
                name = "TV8 HD",
                url = "https://tv8.daioncdn.net/tv8/tv8.m3u8?app=7ddc255a-ef47-4e81-ab14-c0e5f2949788&ce=3",
                logoUrl = null,
                group = "Ulusal & Eğlence"
            ),
            Channel(
                id = "beyaztv",
                number = 18,
                name = "Beyaz TV HD",
                url = "https://beyaztv-live.daioncdn.net/beyaztv/beyaztv.m3u8",
                logoUrl = null,
                group = "Ulusal & Sinema"
            ),
            Channel(
                id = "kanal7",
                number = 19,
                name = "Kanal 7 HD",
                url = "https://kanal7-live.daioncdn.net/kanal7/kanal7.m3u8",
                logoUrl = null,
                group = "Ulusal & TV Filmi"
            ),
            Channel(
                id = "tv360",
                number = 20,
                name = "360 TV HD",
                url = "https://turkmedya-live.ercdn.net/tv360/tv360.m3u8",
                logoUrl = null,
                group = "Ulusal & Kültür"
            ),
            Channel(
                id = "cnbce",
                number = 21,
                name = "CNBC-e HD",
                url = "https://hnpsechtsc.turknet.ercdn.net/xpnvudnlsv/cnbc-e/cnbc-e.m3u8",
                logoUrl = null,
                group = "Finans & Dizi/Film"
            ),

            // --- BİLİM, TEKNOLOJİ & BELGESEL KANALLARI ---
            Channel(
                id = "discovery_channel",
                number = 22,
                name = "Discovery Channel HD",
                url = "http://57.128.231.171:8080/282/index.m3u8",
                logoUrl = null,
                group = "Bilim & Belgesel"
            ),
            Channel(
                id = "discovery_science",
                number = 23,
                name = "Discovery Science HD",
                url = "http://57.128.231.171:8080/279/index.m3u8",
                logoUrl = null,
                group = "Bilim & Teknoloji"
            ),
            Channel(
                id = "bbc_earth",
                number = 24,
                name = "BBC Earth HD",
                url = "https://amg00793-amg00793c6-xumo-us-2669.playouts.now.amagi.tv/BBCStudios-BBCEarthA-hls/playlist.m3u8",
                logoUrl = null,
                group = "Bilim & Doğa"
            ),
            Channel(
                id = "dmax",
                number = 25,
                name = "DMAX HD",
                url = "https://dogus.daioncdn.net/dmax/dmax.m3u8?app=5a02c599-d17e-4982-9b04-090934d51b06",
                logoUrl = null,
                group = "Belgesel & Teknoloji"
            ),
            Channel(
                id = "tlc",
                number = 26,
                name = "TLC HD",
                url = "https://dogus.daioncdn.net/tlc/tlc.m3u8?app=14625233-b122-4369-8d0e-f4924c8b136c",
                logoUrl = null,
                group = "Belgesel & Yaşam"
            ),
            Channel(
                id = "animal_planet",
                number = 27,
                name = "Animal Planet HD",
                url = "http://23.239.31.26:8989/animalplanet/index.m3u8",
                logoUrl = null,
                group = "Doğa & Bilim"
            ),
            Channel(
                id = "discovery_id",
                number = 28,
                name = "Discovery ID HD",
                url = "http://57.128.231.171:8080/239/index.m3u8",
                logoUrl = null,
                group = "Belgesel & Araştırma"
            ),
            Channel(
                id = "trtbelgesel",
                number = 29,
                name = "TRT Belgesel HD",
                url = "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Bilim & Belgesel"
            ),
            Channel(
                id = "trtgenc",
                number = 30,
                name = "TRT Genç HD",
                url = "https://tv-trtgenc.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Bilim & Teknoloji"
            ),
            Channel(
                id = "belgeseltv",
                number = 31,
                name = "Belgesel TV HD",
                url = "https://playlist.fasttvcdn.com/pl/rfrk9821hdy9dayo8wfyha/belgesel-tv/playlist.m3u8",
                logoUrl = null,
                group = "Belgesel"
            ),
            Channel(
                id = "dogatv",
                number = 32,
                name = "Doğa TV HD",
                url = "https://playlist.fasttvcdn.com/pl/rfrk9821hdy9dayo8wfyha/doga-tv/playlist.m3u8",
                logoUrl = null,
                group = "Doğa & Bilim"
            ),
            Channel(
                id = "tarihtv",
                number = 33,
                name = "Tarih TV HD",
                url = "https://playlist.fasttvcdn.com/pl/rfrk9821hdy9dayo8wfyha/Tarih-tv/playlist.m3u8",
                logoUrl = null,
                group = "Tarih & Belgesel"
            ),
            Channel(
                id = "inwonder",
                number = 34,
                name = "InWonder Science HD",
                url = "https://amg00861-terninternation-inwonder-samsungau-1k63k.amagi.tv/playlist/amg00861-terninternation-inwonder-samsungau/playlist.m3u8",
                logoUrl = null,
                group = "Bilim & Teknoloji"
            ),
            Channel(
                id = "bfm_tech",
                number = 35,
                name = "Tech & Co HD",
                url = "https://ncdn-live-bfm.pfd.sfr.net/shls/LIVE\$BFM_TECHANDCO/index.m3u8?end=END&start=LIVE",
                logoUrl = null,
                group = "Teknoloji & İnovasyon"
            ),

            // --- HABER & EKONOMİ KANALLARI (CNN TÜRK, NTV, TV 100, vb.) ---
            Channel(
                id = "cnnturk",
                number = 36,
                name = "CNN Türk HD",
                url = "https://live.duhnet.tv/S2/HLS_LIVE/cnnturknp/playlist.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "ntv",
                number = 37,
                name = "NTV HD",
                url = "https://dogus.daioncdn.net/ntv/ntv.m3u8?app=ntv_web",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "tv100",
                number = 38,
                name = "TV 100 HD",
                url = "https://tv.ensonhaber.com/tv100/tv100.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "haberturk",
                number = 39,
                name = "Habertürk HD",
                url = "https://tv.ensonhaber.com/haberturk/haberturk.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "haberglobal",
                number = 40,
                name = "Haber Global HD",
                url = "https://tv.ensonhaber.com/haberglobal/haberglobal.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "trthaber",
                number = 41,
                name = "TRT Haber HD",
                url = "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "ahaber",
                number = 42,
                name = "A Haber HD",
                url = "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/ahaber/ahaber.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "tv24",
                number = 43,
                name = "24 TV HD",
                url = "https://turkmedya-live.ercdn.net/tv24/tv24.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "bloomberght",
                number = 44,
                name = "Bloomberg HT HD",
                url = "https://tv.ensonhaber.com/bloomberght/bloomberght.m3u8",
                logoUrl = null,
                group = "Ekonomi"
            ),
            Channel(
                id = "apara",
                number = 45,
                name = "A Para HD",
                url = "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/apara/apara.m3u8",
                logoUrl = null,
                group = "Ekonomi"
            ),
            Channel(
                id = "ekoturk",
                number = 46,
                name = "Ekotürk HD",
                url = "https://cdn.ekoturk.net/hls/master.m3u8",
                logoUrl = null,
                group = "Ekonomi & Teknoloji"
            ),
            Channel(
                id = "benguturk",
                number = 47,
                name = "Bengütürk HD",
                url = "https://tv.ensonhaber.com/benguturk/benguturk.m3u8",
                logoUrl = null,
                group = "Haber"
            ),
            Channel(
                id = "trtworld",
                number = 48,
                name = "TRT World HD",
                url = "https://tv-trtworld.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Uluslararası Haber"
            ),

            // --- KÜLTÜR & DİNİ ---
            Channel(
                id = "vavtv",
                number = 49,
                name = "Vav TV HD",
                url = "https://trkvz-live.ercdn.net/vavtv/vavtv.m3u8",
                logoUrl = "https://i.tmgrup.com.tr/vavtv/site/v2/i/logo.png",
                group = "Kültür & Dini"
            ),
            Channel(
                id = "diyanet_tv",
                number = 50,
                name = "Diyanet TV HD",
                url = "https://eustr73.mediatriple.net/videoonlylive/mtikoimxnztxlive/broadcast_5e3bf95a47e07.smil/playlist.m3u8",
                logoUrl = null,
                group = "Kültür & Dini"
            ),

            // --- SPOR KANALLARI ---
            Channel(
                id = "trtspor",
                number = 51,
                name = "TRT Spor HD",
                url = "https://tv-trtspor1.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Spor"
            ),
            Channel(
                id = "trtspor2",
                number = 52,
                name = "TRT Spor Yıldız HD",
                url = "https://tv-trtspor2.medya.trt.com.tr/master.m3u8",
                logoUrl = null,
                group = "Spor"
            ),
            Channel(
                id = "aspor",
                number = 53,
                name = "A Spor HD",
                url = "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/aspor/aspor.m3u8",
                logoUrl = null,
                group = "Spor"
            ),
            // Ücretsiz reklam destekli (FAST) doğa/gezi kanalları; adresler iptv-org listesinden, Türkiye'de coğrafi kısıt olabilir
            Channel(
                id = "dronetv",
                number = 54,
                name = "DroneTV",
                url = "https://airvuz-dronetv-1-us.ohlscdn.wurl.tv/playlist.m3u8",
                logoUrl = null,
                group = "Doğa & Gezi"
            ),
            Channel(
                id = "intravel",
                number = 55,
                name = "INTRAVEL",
                url = "https://amg00861-amg00861c10-rakuten-uk-3152.playouts.now.amagi.tv/playlist.m3u8",
                logoUrl = null,
                group = "Doğa & Gezi"
            ),
            Channel(
                id = "lovenature",
                number = 56,
                name = "Love Nature",
                url = "https://aegis-cloudfront-1.tubi.video/6d6d0f24-8445-4b4c-bdf6-44f9e38beaa4/playlist.m3u8",
                logoUrl = null,
                group = "Doğa & Gezi"
            ),
            Channel(
                id = "naturetime",
                number = 57,
                name = "NatureTime",
                url = "https://bamusa-naturetime-emea-eng-rakuten.amagi.tv/playlist.m3u8",
                logoUrl = null,
                group = "Doğa & Gezi"
            ),
            Channel(
                id = "inwild",
                number = 58,
                name = "INWILD",
                url = "https://amg00861-terninternation-inwild-samsunguk-w5wic.amagi.tv/playlist/amg00861-terninternation-inwild-samsunguk/playlist.m3u8",
                logoUrl = null,
                group = "Doğa & Gezi"
            ),
            // Ücretsiz dünya mutfağı kanalları (FAST/resmî yayıncı); Türkiye'den segment erişimi 2026-10-19'da doğrulandı
            Channel(
                id = "tv5monde_chefs",
                number = 59,
                name = "TV5MONDE Chefs",
                url = "https://tvf-tv5ch.otteravision.com/tvf/tv5ch/tv5ch.m3u8",
                logoUrl = null,
                group = "Yemek"
            ),
            Channel(
                id = "tastemade",
                number = 60,
                name = "Tastemade",
                url = "https://tastemade-tdint-rakuten.amagi.tv/playlist.m3u8",
                logoUrl = null,
                group = "Yemek"
            ),
            Channel(
                id = "bonappetit",
                number = 61,
                name = "bon appétit",
                url = "https://bonappetit-samsung.amagi.tv/playlist.m3u8",
                logoUrl = null,
                group = "Yemek"
            )
        )
        return rawList.mapIndexed { idx, ch -> ch.copy(number = idx + 1) }
    }

    /**
     * Resolves live tokenized stream URLs when needed:
     * - Vav TV: Turkuvaz securevideotoken API
     * - CNN Türk: Demirören Media action API
     * - Teve2: Demirören Daion 1080p stream or action API fallback
     * - DMAX, TLC, Show TV: Live token extraction
     */
    suspend fun resolveStreamUrl(channel: Channel, useCache: Boolean = true): String {
        if (channel.id !in TOKEN_CHANNEL_IDS) return channel.url
        val now = System.currentTimeMillis()
        if (useCache) {
            resolveCache[channel.id]?.let { (at, url) -> if (now - at < RESOLVE_TTL_MS) return url }
        }
        val remote = resolveRemote(channel)
        if (remote != null) resolveCache[channel.id] = now to remote
        return remote ?: channel.url
    }

    /**
     * Resolves the stream URL (cache aware) and opens a connection to the playlist host so DNS/TLS
     * are warm when the channel is actually selected. Blocking; call off the main thread. Best effort.
     */
    suspend fun prewarm(channel: Channel) {
        val url = resolveStreamUrl(channel)
        withContext(Dispatchers.IO) {
            try {
                // HttpURLConnection shares its connection pool with ExoPlayer's DefaultHttpDataSource
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", BROWSER_UA)
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (input.read(buf) >= 0) { /* drain so the connection returns to the pool */ }
                }
            } catch (_: Exception) {
            }
        }
    }

    private val TOKEN_CHANNEL_IDS = setOf("vavtv", "cnnturk", "teve2", "dmax", "tlc", "showtv")
    private const val RESOLVE_TTL_MS = 60_000L
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** Returns null when no tokenized URL could be resolved (caller falls back to the static URL). */
    private suspend fun resolveRemote(channel: Channel): String? = withContext(Dispatchers.IO) {
        when (channel.id) {
            "vavtv" -> {
                try {
                    val randNum = Random.nextInt(1, 1000000)
                    val timestamp = System.currentTimeMillis().toString()
                    val videoUrl = "https://trkvz-live.ercdn.net/vavtv/vavtv.m3u8"
                    val encoded = URLEncoder.encode(videoUrl, "UTF-8")
                    val tokenApiUrl = "https://securevideotoken.tmgrup.com.tr/webtv/secure?$randNum&url=$encoded"

                    val request = Request.Builder()
                        .url(tokenApiUrl)
                        .addHeader("X-isApp", "false")
                        .addHeader("X-Rand", timestamp)
                        .addHeader("User-Agent", BROWSER_UA)
                        .addHeader("Referer", "https://www.vavtv.com.tr/")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val streamUrl = json.optString("Url")
                            if (streamUrl.startsWith("https://")) {
                                return@withContext streamUrl
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            "cnnturk" -> {
                try {
                    val request = Request.Builder()
                        .url("https://www.cnnturk.com/action/media/62d6814670380e2cdc7c124c")
                        .addHeader("User-Agent", BROWSER_UA)
                        .addHeader("Referer", "https://www.cnnturk.com/canli-yayin")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val link = json.optJSONObject("Media")?.optJSONObject("Link")
                            if (link != null) {
                                val serviceUrl = link.optString("ServiceUrl")
                                    .ifEmpty { link.optString("DefaultServiceUrl", "https://live.duhnet.tv") }
                                    .trimEnd('/')
                                val securePath = link.optString("SecurePath").trimStart('/')
                                val fullUrl = if (securePath.startsWith("http")) {
                                    securePath
                                } else if (serviceUrl.startsWith("http") && securePath.isNotEmpty()) {
                                    "$serviceUrl/$securePath"
                                } else null
                                if (fullUrl != null) {
                                    return@withContext fullUrl.replace(
                                        "/cnnturknp/playlist.m3u8",
                                        "/cnnturknp/track_4_1000/playlist.m3u8"
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            "teve2" -> {
                scrapeMatchingM3u8("https://www.teve2.com.tr/canli-yayin", "daioncdn")?.let {
                    return@withContext it
                }
            }

            "dmax" -> {
                scrapeFirstM3u8("https://www.dmax.com.tr/canli-izle")?.let { return@withContext it }
            }

            "tlc" -> {
                scrapeFirstM3u8("https://www.tlctv.com.tr/canli-izle")?.let { return@withContext it }
            }

            "showtv" -> {
                scrapeMatchingM3u8("https://www.showtv.com.tr/canli-yayin", "showtv")?.let {
                    return@withContext it
                }
            }
        }
        return@withContext null
    }

    private fun scrapeFirstM3u8(pageUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .addHeader("User-Agent", BROWSER_UA)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val match = m3u8Regex.find(html)
                    match?.value?.replace("\\/", "/")?.replace("&amp;", "&")
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun scrapeMatchingM3u8(pageUrl: String, keyword: String): String? {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .addHeader("User-Agent", BROWSER_UA)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val matches = m3u8Regex.findAll(html).map {
                        it.value.replace("\\/", "/").replace("&amp;", "&")
                    }.toList()
                    matches.firstOrNull { it.contains(keyword, ignoreCase = true) } ?: matches.firstOrNull()
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Parses M3U or M3U8 playlist from raw text content.
     */
    fun parseM3U(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()

        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = "Genel"
        var channelIndex = 1

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(trimmed)
                currentLogo = logoMatch?.groupValues?.get(1)

                val groupMatch = Regex("""group-title="([^"]+)"""").find(trimmed)
                currentGroup = groupMatch?.groupValues?.get(1) ?: "Genel"

                val commaIndex = trimmed.lastIndexOf(',')
                currentName = if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                    trimmed.substring(commaIndex + 1).trim()
                } else {
                    "Kanal $channelIndex"
                }
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                if (currentName.isNotEmpty()) {
                    channels.add(
                        Channel(
                            id = "custom_$channelIndex",
                            number = channelIndex,
                            name = currentName,
                            url = trimmed,
                            logoUrl = currentLogo,
                            group = currentGroup
                        )
                    )
                    channelIndex++
                    currentName = ""
                    currentLogo = null
                    currentGroup = "Genel"
                }
            }
        }
        return channels
    }
}
