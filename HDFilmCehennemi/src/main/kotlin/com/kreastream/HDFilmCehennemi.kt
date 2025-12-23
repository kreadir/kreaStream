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
            // FIX: Apply image path correction globally for standard posters
            ?.replace("/list/", "/")
            ?.replace("/thumb/", "/")

        val year = this.selectFirst(".poster-meta span")?.text()?.trim()?.toIntOrNull()
        val score = this.selectFirst(".poster-meta .imdb")?.ownText()?.trim()?.toFloatOrNull()
        
        // Use .poster-lang or .poster-meta for language info
        val lang = this.selectFirst(".poster-lang span, .poster-meta-genre span")?.text()?.trim()
        
        // Dubbed status: checks for "Dublaj" or "Yerli"
        val hasDub = lang?.contains("Dublaj", ignoreCase = true) == true || lang?.contains("Yerli", ignoreCase = true) == true
        
        // Subtitle status: checks for "Altyazılı"
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
            // Select all relevant link elements
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

            // Format title to show episode info for easier identification
            val newName = "$seriesTitle - $episodeInfo"
            
            return newTvSeriesSearchResponse(newName, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        
        // Handle standard posters for "Yeni Eklenen Diziler" and movies
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

    private object HDFCDynamicDecrypter {
        
        // Define all possible operations
        sealed class Operation {
            object Reverse : Operation()
            object Base64Decode : Operation()
            object ROT13 : Operation()
            data class CustomShift(val seed: Int) : Operation()
            object DoubleBase64Decode : Operation()
            object NoOp : Operation()
        }
        
        private fun applyRot13(inputBytes: ByteArray): ByteArray {
            val rotated = ByteArray(inputBytes.size)
            for (i in inputBytes.indices) {
                val charCode = inputBytes[i].toInt()
                val char = charCode.toChar()
                rotated[i] = when (char) {
                    in 'a'..'z' -> (((charCode - 'a'.code + 13) % 26 + 'a'.code).toChar()).code.toByte()
                    in 'A'..'Z' -> (((charCode - 'A'.code + 13) % 26 + 'A'.code).toChar()).code.toByte()
                    else -> inputBytes[i]
                }
            }
            return rotated
        }
        
        private fun applyCustomShift(inputBytes: ByteArray, seed: Int): String {
            val sb = StringBuilder()
            for (i in inputBytes.indices) {
                val charCode = inputBytes[i].toInt() and 0xFF
                val shift = seed % (i + 5)
                val newChar = (charCode - shift + 256) % 256
                sb.append(newChar.toChar())
            }
            return sb.toString()
        }
        
        private fun isValidResult(output: String): Boolean {
            if (output.isBlank()) return false
            if (output.length < 5) return false
            
            var score = 0
            
            if (output.startsWith("http://") || output.startsWith("https://")) score += 100
            if (output.contains(".m3u8") || output.contains(".mp4") || output.contains(".mkv")) score += 50
            if (output.contains("rapidrame") || output.contains("cehennem")) score += 30
            if (output.contains("http") || output.contains("://")) score += 20
            if (output.contains("embed") || output.contains("player")) score += 15
            
            val printable = output.count { it.code in 32..126 || it.code in 9..13 }
            val printableRatio = printable.toDouble() / output.length
            if (printableRatio > 0.8) score += 10
            
            if (output.contains("{") && output.contains("}")) score += 5
            if (output.contains("[") && output.contains("]")) score += 5
            
            return score >= 15
        }
        
        // Try a specific sequence of operations - returns String result
        private fun trySequence(encrypted: String, seed: Int, operations: List<Operation>): String {
            var currentString: String? = encrypted
            var currentBytes: ByteArray? = null
            
            for (operation in operations) {
                try {
                    when {
                        currentString != null -> {
                            currentBytes = null // Clear bytes when working with string
                            currentString = when (operation) {
                                Operation.Reverse -> currentString!!.reversed()
                                Operation.Base64Decode -> {
                                    val decoded = Base64.decode(currentString!!, Base64.DEFAULT)
                                    currentBytes = decoded // Store for potential next operation
                                    String(decoded, Charsets.UTF_8)
                                }
                                Operation.DoubleBase64Decode -> {
                                    val once = Base64.decode(currentString!!, Base64.DEFAULT)
                                    val twice = Base64.decode(once, Base64.DEFAULT)
                                    String(twice, Charsets.UTF_8)
                                }
                                Operation.ROT13 -> {
                                    val bytes = currentString!!.toByteArray(Charsets.UTF_8)
                                    val rotated = applyRot13(bytes)
                                    currentBytes = rotated // Store for potential next operation
                                    String(rotated, Charsets.UTF_8)
                                }
                                is Operation.CustomShift -> {
                                    val bytes = currentString!!.toByteArray(Charsets.UTF_8)
                                    applyCustomShift(bytes, operation.seed)
                                }
                                Operation.NoOp -> currentString
                            }
                        }
                        currentBytes != null -> {
                            currentString = when (operation) {
                                Operation.Reverse -> {
                                    val reversed = currentBytes!!.reversedArray()
                                    currentBytes = reversed
                                    String(reversed, Charsets.UTF_8)
                                }
                                Operation.ROT13 -> {
                                    val rotated = applyRot13(currentBytes!!)
                                    currentBytes = rotated
                                    String(rotated, Charsets.UTF_8)
                                }
                                is Operation.CustomShift -> {
                                    applyCustomShift(currentBytes!!, operation.seed)
                                }
                                Operation.NoOp -> String(currentBytes!!, Charsets.UTF_8)
                                else -> {
                                    // Convert to string for other operations
                                    currentString = String(currentBytes!!, Charsets.UTF_8)
                                    currentBytes = null
                                    continue // Re-process this operation with string
                                }
                            }
                        }
                    }
                    
                    if (currentString?.isEmpty() == true) {
                        return ""
                    }
                } catch (e: Exception) {
                    return ""
                }
            }
            
            return currentString ?: (currentBytes?.let { String(it, Charsets.UTF_8) } ?: "")
        }
        
        private fun generateOperationSequences(seed: Int): List<List<Operation>> {
            val commonSequences = listOf(
                listOf(Operation.Reverse, Operation.DoubleBase64Decode, Operation.CustomShift(seed)),
                listOf(Operation.Reverse, Operation.Base64Decode, Operation.ROT13, Operation.CustomShift(seed)),
                listOf(Operation.Base64Decode, Operation.ROT13, Operation.Reverse, Operation.CustomShift(seed)),
                listOf(Operation.Reverse, Operation.Base64Decode, Operation.CustomShift(seed)),
                listOf(Operation.Base64Decode, Operation.CustomShift(seed), Operation.Reverse),
            )
            
            return commonSequences
        }
        
        // Main dynamic decryption function
        fun dynamicDecrypt(encrypted: String, seed: Int): String {
            if (encrypted.isEmpty() || encrypted.length < 10) return ""
            
            // Try common sequences first
            val commonSequences = listOf(
                listOf(Operation.Reverse, Operation.DoubleBase64Decode, Operation.CustomShift(seed)),
                listOf(Operation.Base64Decode, Operation.ROT13, Operation.Reverse, Operation.CustomShift(seed)),
                listOf(Operation.Reverse, Operation.Base64Decode, Operation.ROT13, Operation.CustomShift(seed)),
            )
            
            for (sequence in commonSequences) {
                val result = trySequence(encrypted, seed, sequence)
                if (isValidResult(result)) {
                    Log.d("HDFC_DYNAMIC", "Found with common sequence")
                    return result
                }
            }
            
            // Try all generated sequences
            val allSequences = generateOperationSequences(seed)
            for (sequence in allSequences) {
                val result = trySequence(encrypted, seed, sequence)
                if (isValidResult(result)) {
                    Log.d("HDFC_DYNAMIC", "Found with generated sequence")
                    return result
                }
            }
            
            // Simple brute force approach
            return simpleBruteForce(encrypted, seed)
        }
        
        // Simplified brute force without complex recursion
        private fun simpleBruteForce(encrypted: String, seed: Int): String {
            val operations = listOf(
                Operation.Reverse,
                Operation.Base64Decode,
                Operation.DoubleBase64Decode,
                Operation.ROT13
            )
            
            // Try simple 2-operation sequences
            for (op1 in operations) {
                for (op2 in operations) {
                    if (op1 == op2) continue
                    val result = trySequence(encrypted, seed, listOf(op1, op2, Operation.CustomShift(seed)))
                    if (isValidResult(result)) {
                        return result
                    }
                }
            }
            
            // Try 3-operation sequences
            for (op1 in operations) {
                for (op2 in operations) {
                    if (op1 == op2) continue
                    for (op3 in operations) {
                        if (op3 == op1 || op3 == op2) continue
                        val result = trySequence(encrypted, seed, listOf(op1, op2, op3, Operation.CustomShift(seed)))
                        if (isValidResult(result)) {
                            return result
                        }
                    }
                }
            }
            
            return ""
        }
        
        // Pattern detection based on encrypted string characteristics
        fun patternBasedDecrypt(encrypted: String, seed: Int): String {
            val isBase64Like = encrypted.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
            val containsEqualSign = encrypted.contains('=')
            val length = encrypted.length
            
            // Pattern 1: Ends with == and contains only base64 chars -> likely double base64
            if (encrypted.endsWith("==") && isBase64Like && length % 4 == 0) {
                val result = trySequence(encrypted, seed, 
                    listOf(Operation.Reverse, Operation.DoubleBase64Decode, Operation.CustomShift(seed)))
                if (isValidResult(result)) return result
            }
            
            // Pattern 2: No equal signs, contains mixed case -> likely already processed
            if (!containsEqualSign && encrypted.any { it.isLetter() }) {
                val result = trySequence(encrypted, seed,
                    listOf(Operation.Reverse, Operation.Base64Decode, Operation.ROT13, Operation.CustomShift(seed)))
                if (isValidResult(result)) return result
            }
            
            // Pattern 3: Very long string -> might need ROT13 first
            if (length > 100) {
                val result = trySequence(encrypted, seed,
                    listOf(Operation.ROT13, Operation.Reverse, Operation.Base64Decode, Operation.CustomShift(seed)))
                if (isValidResult(result)) return result
            }
            
            // Default to dynamic approach
            return dynamicDecrypt(encrypted, seed)
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
            val response = app.get(url, referer = "$mainUrl/")
            val script = response.document.select("script").find { 
                it.data().contains("eval(function(p,a,c,k,e,d)") 
            }?.data() ?: return

            // 1. Unpack the javascript
            val unpacked = JsUnpacker(script).unpack() ?: return
            
            val callRegex = Regex("""\w+\(\[(.*?)\]\)""")
            val arrayContent = callRegex.find(unpacked)?.groupValues?.get(1) ?: return
            
            // Clean it up to get the single Base64 string
            val encryptedString = arrayContent.replace("\"", "").replace("'", "").replace(",", "").replace("\\s".toRegex(), "")

            // 3. Extract the math seed
            val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)\)""")
            val seed = seedRegex.find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: 399756995

            // 4. Decrypt using pattern-based approach first, then dynamic
            var decryptedUrl = HDFCDynamicDecrypter.patternBasedDecrypt(encryptedString, seed)
            if (decryptedUrl.isEmpty()) {
                decryptedUrl = HDFCDynamicDecrypter.dynamicDecrypt(encryptedString, seed)
            }
            
            if (decryptedUrl.isEmpty()) {
                Log.e("HDFC_DYNAMIC", "All decryption attempts failed for: ${encryptedString.take(50)}...")
                return
            }

            if (seenUrls.contains(decryptedUrl)) return
            seenUrls.add(decryptedUrl)

            // ... rest of your code
        } catch (e: Exception) {
            Log.e("HDFC", "Error extracting local source", e)
        }
    }

    private suspend fun extractDownloadLinks(rapidrameId: String, callback: (ExtractorLink) -> Unit) {
        val downloadUrl = "https://cehennempass.pw/download/$rapidrameId" // Updated domain based on snippet
        
        val qualities = mapOf(
            "low" to "Download SD", 
            "high" to "Download HD"   
        )

        qualities.forEach { (qualityData, qualityName) ->
            // The process URL might also need updating or staying as cehennempass.pw
            // Trying the new domain for processing as well based on common patterns
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
                // Fallback to old domain if new one fails
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

                // Skip 'Close' here as we handle it separately (or if it appears in list)
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