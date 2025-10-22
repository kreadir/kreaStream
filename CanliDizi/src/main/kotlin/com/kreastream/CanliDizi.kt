package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element
import java.util.Base64

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    // ===== MAIN PAGE =====
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val all = ArrayList<HomePageList>()
        
        // Parse different categories
        listOf(
            Triple("$mainUrl/diziler", "Yerli Diziler", true),
            Triple("$mainUrl/dijital-diziler-izle", "Dijital Diziler", true),
            Triple("$mainUrl/film-izle", "Filmler", false)
        ).forEach { (url, categoryName, isSeries) ->
            try {
                val items = if (isSeries) {
                    parseCategoryWithPagination(url, categoryName)
                } else {
                    parseMoviesWithPagination(url, categoryName)
                }
                if (items.isNotEmpty()) {
                    all.add(HomePageList(categoryName, items))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return if (all.isEmpty()) null else newHomePageResponse(all)
    }

    // ===== SEARCH =====
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/search/${query.encodeToUrl()}/"
        val document = app.get(searchUrl).document
        
        val results = mutableListOf<SearchResponse>()
        
        document.select("div.single-item").forEach { element ->
            parseSearchResultItem(element)?.let { result -> results.add(result) }
        }
        
        return results
    }

    // ===== LOAD CONTENT =====
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        return when {
            document.selectFirst("div.incontentx") != null -> 
                loadNewSeriesStructure(document, url)
            url.contains("/film") || document.selectFirst("div.bolumust") == null -> 
                loadMovieStructure(document, url)
            else -> 
                loadOldStructure(document, url)
        }
    }

    // ===== LOAD LINKS =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Starting link extraction for: $data")
        
        val document = app.get(data).document
        val html = document.html()
        
        // Try BetaPlayer extraction first (for movies)
        if (extractBetaPlayerLinks(html, data, callback)) {
            println("BetaPlayer extractor found links!")
            return true
        }
        
        // Try CanliPlayer extraction (for series)
        if (extractCanliPlayerLinks(html, data, callback)) {
            println("CanliPlayer extractor found links!")
            return true
        }
        
        // Try YouTube extraction
        if (extractYouTubeLinks(html, data, callback)) {
            println("YouTube extractor found links!")
            return true
        }
        
        // Try iframe extraction
        if (extractIframeLinks(document, data, callback, subtitleCallback)) {
            println("Iframe extractor found links!")
            return true
        }
        
        // Try direct video extraction
        if (extractDirectVideoLinks(html, data, callback)) {
            println("Direct video extractor found links!")
            return true
        }
        
        println("No video links found after all extraction methods")
        return false
    }

    // ===== EXTRACTION METHODS =====
    
    private suspend fun extractYouTubeLinks(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        println("YouTube extractor started")
        
        val patterns = listOf(
            """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
            """youtu\.be/([a-zA-Z0-9_-]{11})""",
            """youtube\.com/embed/([a-zA-Z0-9_-]{11})""",
            """videoId["']?\s*:\s*["']([^"']{11})["']""",
            """source\s*:\s*["'](https?://www\.youtube\.com/watch\?v=([^"']{11}))["']"""
        )

        for (pattern in patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val youtubeUrl = when {
                    pattern.contains("youtube\\.com/watch\\?v=") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("youtu\\.be/") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("youtube\\.com/embed/") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("videoId") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("source") && match.groupValues.size > 2 -> 
                        match.groupValues[1]
                    else -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                }

                if (isValidYouTubeUrl(youtubeUrl)) {
                    println("Found valid YouTube URL: $youtubeUrl")
                    if (createYouTubeLink(youtubeUrl, referer, callback)) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractCanliPlayerLinks(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        println("CanliPlayer extractor started")
        
        // Look for canliplayer.com URLs
        val playerPatterns = listOf(
            """(https?://[^"'\s]*canliplayer\.com/fireplayer/video/[^"'\s]*)""",
            """(https?://[^"'\s]*canliplayer\.com[^"'\s]*video[^"'\s]*)""",
            """(https?://[^"'\s]*fireplayer\.com[^"'\s]*)"""
        )

        for (pattern in playerPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                println("Found CanliPlayer URL: $playerUrl")
                
                try {
                    val response = app.get(playerUrl, referer = referer)
                    val playerHtml = response.text
                    
                    // Method 1: Look for base64 encoded video URLs in packed JavaScript
                    if (extractFromPackedJavaScript(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Method 2: Look for direct video URLs in the HTML
                    if (extractDirectVideos(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Method 3: Look for YouTube embeds in CanliPlayer
                    if (extractYouTubeFromCanliPlayer(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("Error fetching CanliPlayer: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractBetaPlayerLinks(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        println("BetaPlayer extractor started")
        
        // Look for betaplayer.site URLs
        val playerPatterns = listOf(
            """(https?://[^"'\s]*betaplayer\.site/embed/[^"'\s]*)""",
            """(https?://[^"'\s]*betaplayer\.site[^"'\s]*)"""
        )

        for (pattern in playerPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                println("Found BetaPlayer URL: $playerUrl")
                
                try {
                    val response = app.get(playerUrl, referer = referer)
                    val playerHtml = response.text
                    
                    // Method 1: Extract from JWPlayer configuration with base64 sources
                    if (extractFromJWPlayerConfig(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Method 2: Try direct m3u8 URL extraction
                    if (extractDirectBetaPlayerM3U8(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Method 3: Try YouTube extraction
                    if (extractYouTubeLinks(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Method 4: Then try direct videos
                    if (extractDirectVideos(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("Error fetching BetaPlayer: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractIframeLinks(
        document: Element, 
        referer: String, 
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("Iframe extractor started")
        
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                val fixedUrl = fixUrl(iframeSrc)
                println("Found iframe: $fixedUrl")
                
                try {
                    val response = app.get(fixedUrl, referer = referer)
                    val iframeHtml = response.text
                    
                    // Try YouTube extraction
                    if (extractYouTubeLinks(iframeHtml, fixedUrl, callback)) {
                        return true
                    }
                    
                    // Try direct video extraction
                    if (extractDirectVideoLinks(iframeHtml, fixedUrl, callback)) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("Error fetching iframe: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractDirectVideoLinks(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        println("Direct video extractor started")
        
        val videoPatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.webm(?:\?[^"'\s]*)?)""",
            """file\s*:\s*["'](https?://[^"']+)["']""",
            """source\s*:\s*["'](https?://[^"']+)["']""",
            """videoUrl\s*:\s*["'](https?://[^"']+)["']""",
            """src\s*:\s*["'](https?://[^"']+)["']"""
        )
        
        for (pattern in videoPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found direct video URL: $videoUrl")
                    createVideoLink(videoUrl, referer, callback, "Direct")
                    return true
                }
            }
        }
        
        return false
    }
    
    // ===== IMPROVED BETA PLAYER EXTRACTION =====
    
    private suspend fun extractFromJWPlayerConfig(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Looking for JWPlayer configuration...")
        
        // Method 1: Look for JWPlayer setup with base64 encoded sources
        val jwPlayerPatterns = listOf(
            """jwplayer\s*\(\s*["'][^"']+["']\s*\)\s*\.setup\s*\(\s*({[^}]+})\s*\)""",
            """beplayer\s*=\s*jwplayer\s*\(\s*["'][^"']+["']\s*\)\s*\.setup\s*\(\s*({[^}]+})\s*\)""",
            """sources\s*:\s*\[\s*{\s*file\s*:\s*"([^"]+)""""
        )
        
        for (pattern in jwPlayerPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                try {
                    if (pattern.contains("sources")) {
                        // Extract just the base64 string from sources array
                        val base64String = match.groupValues[1]
                        println("Found base64 string in sources: $base64String")
                        
                        // Check if it's a betaplayer.site list URL
                        if (base64String.contains("betaplayer.site/list/")) {
                            val encodedPart = base64String.substringAfter("list/").substringBefore("\"")
                            if (encodedPart.isNotBlank()) {
                                println("Found betaplayer list encoded part: $encodedPart")
                                if (decodeBetaPlayerList(encodedPart, referer, callback)) {
                                    return true
                                }
                            }
                        } else {
                            // Try direct base64 decoding
                            if (decodeAndCreateVideoLink(base64String, referer, callback, "BetaPlayer JWPlayer")) {
                                return true
                            }
                        }
                    } else {
                        // Extract the entire JWPlayer configuration
                        val configJson = match.groupValues[1]
                        println("Found JWPlayer config: $configJson")
                        
                        // Look for sources array in the configuration
                        val sourcesPattern = """sources\s*:\s*\[\s*\{[^}]+\}"""
                        Regex(sourcesPattern, RegexOption.IGNORE_CASE).findAll(configJson).forEach { sourceMatch ->
                            val sourceBlock = sourceMatch.value
                            println("Found sources block: $sourceBlock")
                            
                            // Extract file URL from source block
                            val filePattern = """file\s*:\s*"([^"]+)"""
                            Regex(filePattern, RegexOption.IGNORE_CASE).findAll(sourceBlock).forEach { fileMatch ->
                                val fileUrl = fileMatch.groupValues[1]
                                println("Found file URL: $fileUrl")
                                
                                // Check if it's a betaplayer.site list URL
                                if (fileUrl.contains("betaplayer.site/list/")) {
                                    val encodedPart = fileUrl.substringAfter("list/").substringBefore("\"")
                                    if (encodedPart.isNotBlank()) {
                                        println("Found betaplayer list encoded part: $encodedPart")
                                        if (decodeBetaPlayerList(encodedPart, referer, callback)) {
                                            return true
                                        }
                                    }
                                } else if (isVideoUrl(fileUrl)) {
                                    createVideoLink(fileUrl, referer, callback, "BetaPlayer Direct")
                                    return true
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error parsing JWPlayer config: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    private suspend fun decodeBetaPlayerList(
        base64String: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("Decoding BetaPlayer list base64: $base64String")
            
            // Decode the base64 string
            val decodedBytes = Base64.getDecoder().decode(base64String)
            val decodedString = String(decodedBytes)
            println("First decode: $decodedString")
            
            // The decoded string might be another base64 string
            if (decodedString.matches(Regex("[A-Za-z0-9+/=]+")) && decodedString.length > 50) {
                // Try double decoding
                val doubleDecodedBytes = Base64.getDecoder().decode(decodedString)
                val doubleDecodedString = String(doubleDecodedBytes)
                println("Double decoded: $doubleDecodedString")
                
                // Check if this is a valid video URL
                if (isVideoUrl(doubleDecodedString)) {
                    println("Found video URL after double decode: $doubleDecodedString")
                    createVideoLink(doubleDecodedString, referer, callback, "BetaPlayer Double Decode")
                    return true
                }
                
                // If not a direct URL, it might be a path that needs to be combined with betaplayer.site
                if (doubleDecodedString.startsWith("/") || !doubleDecodedString.contains("://")) {
                    val fullUrl = "https://betaplayer.site$doubleDecodedString"
                    println("Constructed full URL: $fullUrl")
                    if (isVideoUrl(fullUrl)) {
                        createVideoLink(fullUrl, referer, callback, "BetaPlayer Constructed")
                        return true
                    }
                }
            }
            
            // Check if the first decoded string is a video URL
            if (isVideoUrl(decodedString)) {
                println("Found video URL after first decode: $decodedString")
                createVideoLink(decodedString, referer, callback, "BetaPlayer Single Decode")
                return true
            }
            
        } catch (e: Exception) {
            println("Failed to decode BetaPlayer list: ${e.message}")
        }
        
        return false
    }
    
    private suspend fun extractDirectBetaPlayerM3U8(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Looking for direct BetaPlayer m3u8 URLs...")
        
        // Look for betaplayer.site m3u8 patterns
        val m3u8Patterns = listOf(
            """betaplayer\.site/m3u/([A-Za-z0-9+/=]+)""",
            """(https?://betaplayer\.site/m3u/[^"'\s]+)"""
        )
        
        for (pattern in m3u8Patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                if (pattern.contains("https?")) {
                    // Direct m3u8 URL
                    val m3u8Url = fixUrl(match.groupValues[1])
                    println("Found direct BetaPlayer m3u8 URL: $m3u8Url")
                    createVideoLink(m3u8Url, referer, callback, "BetaPlayer M3U8")
                    return true
                } else {
                    // Base64 encoded m3u8 path
                    val base64String = match.groupValues[1]
                    println("Found BetaPlayer m3u8 base64: $base64String")
                    
                    try {
                        val decodedBytes = Base64.getDecoder().decode(base64String)
                        val decodedPath = String(decodedBytes)
                        val m3u8Url = "https://betaplayer.site/m3u/$decodedPath"
                        println("Constructed m3u8 URL: $m3u8Url")
                        
                        if (isVideoUrl(m3u8Url)) {
                            createVideoLink(m3u8Url, referer, callback, "BetaPlayer M3U8 Base64")
                            return true
                        }
                    } catch (e: Exception) {
                        println("Failed to decode m3u8 base64: ${e.message}")
                    }
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractBase64VideoLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Looking for base64 encoded video URLs...")
        
        // Look for base64 encoded strings that might be video URLs
        val base64Patterns = listOf(
            """list/([A-Za-z0-9+/=]+)""",
            """file\s*:\s*"([A-Za-z0-9+/=]{50,})""",
            """sources\s*:\s*\[\s*{\s*file\s*:\s*"([A-Za-z0-9+/=]{50,})"""
        )
        
        for (pattern in base64Patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val base64String = match.groupValues[1]
                if (base64String.length > 50) { // Likely a video URL if it's long enough
                    println("Found potential base64 string: $base64String")
                    if (decodeAndCreateVideoLink(base64String, referer, callback, "BetaPlayer Base64")) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    private suspend fun decodeAndCreateVideoLink(
        base64String: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        sourceName: String
    ): Boolean {
        try {
            val decodedBytes = Base64.getDecoder().decode(base64String)
            val decodedString = String(decodedBytes)
            println("Decoded base64: $decodedString")
            
            if (isVideoUrl(decodedString)) {
                println("Found video URL in base64: $decodedString")
                createVideoLink(decodedString, referer, callback, sourceName)
                return true
            }
            
            // Sometimes it might be double-encoded
            if (decodedString.matches(Regex("[A-Za-z0-9+/=]+")) && decodedString.length > 50) {
                println("Trying double decode...")
                val doubleDecodedBytes = Base64.getDecoder().decode(decodedString)
                val doubleDecodedString = String(doubleDecodedBytes)
                println("Double decoded: $doubleDecodedString")
                
                if (isVideoUrl(doubleDecodedString)) {
                    println("Found video URL in double base64: $doubleDecodedString")
                    createVideoLink(doubleDecodedString, referer, callback, "$sourceName Double")
                    return true
                }
            }
        } catch (e: Exception) {
            println("Failed to decode base64: ${e.message}")
        }
        
        return false
    }
    
    // ===== IMPROVED CANLIPLAYER EXTRACTION =====
    
    private suspend fun extractFromPackedJavaScript(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Looking for packed JavaScript patterns...")
        
        // Method 1: Look for base64 encoded URLs in the packed JavaScript
        val base64Pattern = """"12"\s*:\s*"([A-Za-z0-9+/=]+)""""
        Regex(base64Pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val base64String = match.groupValues[1]
            println("Found base64 string: $base64String")
            
            if (decodeAndCreateVideoLink(base64String, referer, callback, "CanliPlayer Base64")) {
                return true
            }
        }
        
        // Method 2: Look for the specific packed array pattern used by CanliPlayer
        val packedPatterns = listOf(
            """split\('\|'\)[^)]+\)[^,]+,\d+,[^,]+,'([^']+)'\)""",
            """eval\(function\([^)]+\)\s*\([^,]+,\s*[^,]+,\s*[^,]+,\s*'([^']+)'\)""",
            """'([^']+)'\)\)\)\)\)\);"""
        )
        
        for (pattern in packedPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val packedString = match.groupValues[1]
                println("Found packed string: $packedString")
                
                // Try to extract YouTube IDs from packed array
                val parts = packedString.split('|')
                for (part in parts) {
                    // Look for YouTube video IDs (11 characters)
                    if (part.length == 11 && isValidYouTubeId(part)) {
                        val youtubeUrl = "https://www.youtube.com/watch?v=$part"
                        println("Found YouTube ID in packed array: $part")
                        if (createYouTubeLink(youtubeUrl, referer, callback)) {
                            return true
                        }
                    }
                    
                    // Look for base64 encoded URLs in the packed parts
                    if (part.length > 20 && part.matches(Regex("[A-Za-z0-9+/=]+"))) {
                        if (decodeAndCreateVideoLink(part, referer, callback, "CanliPlayer Packed")) {
                            return true
                        }
                    }
                }
            }
        }
        
        // Method 3: Look for direct file references in the packed JavaScript
        val filePatterns = listOf(
            """file["']?\s*:\s*["']([^"']+)["']""",
            """source["']?\s*:\s*["']([^"']+)["']""",
            """url["']?\s*:\s*["']([^"']+)["']"""
        )
        
        for (pattern in filePatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found direct video in packed JS: $videoUrl")
                    createVideoLink(videoUrl, referer, callback, "CanliPlayer JS")
                    return true
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractYouTubeFromCanliPlayer(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Looking for YouTube embeds in CanliPlayer...")
        
        // Look for YouTube video IDs in various patterns
        val youtubePatterns = listOf(
            """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
            """youtu\.be/([a-zA-Z0-9_-]{11})""",
            """youtube\.com/embed/([a-zA-Z0-9_-]{11})""",
            """videoId["']?\s*:\s*["']([^"']{11})["']""",
            """src["']?\s*:\s*["']([^"']{11})["']"""
        )
        
        for (pattern in youtubePatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoId = match.groupValues[1]
                if (isValidYouTubeId(videoId)) {
                    val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                    println("Found YouTube URL in CanliPlayer: $youtubeUrl")
                    if (createYouTubeLink(youtubeUrl, referer, callback)) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractDirectVideos(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoPatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """file\s*:\s*["'](https?://[^"']+)["']""",
            """source\s*:\s*["'](https?://[^"']+)["']"""
        )
        
        for (pattern in videoPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found direct video: $videoUrl")
                    createVideoLink(videoUrl, referer, callback, "Direct")
                    return true
                }
            }
        }
        
        return false
    }
    
    // ===== LINK CREATION METHODS =====
    
    private suspend fun createYouTubeLink(
        youtubeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
            if (videoId.length != 11) return false

            callback.invoke(
                newExtractorLink(
                    "YouTube",
                    "YouTube",
                    youtubeUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            
            println("Successfully created YouTube extractor link")
            return true
        } catch (e: Exception) {
            println("Error creating YouTube link: ${e.message}")
            return false
        }
    }
    
    private suspend fun createVideoLink(
        videoUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        sourceName: String
    ) {
        try {
            val quality = determineQuality(videoUrl)
            val type = when {
                videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                videoUrl.contains(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            println("Creating video link: $videoUrl (Quality: $quality, Type: $type)")

            callback.invoke(
                newExtractorLink(
                    "$name - $sourceName",
                    name,
                    videoUrl,
                    type
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            )
        } catch (e: Exception) {
            println("Error creating video link: ${e.message}")
        }
    }
    
    // ===== VALIDATION METHODS =====
    
    private fun isValidYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com/watch?v=") && 
               url.length >= 30 &&
               !url.contains("no-referrer")
    }
    
    private fun isValidYouTubeId(id: String): Boolean {
        return id.length == 11 && 
               id.matches(Regex("[a-zA-Z0-9_-]+")) &&
               id !in listOf("fireplayer", "FirePlayer", "jwplayer8", "videojsSkin", 
                           "beezPlayer", "youtubeApi", "no-referrer", "referrer", "canliplayer")
    }
    
    private fun isVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        
        val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mkv", ".avi", ".mov", ".flv")
        val videoKeywords = listOf("video", "stream", "m3u8", "mp4", "hls", "dash")
        
        return (videoExtensions.any { url.contains(it, ignoreCase = true) } ||
                videoKeywords.any { url.contains(it, ignoreCase = true) }) &&
                !url.contains("data:image") &&
                !url.contains("base64") &&
                !url.contains("placeholder") &&
                !url.contains("blank") &&
                !url.contains("logo") &&
                !url.contains("ads") &&
                !url.contains("banner")
    }

    // ===== PARSER FUNCTIONS =====
    
    private fun parseSearchResultItem(element: Element): SearchResponse? {
        try {
            val link = element.selectFirst("div.cat-img a")?.attr("href")?.let { fixUrl(it) } ?: return null
            
            val title = element.selectFirst("div.categorytitle a")?.text()?.trim()
                ?: element.selectFirst("div.cat-img img")?.attr("alt")?.trim()
                    ?.replace("son bölüm izle", "")?.trim()
                    ?.replace("izle", "")?.trim()
                ?: element.selectFirst("div.cat-img img")?.attr("title")?.trim()
                    ?.replace("son bölüm izle", "")?.trim()
                    ?.replace("izle", "")?.trim()
                ?: return null

            val poster = element.selectFirst("div.cat-img img")?.let { img ->
                img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                    ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }

            val year = element.select("div.cat-container-in div").mapNotNull { div ->
                val text = div.text().trim()
                if (text.contains("Yapım Yılı")) {
                    Regex("""(\d{4})""").find(text)?.value?.toIntOrNull()
                } else {
                    null
                }
            }.firstOrNull()

            val scoreText = element.selectFirst("div.imdbp")?.text()?.trim()
            val score = scoreText?.let { text ->
                Regex("""IMDb:\s*([\d,]+)""").find(text)?.groupValues?.get(1)
                    ?.replace(",", ".")?.toFloatOrNull()?.times(10)?.toInt()
            }

            val description = element.selectFirst("div.cat_ozet")?.text()?.trim()

            val isMovie = link.contains("/film") || 
                         description?.contains("film") == true ||
                         title.contains("film", ignoreCase = true)

            return if (isMovie) {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(score)
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(score)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun parseDizilerItem(element: Element): TvSeriesSearchResponse? {
        val link = element.selectFirst("div.cat-img a")?.attr("href")?.let { fixUrl(it) } ?: return null
        
        val title = element.selectFirst("div.categorytitle a")?.text()?.trim()
            ?: element.selectFirst("div.cat-img img")?.attr("alt")?.trim()
            ?: element.selectFirst("div.cat-img img")?.attr("title")?.trim()
            ?: return null

        val poster = element.selectFirst("div.cat-img img")?.let { img ->
            img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val year = element.select("div.cat-container-in div").mapNotNull { div ->
            div.text().trim().takeIf { it.contains("Yapım Yılı") }?.let {
                Regex("""(\d{4})""").find(it)?.value?.toIntOrNull()
            }
        }.firstOrNull()

        val scoreText = element.selectFirst("div.imdbp")?.text()?.trim()
        val score = scoreText?.let { text ->
            Regex("""IMDb:\s*([\d,]+)""").find(text)?.groupValues?.get(1)
                ?.replace(",", ".")?.toFloatOrNull()?.times(10)?.toInt()
        }

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    private fun parseMovieItem(element: Element): MovieSearchResponse? {
        val link = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } 
            ?: element.selectFirst(".cat-img a, .poster a, .image a")?.attr("href")?.let { fixUrl(it) }
            ?: return null

        val title = element.selectFirst(".serie-name, .episode-name, .categorytitle, .title, .film-title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: element.selectFirst("img")?.attr("title")?.trim()
            ?: return null

        val poster = element.selectFirst("img")?.let { img ->
            img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val year = element.selectFirst(".episode-name, .year, .release-date")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            element.selectFirst(".rating, .imdb, .imdb-rating")?.text()?.toFloatOrNull()?.let { rating ->
                this.score = Score.from10(rating)
            }
        }
    }

    // ===== LOAD STRUCTURE FUNCTIONS =====
    
    private suspend fun loadNewSeriesStructure(document: Element, url: String): LoadResponse {
        val title = document.selectFirst("h1.title-border")?.text()?.trim()
            ?.replace("son bölüm izle", "")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        
        val poster = document.selectFirst("div.cat-img img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val description = document.selectFirst("div.cat_ozet")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = document.select("div.cat-container-in div").mapNotNull { div ->
            div.text().trim().takeIf { it.contains("Yapım Yılı") }?.let {
                Regex("""(\d{4})""").find(it)?.value?.toIntOrNull()
            }
        }.firstOrNull()

        val episodes = document.select("div.bolumust a").mapNotNull { parseNewEpisodeItem(it) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    private suspend fun loadOldStructure(document: Element, url: String): LoadResponse {
        val title = document.selectFirst("h1.series-title, h1.title, h1.entry-title, h1")?.text()?.trim() 
            ?: document.selectFirst(".series-name, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        
        val poster = document.selectFirst("img.poster, .poster img, .series-poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".wp-post-image, .attachment-post-thumbnail")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img[data-wpfc-original-src]")?.attr("data-wpfc-original-src")?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val description = document.selectFirst("div.description, .plot, .synopsis, .entry-content")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val isMovie = url.contains("/film") || 
                    (url.contains("/izle.html") && !url.contains("-bolum-") && !url.contains("/kategori/"))

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = document.select("div.episodes.episode div.list-episodes div.episode-box, div.episode-list div.episode, .season-episodes li")
                .mapNotNull { parseEpisode(it) }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private suspend fun loadMovieStructure(document: Element, url: String): LoadResponse {
        val title = document.selectFirst("h1.title-border, h1, h1.entry-title")?.text()?.trim()
            ?.replace("son bölüm izle", "")?.trim()
            ?.replace("izle", "")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        
        val poster = document.selectFirst("div.cat-img img, .poster img, .wp-post-image")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img[data-wpfc-original-src]")?.attr("data-wpfc-original-src")?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val description = document.selectFirst("div.cat_ozet, .plot, .synopsis, .entry-content")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ===== HELPER FUNCTIONS =====
    
    private suspend fun parseCategoryWithPagination(baseUrl: String, categoryName: String): List<TvSeriesSearchResponse> {
        val items = mutableListOf<TvSeriesSearchResponse>()
        
        for (pageNum in 1..10) {
            val pageUrl = if (pageNum == 1) baseUrl else "$baseUrl/page/$pageNum"
            try {
                val pageDocument = app.get(pageUrl).document
                val pageItems = pageDocument.select("div.seriescontent div.single-item").mapNotNull {
                    parseDizilerItem(it)
                }
                
                if (pageItems.isEmpty()) break
                
                items.addAll(pageItems)
                
            } catch (e: Exception) {
                break
            }
        }
        
        return items
    }

    private suspend fun parseMoviesWithPagination(baseUrl: String, categoryName: String): List<MovieSearchResponse> {
        val items = mutableListOf<MovieSearchResponse>()
        
        for (pageNum in 1..10) {
            val pageUrl = if (pageNum == 1) baseUrl else "$baseUrl/page/$pageNum"
            try {
                val pageDocument = app.get(pageUrl).document
                
                val movieSelectors = listOf(
                    "div.seriescontent div.single-item",
                    "div.episodes.episode div.list-episodes div.episode-box",
                    "div.film-list div.film-item"
                )
                
                val pageItems = movieSelectors.flatMap { selector ->
                    pageDocument.select(selector).mapNotNull { parseMovieItem(it) }
                }
                
                if (pageItems.isEmpty()) break
                
                items.addAll(pageItems)
                
            } catch (e: Exception) {
                break
            }
        }
        
        return items
    }

    private fun parseNewEpisodeItem(element: Element): Episode? {
        val epUrl = element.attr("href")?.let { fixUrl(it) } ?: return null
        
        val epTitle = element.selectFirst("div.baslik")?.text()?.trim()
            ?: element.attr("title")?.trim()
            ?: "Episode"

        val epNum = Regex("""(\d+)\.?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        return newEpisode(epUrl) {
            this.name = epTitle
            this.episode = epNum
            this.season = 1
        }
    }

    private fun parseEpisode(element: Element): Episode? {
        val epTitle = element.selectFirst("span.episode-title, .episode-name, .title, a")?.text()?.trim() 
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: "Episode"
        
        val epUrl = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        
        val epNum = element.selectFirst("span.episode-number, .episode-num, .number")?.text()?.toIntOrNull()
            ?: Regex("""(\d+)\.?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        val season = element.selectFirst("span.season-number, .season-num")?.text()?.toIntOrNull()
            ?: Regex("""(\d+)\.?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        return newEpisode(epUrl) {
            this.name = epTitle
            this.episode = epNum
            this.season = season
        }
    }

    // ===== UTILITY FUNCTIONS =====
    
    private fun String.encodeToUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    private fun determineQuality(url: String): Int {
        return when {
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720") -> Qualities.P720.value
            url.contains("480") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
