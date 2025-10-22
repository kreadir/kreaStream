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
        
        // Try CanliPlayer extraction first (most common)
        if (extractCanliPlayerLinks(html, data, callback)) {
            println("CanliPlayer extractor found links!")
            return true
        }
        
        // Try YouTube extraction
        if (extractYouTubeLinks(html, data, callback)) {
            println("YouTube extractor found links!")
            return true
        }
        
        // Try BetaPlayer extraction
        if (extractBetaPlayerLinks(html, data, callback)) {
            println("BetaPlayer extractor found links!")
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
                    
                    // Try YouTube extraction first
                    if (extractYouTubeLinks(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Then try direct videos
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
    
    // ===== IMPROVED CANLIPLAYER EXTRACTION =====
    
    private suspend fun extractFromPackedJavaScript(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Looking for packed JavaScript patterns...")
        
        // Method 1: Look for base64 encoded URLs in the packed JavaScript
        // Pattern: "12":"base64string==" (like "12":"10==" in the example)
        val base64Pattern = """"12"\s*:\s*"([A-Za-z0-9+/=]+)""""
        Regex(base64Pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val base64String = match.groupValues[1]
            println("Found base64 string: $base64String")
            
            try {
                val decodedBytes = Base64.getDecoder().decode(base64String)
                val decodedString = String(decodedBytes)
                println("Decoded base64: $decodedString")
                
                if (isVideoUrl(decodedString)) {
                    println("Found video URL in base64: $decodedString")
                    createVideoLink(decodedString, referer, callback, "CanliPlayer Base64")
                    return true
                }
            } catch (e: Exception) {
                println("Failed to decode base64: ${e.message}")
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
                        try {
                            val decodedBytes = Base64.getDecoder().decode(part)
                            val decodedString = String(decodedBytes)
                            if (isVideoUrl(decodedString)) {
                                println("Found video URL in packed base64: $decodedString")
                                createVideoLink(decodedString, referer, callback, "CanliPlayer Packed")
                                return true
                            }
                        } catch (e: Exception) {
                            // Not a valid base64, continue
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
            ?: document.SelectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

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
        val epUrl = element.attr("
