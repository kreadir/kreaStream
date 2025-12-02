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
    override var mainUrl              = "https://www.hdfilmcehennemi.la"
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

    // START: Main Page Tidy Up and Pagination Support
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
        // Handle "Yeni Bölümler" which use the mini-poster format
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
    // END: Main Page Tidy Up and Pagination Support

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
    
    // START DOWNLOAD LOGIC FUNCTIONS
    
    private suspend fun extractDownloadLinks(rapidrameId: String, callback: (ExtractorLink) -> Unit) {
        val downloadUrl = "https://cehennempass.pw/download/$rapidrameId"
        
        // Map the qualities provided in the script to display names
        val qualities = mapOf(
            "low" to "Download SD", 
            "high" to "Download HD"   
        )

        qualities.forEach { (qualityData, qualityName) ->
            val postUrl = "https://cehennempass.pw/process_quality_selection.php"
            
            // Build the form data for the POST request
            val postBody = okhttp3.FormBody.Builder()
                .add("video_id", rapidrameId)
                .add("selected_quality", qualityData)
                .build()
            
            // Make the POST request to get the final download link
            val response = app.post(
                postUrl,
                requestBody = postBody,
                headers = standardHeaders,
                referer = downloadUrl 
            ).parsedSafe<DownloadResponse>()

            val finalLink = response?.download_link

            if (finalLink.isNullOrEmpty()) return@forEach

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
    }
    
    // END DOWNLOAD LOGIC FUNCTIONS

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
    
    /**
     * Helper object to encapsulate and execute the decryption logic attempts.
     */
    private object HDFCDecrypter {
        
        private fun applyRot13(inputBytes: ByteArray): ByteArray {
            val rot13edBytes = ByteArray(inputBytes.size)
            for (i in inputBytes.indices) {
                val charCode = inputBytes[i].toInt()
                val char = charCode.toChar()
                rot13edBytes[i] = when (char) {
                    in 'a'..'z' -> (((charCode - 'a'.code + 13) % 26 + 'a'.code).toChar()).code.toByte()
                    in 'A'..'Z' -> (((charCode - 'A'.code + 13) % 26 + 'A'.code).toChar()).code.toByte()
                    else -> inputBytes[i]
                }
            }
            return rot13edBytes
        }

        private fun applyCustomShift(inputBytes: ByteArray, seed: Int): String {
            val sb = StringBuilder()
            for (i in inputBytes.indices) {
                val charCode = inputBytes[i].toInt() and 0xFF // Unsigned conversion
                val shift = seed % (i + 5)
                // JS: (charCode - (seed % (i+5)) + 256) % 256
                val newChar = (charCode - shift + 256) % 256
                sb.append(newChar.toChar())
            }
            return sb.toString()
        }

        // Attempt 1: Reverse String -> Base64 Decode -> ROT13 on Bytes -> Custom Shift
        private fun attempt1(encryptedData: String, seed: Int): String {
            val reversedString = encryptedData.reversed()
            val decodedBytes = Base64.decode(reversedString, Base64.DEFAULT)
            val rot13edBytes = applyRot13(decodedBytes)
            return applyCustomShift(rot13edBytes, seed)
        }

        // Attempt 2: ROT13 on String -> Reverse String -> Base64 Decode -> Custom Shift
        private fun attempt2(encryptedData: String, seed: Int): String {
            val rot13edString = applyRot13(encryptedData.toByteArray()).toString(Charsets.UTF_8)
            val reversedString = rot13edString.reversed()
            val decodedBytes = Base64.decode(reversedString, Base64.DEFAULT)
            return applyCustomShift(decodedBytes, seed)
        }

        // Attempt 3: Reverse String -> ROT13 on String -> Base64 Decode -> Custom Shift
        private fun attempt3(encryptedData: String, seed: Int): String {
            val reversedString = encryptedData.reversed()
            val rot13edString = applyRot13(reversedString.toByteArray()).toString(Charsets.UTF_8)
            val decodedBytes = Base64.decode(rot13edString, Base64.DEFAULT)
            return applyCustomShift(decodedBytes, seed)
        }

        // Main function to try all known orders
        fun dynamicDecrypt(encryptedData: String, seed: Int): String {
            val decryptionAttempts = listOf<() -> String>(
                { attempt1(encryptedData, seed) },
                { attempt2(encryptedData, seed) },
                { attempt3(encryptedData, seed) }
            )

            for ((index, attempt) in decryptionAttempts.withIndex()) {
                try {
                    val decryptedUrl = attempt()
                    // A successful decryption should yield a URL starting with "http"
                    if (decryptedUrl.startsWith("http")) {
                        Log.d("HDFC", "Decryption Success with Attempt ${index + 1}")
                        return decryptedUrl
                    }
                } catch (e: IllegalArgumentException) {
                    // This typically means bad Base64 input, which is expected for incorrect orders.
                    Log.d("HDFC", "Decryption Attempt ${index + 1} failed: Bad Base64")
                } catch (e: Exception) {
                    Log.e("HDFC", "Decryption Attempt ${index + 1} failed: ${e.message}")
                }
            }

            Log.e("HDFC", "All decryption attempts failed.")
            return ""
        }
    }


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
            
            // 2. Extract the encrypted array string: matches func(["45","4l",...])
            val callRegex = Regex("""\w+\(\[(.*?)\]\)""")
            val arrayContent = callRegex.find(unpacked)?.groupValues?.get(1) ?: return
            
            // Clean it up to get the single Base64 string "454l..."
            val encryptedString = arrayContent.replace("\"", "").replace("'", "").replace(",", "").replace("\\s".toRegex(), "")

            // 3. Extract the math seed: matches charCode-(SEED%(i+5))
            val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)\)""")
            val seed = seedRegex.find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: 399756995 // Fallback seed

            // 4. Decrypt dynamically
            val decryptedUrl = HDFCDecrypter.dynamicDecrypt(encryptedString, seed)
            
            if (decryptedUrl.isEmpty()) return
            Log.d("HDFC", "Decrypted URL: $decryptedUrl")

            // 5. Determine if it's HLS 
            val isHls = decryptedUrl.contains(".m3u8") || decryptedUrl.endsWith(".txt")
            
            callback.invoke(
                newExtractorLink(
                    source  = source,
                    name    = source,
                    url     = decryptedUrl
                ){
                    this.referer = referer // Use the passed referer
                    this.quality = Qualities.Unknown.value
                    this.type    = if(isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
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
        
        // --- 1. Handle Default Player (Close) ---
        val defaultSourceUrl = fixUrlNull(document.selectFirst(".close")?.attr("data-src"))

        var rapidrameId: String? = null // Variable to store the ID for download links
        
        if (defaultSourceUrl != null) {
            val sourceName = "Close"
            var referer = "$mainUrl/" // Default referer for non-mobi links
            
            // 1.1. Subtitle processing & Referer check
            if (defaultSourceUrl.contains("hdfilmcehennemi.mobi")) {
                try {
                    // Fetch the iframe content to extract subtitles and get the base URI for the referer
                    val iframedoc = app.get(defaultSourceUrl, referer = mainUrl).document
                    // Set referer to the base domain of the iframe 
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

            // Extract the rapidrame_id if it exists, for use in the download function
            rapidrameId = defaultSourceUrl.substringAfter("?rapidrame_id=", "").takeIf { it.isNotEmpty() }
            
            // 1.2. Decrypt the main video link using the iframe URL directly
            invokeLocalSource(sourceName, defaultSourceUrl, referer, callback) 
        }

        // --- 2. Check Alternative Links (buttons below player) ---
        val rapidrameReferer = "$mainUrl/" // Use main URL as referer for alternative/external sources
        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val sourceNameRaw = button.text().replace("(HDrip Xbet)", "").trim()

                // Skip 'Close' to prevent duplication of links
                if (sourceNameRaw.equals("close", ignoreCase = true)) {
                    return@forEach
                }
                
                val videoID = button.attr("data-video")
                
                // API call to get the iframe link (e.g., /rplayer/...)
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf("Content-Type" to "application/json", "X-Requested-With" to "fetch"),
                    referer = data
                ).text

                // Extract the data-src from the JSON response
                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "") ?: ""
                
                // Convert the internal rapidrame link to the /playerr/ format for extraction
                if (iframe.contains("?rapidrame_id=")) {
                    iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                }

                if (iframe.isNotEmpty()) {
                    // Set source name to "Rapidrame" as requested if it matches the name
                    val finalSourceName = if (sourceNameRaw.contains("rapidrame", ignoreCase = true)) {
                        "Rapidrame $langCode"
                    } else {
                        "$sourceNameRaw $langCode"
                    }
                    invokeLocalSource(finalSourceName, iframe, rapidrameReferer, callback) // Pass the main URL as referer
                }
            }
        }
        // --- 3. Handle Download Links ---
        // Only run if we found a rapidrame ID
        if (!rapidrameId.isNullOrEmpty()) {
            extractDownloadLinks(rapidrameId, callback)
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
