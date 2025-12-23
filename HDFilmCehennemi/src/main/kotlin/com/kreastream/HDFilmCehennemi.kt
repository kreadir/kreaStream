package com.kreastream

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.text.Charsets

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.ws"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override var hasDownloadSupport   = true 
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage             = true
    override var sequentialMainPageDelay        = 50L
    override var sequentialMainPageScrollDelay  = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    
    class CloudflareKiller { 
        fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request()) 
    }
    
    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            return response
        }
    }

    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    private data class PosterData(
        val title: String,
        val newTitle: String,
        val href: String,
        val posterUrl: String?,
        val lang: String?,
        val year: Int?,
        val score: Float?,
        val tvType: TvType,
        val hasDub: Boolean,
        val hasSub: Boolean 
    )

    private data class LoadData(
        val title: String,
        val newTitle: String,
        val poster: String?,
        val tags: List<String>,
        val year: Int?,
        val tvType: TvType,
        val description: String?,
        val score: Float?,
        val actors: List<Actor>,
        val trailer: String?
    )

    private fun Document.extractLoadData(): LoadData? {
        val title = this.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(this.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = this.select("div.post-info-genres a").map { it.text() }
        val year = this.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (this.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = this.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score = this.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
        val lang = this.selectFirst(".language-link")?.text()?.trim()
        val hasDub = lang?.contains("Dublaj", ignoreCase = true) == true
        val newTitle = if (hasDub) "🇹🇷 ${title}" else title

        val actors = this.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")?.text() ?: it.text(), fixUrlNull(it.selectFirst("img")?.attr("data-src")))
        }

        val trailer = this.selectFirst("div.post-info-trailer button")?.attr("data-modal")
            ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

        return LoadData(title, newTitle, poster, tags, year, tvType, description, score, actors, trailer)
    }

    private fun Element.extractPosterData(): PosterData? {
        val title = this.attr("title")
            .takeIf { it.isNotEmpty() }?.trim()
            ?: this.selectFirst("strong.poster-title")?.text()?.trim()
            ?: this.selectFirst("h4.title")?.text()?.trim()
            ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img[data-src], img[src]")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src"))
            ?.replace("/list/", "/")
            ?.replace("/thumb/", "/")

        val year = this.selectFirst(".poster-meta span")?.text()?.trim()?.toIntOrNull()
        val score = this.selectFirst(".poster-meta .imdb")?.ownText()?.trim()?.toFloatOrNull()
        
        val lang = this.selectFirst(".poster-lang span, .poster-meta-genre span")?.text()?.trim()
        
        val hasDub = lang?.contains("Dublaj", ignoreCase = true) == true || lang?.contains("Yerli", ignoreCase = true) == true
        val hasSub = lang?.contains("Altyazılı", ignoreCase = true) == true
        
        val newTitle = if (hasDub) "🇹🇷 ${title}" else title

        val typeCheck = this.attr("href").contains("/dizi/", ignoreCase = true) || this.attr("href").contains("/series", ignoreCase = true)
        val tvType = if (typeCheck) TvType.TvSeries else TvType.Movie

        return PosterData(title, newTitle, href, posterUrl, lang, year, score, tvType, hasDub, hasSub)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/1/home/"                                      to "Yeni Filmler",
        "${mainUrl}/load/page/1/languages/turkce-dublajli-film-izleyin-3/"   to "Türkçe Dublaj Filmler",
        "${mainUrl}/load/page/1/countries/turkiye-2/"                        to "Türk Filmleri",
        "${mainUrl}/load/page/1/recent-episodes/"                            to "Yeni Bölümler",
        "${mainUrl}/load/page/1/home-series/"                                to "Yeni Diziler",
        "${mainUrl}/load/page/1/categories/tavsiye-filmler-izle2/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/1/genres/aksiyon-filmleri-izleyin-5/"          to "Aksiyon Filmleri",
        "${mainUrl}/load/page/1/genres/animasyon-filmlerini-izleyin-5/"      to "Animasyon Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/","/tur/")
                .replace("/load/page/1/categories/","/category/")
                .replace("/load/page/1/imdb7/","/imdb-7-puan-uzeri-filmler/")
                .replace("/load/page/1/languages/","/dil/")
                .replace("/load/page/1/countries/","/ulke/")
        } else {
            request.data.replace("/page/1/", "/page/${page}/")
        }

        val response = app.get(url, headers = standardHeaders, referer = mainUrl)

        if (response.text.contains("Sayfa Bulunamadı")) {
            return newHomePageResponse(request.name, emptyList())
        }

        try {
            val hdfc: HDFC = objectMapper.readValue(response.text, HDFC::class.java)
            val document = Jsoup.parse(hdfc.html)
            val results = document.select("a.poster, a.mini-poster").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        if (this.hasClass("mini-poster")) {
            val seriesTitle = this.selectFirst(".mini-poster-title")?.text()?.trim() ?: return null

            val href = fixUrlNull(this.attr("href")) ?: return null
            val episodeInfo = this.selectFirst(".mini-poster-episode-info")?.text()?.trim() ?: ""
            val posterUrl = fixUrlNull(this.selectFirst("img[data-src], img[src]")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src"))
                ?.replace("/list/", "/") 
                ?.replace("/thumb/", "/")

            val newName = "$seriesTitle - $episodeInfo"
            
            return newTvSeriesSearchResponse(newName, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        
        val data = this.extractPosterData() ?: return null
        
        return newMovieSearchResponse(data.newTitle, data.href, data.tvType) {
            this.posterUrl = data.posterUrl
            this.score = Score.from10(data.score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val data = document.selectFirst("a")?.extractPosterData() ?: return@forEach
            
            searchResults.add(
                newMovieSearchResponse(data.newTitle, data.href, data.tvType) {
                    this.posterUrl = data.posterUrl
                        ?.replace("/list/", "/")
                        ?.replace("/thumb/", "/")
                    this.score = Score.from10(data.score)
                }
            )
        }
        return searchResults
    }
    
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val data = document.extractLoadData() ?: return null

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?:
            fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, data.tvType) { 
                this.posterUrl = recPosterUrl
            }
        }

        return if (data.tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(data.newTitle, url, data.tvType, episodes) {
                this.posterUrl       = data.poster
                this.year            = data.year
                this.plot            = data.description
                this.tags            = data.tags
                this.score           = Score.from10(data.score)
                this.recommendations = recommendations
                addActors(data.actors)
                addTrailer(data.trailer)
            }
        } else {
            newMovieLoadResponse(data.newTitle, url, data.tvType, url) {
                this.posterUrl       = data.poster
                this.year            = data.year
                this.plot            = data.description
                this.tags            = data.tags
                this.score           = Score.from10(data.score)
                this.recommendations = recommendations
                addActors(data.actors)
                addTrailer(data.trailer)
            }
        }
    }

    // ============================================
    // DECRYPTION SYSTEM
    // ============================================

    /**
     * Hybrid decrypter that uses current known pattern first,
     * then falls back to dynamic discovery if needed.
     */

    private object HDFCHybridDecrypter {
    
        // FIXED: Apply ROT13 only to alphabetical characters in the string
        private fun applyRot13ToText(input: String): String {
            return StringBuilder().apply {
                for (char in input) {
                    when {
                        // Only apply ROT13 to A-Z and a-z
                        char in 'a'..'z' -> {
                            val rotated = char.code + 13
                            append(if (rotated <= 'z'.code) rotated.toChar() else (rotated - 26).toChar())
                        }
                        char in 'A'..'Z' -> {
                            val rotated = char.code + 13
                            append(if (rotated <= 'Z'.code) rotated.toChar() else (rotated - 26).toChar())
                        }
                        // Leave all other characters (including non-ASCII) untouched
                        else -> append(char)
                    }
                }
            }.toString()
        }
        
        // FIXED: Better URL validation that checks for clean URLs
        private fun isValidVideoUrl(url: String): Boolean {
            if (url.isEmpty()) return false
            
            // Must start with http/https
            if (!url.startsWith("http")) return false
            
            // Check for clean domain (no special characters in domain)
            val domainPattern = Regex("""https?://([a-zA-Z0-9.-]+)/""")
            val match = domainPattern.find(url)
            if (match == null) return false
            
            val domain = match.groupValues[1]
            // Domain should only contain letters, numbers, dots, and hyphens
            if (!domain.matches(Regex("""^[a-zA-Z0-9.-]+$"""))) {
                Log.d("HDFC_VALIDATE", "Invalid domain characters: $domain")
                return false
            }
            
            // Check for video file extensions
            val videoExtensions = listOf(".m3u8", ".mp4", ".mkv", ".webm")
            if (!videoExtensions.any { url.contains(it, ignoreCase = true) }) {
                return false
            }
            
            return true
        }
        
        // Updated to check for clean URLs
        fun decryptCurrentPattern(encrypted: String, seed: Int): String {
            try {
                Log.d("HDFC_FIXED", "Starting decryption")
                
                // 1. Reverse string
                val reversed = encrypted.reversed()
                Log.d("HDFC_FIXED", "After reverse: ${reversed.take(50)}...")
                
                // 2. Base64 decode
                val decodedBytes = Base64.decode(reversed, Base64.DEFAULT)
                val decodedText = String(decodedBytes, Charsets.UTF_8)
                Log.d("HDFC_FIXED", "After Base64 decode: ${decodedText.take(50)}...")
                
                // 3. Apply ROT13 to TEXT characters only
                val rot13Text = applyRot13ToText(decodedText)
                Log.d("HDFC_FIXED", "After ROT13: ${rot13Text.take(50)}...")
                
                // 4. Apply custom shift
                val result = StringBuilder()
                for (i in rot13Text.indices) {
                    val charCode = rot13Text[i].code
                    val shift = seed % (i + 5)
                    val newCharCode = (charCode - shift + 256) % 256
                    result.append(newCharCode.toChar())
                }
                
                val finalUrl = result.toString()
                Log.d("HDFC_FIXED", "After custom shift: ${finalUrl.take(100)}...")
                
                // Check if it's a valid URL
                if (isValidVideoUrl(finalUrl)) {
                    Log.d("HDFC_FIXED", "Decryption SUCCESS!")
                    return finalUrl
                } else {
                    Log.d("HDFC_FIXED", "URL validation failed")
                }
                
            } catch (e: Exception) {
                Log.e("HDFC_FIXED", "Decryption error: ${e.message}")
            }
            
            return ""
        }
        
        // Alternative approach: Process as bytes with proper ROT13
        fun decryptAsBytes(encrypted: String, seed: Int): String {
            try {
                Log.d("HDFC_BYTES", "Starting byte-based decryption")
                
                // 1. Reverse string
                val reversed = encrypted.reversed()
                
                // 2. Base64 decode to bytes
                val decodedBytes = Base64.decode(reversed, Base64.DEFAULT)
                
                // 3. Apply ROT13 to bytes (treating them as ASCII)
                val rot13Bytes = ByteArray(decodedBytes.size)
                for (i in decodedBytes.indices) {
                    val byte = decodedBytes[i].toInt() and 0xFF
                    rot13Bytes[i] = when {
                        // Only apply ROT13 to a-z (97-122) and A-Z (65-90)
                        byte in 97..122 -> { // a-z
                            val rotated = byte + 13
                            (if (rotated <= 122) rotated else rotated - 26).toByte()
                        }
                        byte in 65..90 -> { // A-Z
                            val rotated = byte + 13
                            (if (rotated <= 90) rotated else rotated - 26).toByte()
                        }
                        // Leave all other bytes unchanged
                        else -> decodedBytes[i]
                    }
                }
                
                // 4. Apply custom shift
                val result = StringBuilder()
                for (i in rot13Bytes.indices) {
                    val charCode = rot13Bytes[i].toInt() and 0xFF
                    val shift = seed % (i + 5)
                    val newCharCode = (charCode - shift + 256) % 256
                    result.append(newCharCode.toChar())
                }
                
                val finalUrl = result.toString()
                Log.d("HDFC_BYTES", "Result: ${finalUrl.take(100)}...")
                
                if (isValidVideoUrl(finalUrl)) {
                    Log.d("HDFC_BYTES", "Byte decryption SUCCESS!")
                    return finalUrl
                }
                
            } catch (e: Exception) {
                Log.e("HDFC_BYTES", "Byte decryption error: ${e.message}")
            }
            
            return ""
        }
        
        // Main entry point that tries both approaches
        fun decrypt(encrypted: String, seed: Int): String {
            Log.d("HDFC_MAIN", "Decrypting with seed: $seed")
            
            // Try text-based approach first
            val textResult = decryptCurrentPattern(encrypted, seed)
            if (textResult.isNotEmpty()) {
                Log.d("HDFC_MAIN", "Text-based approach succeeded")
                return textResult
            }
            
            Log.d("HDFC_MAIN", "Text approach failed, trying byte-based...")
            
            // Try byte-based approach
            val byteResult = decryptAsBytes(encrypted, seed)
            if (byteResult.isNotEmpty()) {
                Log.d("HDFC_MAIN", "Byte-based approach succeeded")
                return byteResult
            }
            
            Log.e("HDFC_MAIN", "Both approaches failed")
            return ""
        }
    }

    private val seenUrls = mutableSetOf<String>()

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HDFC", "Extracting source: $source")
            
            val response = app.get(url, referer = "$mainUrl/")
            val script = response.document.select("script").find { 
                it.data().contains("eval(function(p,a,c,k,e,d)") 
            }?.data() ?: return

            val unpacked = JsUnpacker(script).unpack() ?: return
            
            val callRegex = Regex("""\w+\(\[(.*?)\]\)""")
            val arrayContent = callRegex.find(unpacked)?.groupValues?.get(1) ?: return
            
            val encryptedString = arrayContent.replace("\"", "").replace("'", "").replace(",", "").replace("\\s".toRegex(), "")
            
            val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)\)""")
            val seed = seedRegex.find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: 399756995

            Log.d("HDFC", "Seed: $seed, Encrypted length: ${encryptedString.length}")
            
            val decryptedUrl = HDFCHybridDecrypter.decrypt(encryptedString, seed)
            
            if (decryptedUrl.isEmpty()) {
                Log.e("HDFC", "Decryption failed")
                return
            }

            if (seenUrls.contains(decryptedUrl)) return
            seenUrls.add(decryptedUrl)

            Log.d("HDFC", "Decrypted URL (first 100 chars): ${decryptedUrl.take(100)}...")
            
            val isHls = decryptedUrl.contains(".m3u8") || decryptedUrl.endsWith(".txt")
            
            val link = newExtractorLink(
                source = source,
                name = source,
                url = decryptedUrl
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
            
            callback.invoke(link)
        } catch (e: Exception) {
            Log.e("HDFC", "Error extracting local source", e)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val rapidrameReferer = "$mainUrl/"
        
        // Use a queue to collect and sort links
        val linkQueue = mutableListOf<Pair<Int, ExtractorLink>>()
        
        // Priority values: 1=Rapidrame, 2=Close, 3=DownloadSD, 4=DownloadHD
        
        // 1. Add Rapidrame links (priority 1)
        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val sourceNameRaw = button.text().replace("(HDrip Xbet)", "").trim()

                if (sourceNameRaw.equals("close", ignoreCase = true)) {
                    return@forEach
                }
                
                val videoID = button.attr("data-video")
                
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf("Content-Type" to "application/json", "X-Requested-With" to "fetch"),
                    referer = data
                ).text

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "") ?: ""
                
                if (iframe.contains("?rapidrame_id=")) {
                    iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                }

                if (iframe.isNotEmpty()) {
                    val finalSourceName = if (sourceNameRaw.contains("rapidrame", ignoreCase = true)) {
                        "Rapidrame $langCode"
                    } else {
                        "$sourceNameRaw $langCode"
                    }

                    val rapidrameLink = newExtractorLink(
                        source = name,
                        name = finalSourceName,
                        url = iframe
                    ) {
                        this.referer = rapidrameReferer
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO
                    }
                    linkQueue.add(Pair(1, rapidrameLink))
                }
            }
        }

        // 2. Add Close link (priority 2)
        val defaultSourceUrl = fixUrlNull(document.selectFirst(".close")?.attr("data-src"))

        if (defaultSourceUrl != null) {
            val sourceName = "Close"
            var referer = "$mainUrl/" 
            
            if (defaultSourceUrl.contains("hdfilmcehennemi.mobi")) {
                try {
                    val iframedoc = app.get(defaultSourceUrl, referer = mainUrl).document
                    val baseUri = iframedoc.location().substringBefore("/", "https://www.hdfilmcehennemi.mobi")
                    referer = baseUri
                    
                    iframedoc.select("track[kind=captions]").forEach { track ->
                        val lang = when (track.attr("srclang")) {
                            "tr" -> "Türkçe"
                            "en" -> "İngilizce"
                            else -> track.attr("srclang")
                        }
                        val subUrl = track.attr("src").let { if (it.startsWith("http")) it else "$baseUri/$it".replace("//", "/") }
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                } catch (e: Exception) { 
                    Log.e("HDFC", "Sub extraction error for default source", e) 
                }
            }

            val closeLink = newExtractorLink(
                source = name,
                name = sourceName,
                url = defaultSourceUrl
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.VIDEO
            }
            linkQueue.add(Pair(2, closeLink))
        }

        // 3. PROCESS DOWNLOAD LINKS DIRECTLY HERE (priority 3 & 4)
        // =========================================================
        var rapidrameD = document.selectFirst("iframe.rapidrame, iframe.close")
            ?.attr("data-src")
            ?.let { src ->
                when {
                    "/rplayer/" in src -> src.substringAfter("/rplayer/").removeSuffix("/")
                    "?rapidrame_id=" in src -> src.substringAfter("?rapidrame_id=")
                    else -> null
                }
            }
            ?.takeIf { it.isNotEmpty() }

        rapidrameD?.let { id ->
            Log.d("HDFC", "Processing download links for ID: $id")
            
            // Process SD (priority 3) and HD (priority 4) separately
            val downloadUrl = "https://cehennempass.pw/download/$id"
            
            // Process SD link first (priority 3)
            try {
                val postBodySD = okhttp3.FormBody.Builder()
                    .add("video_id", id)
                    .add("selected_quality", "low")
                    .build()
                
                val responseSD = app.post(
                    "https://cehennempass.pw/process_quality_selection.php",
                    requestBody = postBodySD,
                    headers = standardHeaders,
                    referer = downloadUrl
                ).parsedSafe<DownloadResponse>()

                val finalLinkSD = responseSD?.download_link

                if (!finalLinkSD.isNullOrEmpty()) {
                    val sdLink = newExtractorLink(
                        source = name,
                        name = "⬇️ SD",
                        url = finalLinkSD
                    ) {
                        this.quality = Qualities.P480.value
                        this.type = ExtractorLinkType.VIDEO
                    }
                    linkQueue.add(Pair(3, sdLink))
                    Log.d("HDFC", "Added SD download link")
                }
            } catch (e: Exception) {
                Log.e("HDFC", "SD download extraction failed", e)
            }

            // Process HD link second (priority 4)
            try {
                val postBodyHD = okhttp3.FormBody.Builder()
                    .add("video_id", id)
                    .add("selected_quality", "high")
                    .build()
                
                val responseHD = app.post(
                    "https://cehennempass.pw/process_quality_selection.php",
                    requestBody = postBodyHD,
                    headers = standardHeaders,
                    referer = downloadUrl
                ).parsedSafe<DownloadResponse>()

                val finalLinkHD = responseHD?.download_link

                if (!finalLinkHD.isNullOrEmpty()) {
                    val hdLink = newExtractorLink(
                        source = name,
                        name = "⬇️ HD",
                        url = finalLinkHD
                    ) {
                        this.quality = Qualities.P720.value
                        this.type = ExtractorLinkType.VIDEO
                    }
                    linkQueue.add(Pair(4, hdLink))
                    Log.d("HDFC", "Added HD download link")
                }
            } catch (e: Exception) {
                Log.e("HDFC", "HD download extraction failed", e)
            }
        }
        // =========================================================

        // Sort by priority and send to callback
        linkQueue.sortedBy { it.first }.forEach { (_, link) ->
            callback.invoke(link)
        }

        return true
    }
    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String)
    data class DownloadResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("download_link") val download_link: String? = null,
        @JsonProperty("message") val message: String? = null
    )
}
