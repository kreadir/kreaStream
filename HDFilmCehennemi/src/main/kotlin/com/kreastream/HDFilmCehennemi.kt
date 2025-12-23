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
        
        // Current known working pattern
        private fun decryptCurrentPattern(encrypted: String, seed: Int): String {
            try {
                // 1. Reverse string
                val reversed = encrypted.reversed()
                
                // 2. Base64 decode
                val decodedBytes = Base64.decode(reversed, Base64.DEFAULT)
                val decodedText = String(decodedBytes, Charsets.UTF_8)
                
                // 3. Apply ROT13 to characters only
                val rot13Text = decodedText.map { char ->
                    when {
                        char in 'a'..'z' -> {
                            val rotated = char.code + 13
                            if (rotated <= 'z'.code) rotated.toChar() else (rotated - 26).toChar()
                        }
                        char in 'A'..'Z' -> {
                            val rotated = char.code + 13
                            if (rotated <= 'Z'.code) rotated.toChar() else (rotated - 26).toChar()
                        }
                        else -> char
                    }
                }.joinToString("")
                
                // 4. Apply custom shift
                val result = StringBuilder()
                for (i in rot13Text.indices) {
                    val charCode = rot13Text[i].code
                    val shift = seed % (i + 5)
                    val newCharCode = (charCode - shift + 256) % 256
                    result.append(newCharCode.toChar())
                }
                
                return result.toString()
            } catch (e: Exception) {
                return ""
            }
        }
        
        // Dynamic fallback for when pattern changes
        private fun dynamicDecrypt(encrypted: String, seed: Int): String {
            if (encrypted.isEmpty()) return ""
            
            val operations = listOf(
                Operation.Reverse,
                Operation.Base64Decode,
                Operation.DoubleBase64Decode,
                Operation.Rot13Chars,
                Operation.Rot13Bytes
            )
            
            // Try common sequences first
            val commonSequences = listOf(
                // Current pattern
                listOf(Operation.Reverse, Operation.Base64Decode, Operation.Rot13Chars, Operation.CustomShift(seed)),
                // Previous pattern
                listOf(Operation.Base64Decode, Operation.Rot13Bytes, Operation.Reverse, Operation.CustomShift(seed)),
                // Double base64 pattern
                listOf(Operation.Reverse, Operation.DoubleBase64Decode, Operation.CustomShift(seed)),
                // Simple patterns
                listOf(Operation.Reverse, Operation.Base64Decode, Operation.CustomShift(seed)),
                listOf(Operation.Base64Decode, Operation.CustomShift(seed)),
            )
            
            for (sequence in commonSequences) {
                val result = trySequence(encrypted, seed, sequence)
                if (isValidVideoUrl(result)) {
                    Log.d("HDFC_DYNAMIC", "Found with common sequence")
                    return result
                }
            }
            
            // Try all 2-operation combinations
            for (op1 in operations) {
                for (op2 in operations) {
                    if (op1 == op2) continue
                    val result = trySequence(encrypted, seed, listOf(op1, op2, Operation.CustomShift(seed)))
                    if (isValidVideoUrl(result)) {
                        Log.d("HDFC_DYNAMIC", "Found with 2-op sequence: $op1 -> $op2")
                        return result
                    }
                }
            }
            
            return ""
        }
        
        private sealed class Operation {
            object Reverse : Operation()
            object Base64Decode : Operation()
            object DoubleBase64Decode : Operation()
            object Rot13Chars : Operation()  // ROT13 on characters
            object Rot13Bytes : Operation()  // ROT13 on bytes
            data class CustomShift(val seed: Int) : Operation()
        }
        
        private fun trySequence(encrypted: String, seed: Int, operations: List<Operation>): String {
            try {
                var current: Any = encrypted
                
                for (operation in operations) {
                    current = when (operation) {
                        Operation.Reverse -> {
                            when (current) {
                                is String -> (current as String).reversed()
                                is ByteArray -> (current as ByteArray).reversedArray()
                                else -> return ""
                            }
                        }
                        Operation.Base64Decode -> {
                            val str = when (current) {
                                is String -> current as String
                                is ByteArray -> String(current as ByteArray, Charsets.UTF_8)
                                else -> return ""
                            }
                            Base64.decode(str, Base64.DEFAULT)
                        }
                        Operation.DoubleBase64Decode -> {
                            val str = when (current) {
                                is String -> current as String
                                is ByteArray -> String(current as ByteArray, Charsets.UTF_8)
                                else -> return ""
                            }
                            val once = Base64.decode(str, Base64.DEFAULT)
                            Base64.decode(once, Base64.DEFAULT)
                        }
                        Operation.Rot13Chars -> {
                            val text = when (current) {
                                is String -> current as String
                                is ByteArray -> String(current as ByteArray, Charsets.UTF_8)
                                else -> return ""
                            }
                            text.map { char ->
                                when {
                                    char in 'a'..'z' -> {
                                        val rotated = char.code + 13
                                        if (rotated <= 'z'.code) rotated.toChar() else (rotated - 26).toChar()
                                    }
                                    char in 'A'..'Z' -> {
                                        val rotated = char.code + 13
                                        if (rotated <= 'Z'.code) rotated.toChar() else (rotated - 26).toChar()
                                    }
                                    else -> char
                                }
                            }.joinToString("")
                        }
                        Operation.Rot13Bytes -> {
                            val bytes = when (current) {
                                is String -> (current as String).toByteArray(Charsets.UTF_8)
                                is ByteArray -> current as ByteArray
                                else -> return ""
                            }
                            bytes.map { byte ->
                                val char = byte.toInt().toChar()
                                when {
                                    char in 'a'..'z' -> {
                                        val rotated = char.code + 13
                                        (if (rotated <= 'z'.code) rotated else rotated - 26).toByte()
                                    }
                                    char in 'A'..'Z' -> {
                                        val rotated = char.code + 13
                                        (if (rotated <= 'Z'.code) rotated else rotated - 26).toByte()
                                    }
                                    else -> byte
                                }
                            }.toByteArray()
                        }
                        is Operation.CustomShift -> {
                            val bytes = when (current) {
                                is String -> (current as String).toByteArray(Charsets.UTF_8)
                                is ByteArray -> current as ByteArray
                                else -> return ""
                            }
                            val sb = StringBuilder()
                            for (i in bytes.indices) {
                                val charCode = bytes[i].toInt() and 0xFF
                                val shift = operation.seed % (i + 5)
                                val newCharCode = (charCode - shift + 256) % 256
                                sb.append(newCharCode.toChar())
                            }
                            sb.toString()
                        }
                    }
                    
                    if (current.toString().isEmpty()) return ""
                }
                
                return when (current) {
                    is String -> current
                    is ByteArray -> String(current, Charsets.UTF_8)
                    else -> ""
                }
            } catch (e: Exception) {
                return ""
            }
        }
        
        private fun isValidVideoUrl(url: String): Boolean {
            if (url.isEmpty()) return false
            if (!url.startsWith("http")) return false
            
            // Check for video indicators
            val videoIndicators = listOf(
                ".m3u8", ".mp4", ".mkv", ".webm",
                "//rapid", "//video", "//stream",
                "m3u8?", "index.m3u8"
            )
            
            return videoIndicators.any { url.contains(it, ignoreCase = true) }
        }
        
        /**
         * Main entry point: tries current pattern first, falls back to dynamic
         */
        fun decrypt(encrypted: String, seed: Int): String {
            Log.d("HDFC_DECRYPT", "Starting decryption, seed: $seed, length: ${encrypted.length}")
            
            // 1. Try current known pattern (fast path)
            val currentResult = decryptCurrentPattern(encrypted, seed)
            if (isValidVideoUrl(currentResult)) {
                Log.d("HDFC_DECRYPT", "Success with current pattern")
                return currentResult
            }
            
            Log.d("HDFC_DECRYPT", "Current pattern failed, trying dynamic...")
            
            // 2. Fall back to dynamic discovery
            val dynamicResult = dynamicDecrypt(encrypted, seed)
            if (isValidVideoUrl(dynamicResult)) {
                Log.d("HDFC_DECRYPT", "Success with dynamic decryption")
                return dynamicResult
            }
            
            Log.e("HDFC_DECRYPT", "All decryption attempts failed")
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

    private suspend fun extractDownloadLinks(rapidrameId: String, callback: (ExtractorLink) -> Unit) {
        val downloadUrl = "https://cehennempass.pw/download/$rapidrameId"
        
        val qualities = mapOf(
            "low" to "Download SD", 
            "high" to "Download HD"   
        )

        qualities.forEach { (qualityData, qualityName) ->
            val postUrl = "https://cehennempass.pw/process_quality_selection.php" 
            
            val postBody = okhttp3.FormBody.Builder()
                .add("video_id", rapidrameId)
                .add("selected_quality", qualityData)
                .build()
            
            try {
                val response = app.post(
                    postUrl,
                    requestBody = postBody,
                    headers = standardHeaders,
                    referer = downloadUrl 
                ).parsedSafe<DownloadResponse>()

                val finalLink = response?.download_link

                if (!finalLink.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name, 
                            name = qualityName,
                            url = finalLink
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("HDFC", "Download extraction failed", e)
            }
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
        var rapidrameId: String? = null

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

                    rapidrameId = iframe.substringAfter("?rapidrame_id=").takeIf { it.isNotEmpty() }
                    
                    invokeLocalSource(finalSourceName, iframe, rapidrameReferer, callback) 
                }
            }
        }

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

            rapidrameId = defaultSourceUrl.substringAfter("?rapidrame_id=").takeIf { it.isNotEmpty() }

            invokeLocalSource(sourceName, defaultSourceUrl, referer, callback) 
        }

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

        rapidrameD?.let { extractDownloadLinks(it, callback) }

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
