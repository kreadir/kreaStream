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
        
        sealed class Operation {
            object Reverse : Operation()
            object Base64Decode : Operation()
            object ROT13 : Operation()
            data class CustomShift(val seed: Int) : Operation()
            object DoubleBase64Decode : Operation()
        }
        
        private fun applyRot13(input: String): String {
            return input.map { char ->
                when (char) {
                    in 'a'..'z' -> {
                        val base = 'a'.code
                        (((char.code - base + 13) % 26) + base).toChar()
                    }
                    in 'A'..'Z' -> {
                        val base = 'A'.code
                        (((char.code - base + 13) % 26) + base).toChar()
                    }
                    else -> char
                }
            }.joinToString("")
        }
        
        private fun applyCustomShift(input: String, seed: Int): String {
            val sb = StringBuilder()
            for (i in input.indices) {
                val charCode = input[i].code
                val shift = seed % (i + 5)
                val newChar = (charCode - shift + 256) % 256
                sb.append(newChar.toChar())
            }
            return sb.toString()
        }
        
        private fun isVideoUrl(result: String): Boolean {
            if (result.isEmpty()) return false
            
            // Check for video URL patterns
            val patterns = listOf(
                ".m3u8", ".mp4", ".mkv", ".webm",
                "http://", "https://",
                "rapidrame", "cehennem",
                "embed", "player"
            )
            
            return patterns.any { result.contains(it, ignoreCase = true) }
        }
        
        // Try the most common sequence first (based on previous working code)
        private fun attemptSequence1(encrypted: String, seed: Int): String {
            Log.d("HDFC_DYNAMIC", "Trying sequence 1: Reverse -> Double Base64 -> CustomShift")
            try {
                // 1. Reverse
                val reversed = encrypted.reversed()
                Log.d("HDFC_DYNAMIC", "After reverse: ${reversed.take(50)}...")
                
                // 2. Double Base64 decode
                val once = Base64.decode(reversed, Base64.DEFAULT)
                val onceStr = String(once, Charsets.UTF_8)
                Log.d("HDFC_DYNAMIC", "After first decode: ${onceStr.take(50)}...")
                
                val twice = Base64.decode(onceStr, Base64.DEFAULT)
                val twiceStr = String(twice, Charsets.UTF_8)
                Log.d("HDFC_DYNAMIC", "After second decode: ${twiceStr.take(50)}...")
                
                // 3. Custom shift
                val result = applyCustomShift(twiceStr, seed)
                Log.d("HDFC_DYNAMIC", "After custom shift: ${result.take(50)}...")
                
                if (isVideoUrl(result)) {
                    Log.d("HDFC_DYNAMIC", "Sequence 1 SUCCESS!")
                    return result
                }
            } catch (e: Exception) {
                Log.e("HDFC_DYNAMIC", "Sequence 1 failed: ${e.message}")
            }
            return ""
        }
        
        // Second sequence: Old working pattern
        private fun attemptSequence2(encrypted: String, seed: Int): String {
            Log.d("HDFC_DYNAMIC", "Trying sequence 2: Base64 -> ROT13 -> Reverse -> CustomShift")
            try {
                // 1. Base64 decode
                val decoded = Base64.decode(encrypted, Base64.DEFAULT)
                val decodedStr = String(decoded, Charsets.UTF_8)
                Log.d("HDFC_DYNAMIC", "After Base64 decode: ${decodedStr.take(50)}...")
                
                // 2. ROT13
                val rot13 = applyRot13(decodedStr)
                Log.d("HDFC_DYNAMIC", "After ROT13: ${rot13.take(50)}...")
                
                // 3. Reverse
                val reversed = rot13.reversed()
                Log.d("HDFC_DYNAMIC", "After reverse: ${reversed.take(50)}...")
                
                // 4. Custom shift
                val result = applyCustomShift(reversed, seed)
                Log.d("HDFC_DYNAMIC", "After custom shift: ${result.take(50)}...")
                
                if (isVideoUrl(result)) {
                    Log.d("HDFC_DYNAMIC", "Sequence 2 SUCCESS!")
                    return result
                }
            } catch (e: Exception) {
                Log.e("HDFC_DYNAMIC", "Sequence 2 failed: ${e.message}")
            }
            return ""
        }
        
        // Third sequence: Reverse -> Base64 -> ROT13 -> CustomShift
        private fun attemptSequence3(encrypted: String, seed: Int): String {
            Log.d("HDFC_DYNAMIC", "Trying sequence 3: Reverse -> Base64 -> ROT13 -> CustomShift")
            try {
                // 1. Reverse
                val reversed = encrypted.reversed()
                Log.d("HDFC_DYNAMIC", "After reverse: ${reversed.take(50)}...")
                
                // 2. Base64 decode
                val decoded = Base64.decode(reversed, Base64.DEFAULT)
                val decodedStr = String(decoded, Charsets.UTF_8)
                Log.d("HDFC_DYNAMIC", "After Base64 decode: ${decodedStr.take(50)}...")
                
                // 3. ROT13
                val rot13 = applyRot13(decodedStr)
                Log.d("HDFC_DYNAMIC", "After ROT13: ${rot13.take(50)}...")
                
                // 4. Custom shift
                val result = applyCustomShift(rot13, seed)
                Log.d("HDFC_DYNAMIC", "After custom shift: ${result.take(50)}...")
                
                if (isVideoUrl(result)) {
                    Log.d("HDFC_DYNAMIC", "Sequence 3 SUCCESS!")
                    return result
                }
            } catch (e: Exception) {
                Log.e("HDFC_DYNAMIC", "Sequence 3 failed: ${e.message}")
            }
            return ""
        }
        
        // Simple pattern detection
        private fun getBestSequenceToTry(encrypted: String): Int {
            // Check if it looks like base64
            val isBase64 = encrypted.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
            val endsWithEquals = encrypted.endsWith("==")
            
            return when {
                endsWithEquals && isBase64 -> 1 // Double base64 likely
                isBase64 -> 2 // Regular base64
                else -> 3 // Try reverse first
            }
        }
        
        fun dynamicDecrypt(encrypted: String, seed: Int): String {
            Log.d("HDFC_DYNAMIC", "Starting decryption")
            Log.d("HDFC_DYNAMIC", "Encrypted (first 100 chars): ${encrypted.take(100)}...")
            Log.d("HDFC_DYNAMIC", "Seed: $seed")
            Log.d("HDFC_DYNAMIC", "Length: ${encrypted.length}")
            
            if (encrypted.isEmpty()) {
                Log.d("HDFC_DYNAMIC", "Empty encrypted string")
                return ""
            }
            
            // Try pattern-based approach first
            val recommendedSequence = getBestSequenceToTry(encrypted)
            Log.d("HDFC_DYNAMIC", "Recommended sequence: $recommendedSequence")
            
            val sequences = listOf(
                recommendedSequence to { attemptSequence1(encrypted, seed) },
                1 to { attemptSequence1(encrypted, seed) },
                2 to { attemptSequence2(encrypted, seed) },
                3 to { attemptSequence3(encrypted, seed) }
            )
            
            // Try recommended first, then all others
            for ((seqNum, attemptFunc) in sequences) {
                if (seqNum == recommendedSequence || seqNum == 1) {
                    val result = attemptFunc()
                    if (result.isNotEmpty()) {
                        Log.d("HDFC_DYNAMIC", "Decryption successful with sequence $seqNum")
                        return result
                    }
                }
            }
            
            // If nothing worked, try brute force combinations
            Log.d("HDFC_DYNAMIC", "Trying brute force combinations...")
            return bruteForceDecrypt(encrypted, seed)
        }
        
        private fun bruteForceDecrypt(encrypted: String, seed: Int): String {
            val operations = listOf("reverse", "base64", "rot13")
            
            // Try all 2-operation combinations
            for (op1 in operations) {
                for (op2 in operations) {
                    if (op1 == op2) continue
                    
                    try {
                        var current = encrypted
                        
                        // Apply first operation
                        current = when (op1) {
                            "reverse" -> current.reversed()
                            "base64" -> String(Base64.decode(current, Base64.DEFAULT), Charsets.UTF_8)
                            "rot13" -> applyRot13(current)
                            else -> current
                        }
                        
                        // Apply second operation
                        current = when (op2) {
                            "reverse" -> current.reversed()
                            "base64" -> String(Base64.decode(current, Base64.DEFAULT), Charsets.UTF_8)
                            "rot13" -> applyRot13(current)
                            else -> current
                        }
                        
                        // Always end with custom shift
                        val result = applyCustomShift(current, seed)
                        
                        if (isVideoUrl(result)) {
                            Log.d("HDFC_DYNAMIC", "Brute force success with: $op1 -> $op2 -> customShift")
                            return result
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            
            Log.d("HDFC_DYNAMIC", "All decryption attempts failed")
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
            Log.d("HDFC_DEBUG", "invokeLocalSource called for: $source")
            Log.d("HDFC_DEBUG", "Fetching URL: $url")
            
            val response = app.get(url, referer = "$mainUrl/")
            Log.d("HDFC_DEBUG", "Response status: ${response.code}")
            
            val script = response.document.select("script").find { 
                it.data().contains("eval(function(p,a,c,k,e,d)") 
            }?.data()
            
            if (script == null) {
                Log.e("HDFC_DEBUG", "No packed script found in response")
                return
            }
            
            Log.d("HDFC_DEBUG", "Found packed script, length: ${script.length}")
            
            // 1. Unpack the javascript
            val unpacked = JsUnpacker(script).unpack()
            if (unpacked == null) {
                Log.e("HDFC_DEBUG", "Failed to unpack JavaScript")
                return
            }
            
            Log.d("HDFC_DEBUG", "Unpacked script (first 500 chars): ${unpacked.take(500)}...")
            
            val callRegex = Regex("""\w+\(\[(.*?)\]\)""")
            val match = callRegex.find(unpacked)
            
            if (match == null) {
                Log.e("HDFC_DEBUG", "No array call found in unpacked script")
                return
            }
            
            val arrayContent = match.groupValues[1]
            Log.d("HDFC_DEBUG", "Array content found, length: ${arrayContent.length}")
            
            // Clean it up to get the single Base64 string
            val encryptedString = arrayContent.replace("\"", "").replace("'", "").replace(",", "").replace("\\s".toRegex(), "")
            Log.d("HDFC_DEBUG", "Cleaned encrypted string (first 100 chars): ${encryptedString.take(100)}...")
            Log.d("HDFC_DEBUG", "Encrypted string length: ${encryptedString.length}")
            
            // 3. Extract the math seed
            val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)\)""")
            val seedMatch = seedRegex.find(unpacked)
            val seed = seedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 399756995
            
            Log.d("HDFC_DEBUG", "Extracted seed: $seed")
            
            // 4. Decrypt
            val decryptedUrl = HDFCDynamicDecrypter.dynamicDecrypt(encryptedString, seed)
            
            if (decryptedUrl.isEmpty()) {
                Log.e("HDFC_DEBUG", "Decryption returned empty string")
                // Try the old decrypter as fallback
                val oldResult = tryOldDecrypter(encryptedString, seed)
                if (oldResult.isNotEmpty()) {
                    Log.d("HDFC_DEBUG", "Old decrypter worked: ${oldResult.take(100)}...")
                    // Call the suspend function
                    processDecryptedUrl(oldResult, source, referer, callback)
                    return
                }
                return
            }
            
            Log.d("HDFC_DEBUG", "Decryption successful: ${decryptedUrl.take(100)}...")
            // Call the suspend function
            processDecryptedUrl(decryptedUrl, source, referer, callback)
            
        } catch (e: Exception) {
            Log.e("HDFC_DEBUG", "Error in invokeLocalSource: ${e.message}")
            e.printStackTrace()
        }
    }

    // Keep old decrypter as fallback temporarily
    private fun tryOldDecrypter(encrypted: String, seed: Int): String {
        // Simplified version of old working logic
        try {
            // Attempt 4 (was working)
            val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
            val rot13edBytes = applyRot13ToBytes(decodedBytes)
            val reversedBytes = rot13edBytes.reversedArray()
            return applyCustomShiftToBytes(reversedBytes, seed)
        } catch (e: Exception) {
            return ""
        }
    }

    private fun applyRot13ToBytes(inputBytes: ByteArray): ByteArray {
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

    private fun applyCustomShiftToBytes(inputBytes: ByteArray, seed: Int): String {
        val sb = StringBuilder()
        for (i in inputBytes.indices) {
            val charCode = inputBytes[i].toInt() and 0xFF
            val shift = seed % (i + 5)
            val newChar = (charCode - shift + 256) % 256
            sb.append(newChar.toChar())
        }
        return sb.toString()
    }

    private suspend fun processDecryptedUrl(
        decryptedUrl: String,
        source: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (seenUrls.contains(decryptedUrl)) {
            Log.d("HDFC_DEBUG", "URL already seen, skipping")
            return
        }
        seenUrls.add(decryptedUrl)
        
        val isHls = decryptedUrl.contains(".m3u8") || decryptedUrl.endsWith(".txt")
        
        // Use newExtractorLink which is a suspend function
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
