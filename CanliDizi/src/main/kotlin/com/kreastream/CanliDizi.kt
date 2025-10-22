package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
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
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        val results = mutableListOf<SearchResponse>()
        
        // Each div.single-item is one search result
        document.select("div.single-item").forEach { element ->
            parseSearchResultItem(element)?.let { result -> results.add(result) }
        }
        
        return results
    }

    // ===== LOAD CONTENT =====
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
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
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val html = document.html()
        
        var foundLinks = false

        println("Starting link extraction for: $data")

        // Method 1: Look for canliplayer.com links specifically first
        val canliPlayerPatterns = listOf(
            """(https?://[^"'\s]*canliplayer\.com/fireplayer/video/[^"'\s]*)""",
            """(https?://[^"'\s]*canliplayer\.com[^"'\s]*video[^"'\s]*)""",
            """(https?://[^"'\s]*fireplayer\.com[^"'\s]*)"""
        )

        canliPlayerPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                println("Found CanliPlayer URL: $playerUrl")
                if (extractYouTubeFromCanliPlayer(playerUrl, data, callback)) {
                    foundLinks = true
                    return@forEach
                }
            }
        }

        // Method 2: Direct YouTube video ID extraction from main page
        val youtubeIdPattern = """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"""
        Regex(youtubeIdPattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val videoId = match.groupValues[1]
            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
            println("Found direct YouTube video ID: $videoId")
            if (createYouTubeExtractorLink(youtubeUrl, data, callback)) {
                foundLinks = true
                return@forEach
            }
        }

        // Method 3: Look for iframe embeds and convert them to watch URLs
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                val fixedUrl = fixUrl(iframeSrc)
                println("Found iframe: $fixedUrl")
                if (fixedUrl.contains("youtube.com/embed")) {
                    // Convert YouTube embed to watch URL
                    val videoId = fixedUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
                    if (videoId.length == 11) {
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        println("Converted YouTube embed to: $youtubeUrl")
                        if (createYouTubeExtractorLink(youtubeUrl, data, callback)) {
                            foundLinks = true
                        }
                    }
                } else if (extractFromIframe(fixedUrl, callback, subtitleCallback)) {
                    foundLinks = true
                }
            }
        }

        // Method 4: Look for betaplayer.site links
        val betaPlayerPatterns = listOf(
            """(https?://[^"'\s]*betaplayer\.site/embed/[^"'\s]*)""",
            """(https?://[^"'\s]*betaplayer\.site[^"'\s]*)"""
        )

        betaPlayerPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                println("Found BetaPlayer URL: $playerUrl")
                if (extractFromBetaPlayer(playerUrl, data, callback)) {
                    foundLinks = true
                    return@forEach
                }
            }
        }

        if (!foundLinks) {
            println("No video links found after all extraction methods")
        }

        return foundLinks
    }

    // ===== EXTRACT YOUTUBE FROM CANLIPLAYER =====
    private suspend fun extractYouTubeFromCanliPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("Extracting YouTube from CanliPlayer: $playerUrl")
            
            val response = app.get(playerUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer
            ))

            val html = response.text

            println("CanliPlayer page loaded, looking for YouTube video...")

            // Method 1: Look for the specific YouTube video ID in the packed JavaScript
            // The video ID is in the packed array: 'tMX3FwlLK18' etc.
            val packedArrayPattern = """split\('\|'\)[^)]+\)[^,]+,\d+,[^,]+,'([^']+)'\)"""
            val packedMatches = Regex(packedArrayPattern, RegexOption.IGNORE_CASE).findAll(html)
            
            for (match in packedMatches) {
                val packedString = match.groupValues[1]
                println("Found packed string: $packedString")
                
                // Split by pipe and look for YouTube video IDs
                val parts = packedString.split('|')
                for (part in parts) {
                    if (part.length == 11 && part.matches(Regex("[a-zA-Z0-9_-]+"))) {
                        // Check if it's a valid YouTube ID (not a keyword)
                        if (part !in listOf("fireplayer", "FirePlayer", "jwplayer8", "videojsSkin", 
                                        "beezPlayer", "youtubeApi", "no-referrer", "referrer", "canliplayer")) {
                            val youtubeUrl = "https://www.youtube.com/watch?v=$part"
                            println("Found potential YouTube ID in packed array: $part")
                            if (createYouTubeExtractorLink(youtubeUrl, playerUrl, callback)) {
                                return true
                            }
                        }
                    }
                }
            }

            // Method 2: Look for YouTube URLs in script content using multiple patterns
            val youtubePatterns = listOf(
                """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
                """youtu\.be/([a-zA-Z0-9_-]{11})""",
                """source\s*:\s*["'](https?://www\.youtube\.com/watch\?v=([^"']{11}))["']""",
                """["'](https?://[^"']*youtube\.com/watch\?v=([^"']{11}))["']""",
                """videoId["']?\s*:\s*["']([^"']{11})["']""",
                """src["']?\s*:\s*["']([^"']{11})["']"""
            )

            for (pattern in youtubePatterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                    val videoId = when {
                        pattern.contains("youtube\\.com/watch\\?v=") -> match.groupValues[1]
                        pattern.contains("youtu\\.be/") -> match.groupValues[1]
                        pattern.contains("source") && match.groupValues.size > 2 -> match.groupValues[2]
                        pattern.contains("videoId") -> match.groupValues[1]
                        pattern.contains("src") -> match.groupValues[1]
                        else -> match.groupValues[1].takeIf { it.length == 11 } ?: ""
                    }
                    
                    if (videoId.length == 11 && videoId.matches(Regex("[a-zA-Z0-9_-]+"))) {
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        println("Found YouTube URL with pattern '$pattern': $youtubeUrl")
                        if (createYouTubeExtractorLink(youtubeUrl, playerUrl, callback)) {
                            return true
                        }
                    }
                }
            }

            // Method 3: Look for the specific video ID in the eval function parameters
            val evalPattern = """eval\(function\([^)]+\)\s*\([^,]+,\s*[^,]+,\s*[^,]+,\s*'([^']+)'\)"""
            val evalMatches = Regex(evalPattern, RegexOption.IGNORE_CASE).findAll(html)
            
            for (match in evalMatches) {
                val evalString = match.groupValues[1]
                println("Found eval string: $evalString")
                
                // This is the packed array - split by pipe
                val parts = evalString.split('|')
                for (part in parts) {
                    if (part.length == 11 && part.matches(Regex("[a-zA-Z0-9_-]+"))) {
                        if (part !in listOf("fireplayer", "FirePlayer", "jwplayer8", "videojsSkin", 
                                        "beezPlayer", "youtubeApi", "no-referrer", "referrer", "canliplayer")) {
                            val youtubeUrl = "https://www.youtube.com/watch?v=$part"
                            println("Found YouTube ID in eval array: $part")
                            if (createYouTubeExtractorLink(youtubeUrl, playerUrl, callback)) {
                                return true
                            }
                        }
                    }
                }
            }

            // Method 4: Look for any 11-character YouTube-like ID in the entire HTML
            val allVideoIds = mutableSetOf<String>()
            val genericPattern = """(?<![a-zA-Z0-9])([a-zA-Z0-9_-]{11})(?![a-zA-Z0-9])"""
            Regex(genericPattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val potentialId = match.groupValues[1]
                if (potentialId.matches(Regex("[a-zA-Z0-9_-]+")) &&
                    potentialId !in listOf("fireplayer", "FirePlayer", "jwplayer8", "videojsSkin", 
                                        "beezPlayer", "youtubeApi", "no-referrer", "referrer", "canliplayer",
                                        "BrJZEEZFSL", "8PKzE75iRni", "CipZE5Sy66f", "d4G3Kw", "canliplayer")) {
                    allVideoIds.add(potentialId)
                }
            }

            // Try each potential video ID
            for (videoId in allVideoIds) {
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                println("Trying potential YouTube ID: $videoId")
                if (createYouTubeExtractorLink(youtubeUrl, playerUrl, callback)) {
                    return true
                }
            }

            println("No valid YouTube video found in CanliPlayer")

        } catch (e: Exception) {
            println("Error extracting from CanliPlayer $playerUrl: ${e.message}")
            e.printStackTrace()
        }
        
        return false
    }

    // ===== CREATE YOUTUBE EXTRACTOR LINK =====
    private suspend fun createYouTubeExtractorLink(
        youtubeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Validate the YouTube URL
            if (!youtubeUrl.contains("youtube.com/watch?v=") || youtubeUrl.length < 30) {
                println("Invalid YouTube URL: $youtubeUrl")
                return false
            }

            // Extract video ID for validation
            val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
            if (videoId.length != 11 || videoId == "no-referrer") {
                println("Invalid YouTube video ID: $videoId")
                return false
            }

            println("Creating YouTube extractor link: $youtubeUrl")
            
            // Create the extractor link
            callback.invoke(
                newExtractorLink(
                    "YouTube",
                    "YouTube",
                    youtubeUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer
                    )
                }
            )
            
            println("Successfully created YouTube extractor link for video: $videoId")
            return true
            
        } catch (e: Exception) {
            println("Error creating YouTube extractor link: ${e.message}")
            return false
        }
    }

    // ===== BETA PLAYER EXTRACTION =====
    private suspend fun extractFromBetaPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("Extracting from BetaPlayer: $playerUrl")
            
            val response = app.get(playerUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer
            ))

            val html = response.text

            // Look for YouTube URLs in BetaPlayer
            val youtubePatterns = listOf(
                """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
                """youtu\.be/([a-zA-Z0-9_-]{11})""",
                """source["']?\s*:\s*["']?(?:https?://)?(?:www\.)?youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
                """["'](https?://[^"']*youtube\.com/watch\?v=[^"']*)["']"""
            )

            for (pattern in youtubePatterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                    val youtubeUrl = if (pattern.startsWith("""["'](https?""")) {
                        fixUrl(match.groupValues[1])
                    } else {
                        val videoId = match.groupValues[1]
                        "https://www.youtube.com/watch?v=$videoId"
                    }
                    println("Found YouTube URL in BetaPlayer: $youtubeUrl")
                    if (createYouTubeExtractorLink(youtubeUrl, playerUrl, callback)) {
                        return true
                    }
                }
            }

            // Look for direct video URLs in BetaPlayer
            val directPatterns = listOf(
                """file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
                """source\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
                """(https?://[^"'\s]+\.(?:mp4|m3u8)(?:\?[^"'\s]*)?)"""
            )

            for (pattern in directPatterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                    val videoUrl = fixUrl(match.groupValues[1])
                    if (isVideoUrl(videoUrl)) {
                        println("Found direct video in BetaPlayer: $videoUrl")
                        createVideoLink(videoUrl, playerUrl, callback, "BetaPlayer Direct")
                        return true
                    }
                }
            }

        } catch (e: Exception) {
            println("Error extracting from BetaPlayer $playerUrl: ${e.message}")
        }
        
        return false
    }

    // ===== CREATE VIDEO LINK =====
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
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer
                    )
                }
            )
        } catch (e: Exception) {
            println("Error creating video link: ${e.message}")
        }
    }

    // ===== IMPROVED IFRAME EXTRACTION =====
    private suspend fun extractFromIframe(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            println("Extracting from iframe: $url")
            
            val response = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            ))

            val html = response.text

            // Look for YouTube URLs in iframe
            val youtubePatterns = listOf(
                """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
                """youtu\.be/([a-zA-Z0-9_-]{11})"""
            )

            for (pattern in youtubePatterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                    val videoId = match.groupValues[1]
                    val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                    println("Found YouTube URL in iframe: $youtubeUrl")
                    if (createYouTubeExtractorLink(youtubeUrl, url, callback)) {
                        return true
                    }
                }
            }

            // Look for direct video URLs
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
                        println("Found iframe video: $videoUrl")
                        createVideoLink(videoUrl, url, callback, "Iframe")
                        return true
                    }
                }
            }

        } catch (e: Exception) {
            println("Error extracting from iframe $url: ${e.message}")
        }
        
        return false
    }


    // ===== IMPROVED VIDEO URL DETECTION =====
    private fun isVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        
        val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mkv", ".avi", ".mov", ".flv")
        val videoKeywords = listOf("video", "stream", "m3u8", "mp4", "hls", "dash")
        
        val isVideo = (videoExtensions.any { url.contains(it, ignoreCase = true) } ||
                    videoKeywords.any { url.contains(it, ignoreCase = true) }) &&
                    !url.contains("data:image") &&
                    !url.contains("base64") &&
                    !url.contains("placeholder") &&
                    !url.contains("blank") &&
                    !url.contains("logo") &&
                    !url.contains("ads") &&
                    !url.contains("banner")
        
        if (isVideo) {
            println("Valid video URL: $url")
        }
        
        return isVideo
    }

    // ===== PARSER FUNCTIONS =====
    
    // Search result parser - SINGLE VERSION (remove duplicates)
    private fun parseSearchResultItem(element: Element): SearchResponse? {
        try {
            // Get link from cat-img > a
            val link = element.selectFirst("div.cat-img a")?.attr("href")?.let { fixUrl(it) } 
                ?: return null
            
            // Get title from categorytitle > a
            val title = element.selectFirst("div.categorytitle a")?.text()?.trim()
                ?: element.selectFirst("div.cat-img img")?.attr("alt")?.trim()
                    ?.replace("son bölüm izle", "")?.trim()
                    ?.replace("izle", "")?.trim()
                ?: element.selectFirst("div.cat-img img")?.attr("title")?.trim()
                    ?.replace("son bölüm izle", "")?.trim()
                    ?.replace("izle", "")?.trim()
                ?: return null

            // Handle lazy-loaded images
            val poster = element.selectFirst("div.cat-img img")?.let { img ->
                img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                    ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }

            // Extract year from dizimeta with "Yapım Yılı"
            val year = element.select("div.cat-container-in div").mapNotNull { div ->
                val text = div.text().trim()
                if (text.contains("Yapım Yılı")) {
                    Regex("""(\d{4})""").find(text)?.value?.toIntOrNull()
                } else {
                    null
                }
            }.firstOrNull()

            // Extract rating from imdbp div
            val scoreText = element.selectFirst("div.imdbp")?.text()?.trim()
            val score = scoreText?.let { text ->
                Regex("""IMDb:\s*([\d,]+)""").find(text)?.groupValues?.get(1)
                    ?.replace(",", ".")?.toFloatOrNull()?.times(10)?.toInt()
            }

            // Extract description from cat_ozet div
            val description = element.selectFirst("div.cat_ozet")?.text()?.trim()

            // Determine if it's a movie or series based on URL and content
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

    // Series item parser
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

    // Movie item parser
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
            // Extract score from rating element if available, otherwise don't set it
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
                val pageDocument = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
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
                val pageDocument = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
                
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

    private fun extractVideoUrlsFromJavaScript(html: String): List<String> {
        val videoUrls = mutableListOf<String>()
        
        val patterns = listOf(
            """(?:src|file|videoUrl|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv|avi|mov))["']""",
            """(?:url|source)\s*\(\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv|avi|mov))["']\s*\)""",
            """(?:hls|m3u8)Url\s*[:=]\s*["']([^"']+\.m3u8)["']""",
            """file:\s*["']([^"']+\.m3u8)["']"""
        )

        patterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                match.groupValues[1].let { fixUrl(it) }.takeIf { isVideoUrl(it) }?.let { videoUrls.add(it) }
            }
        }

        return videoUrls.distinct()
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

    // ===== UTILITY FUNCTIONS =====
    
    private fun String.encodeToUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
