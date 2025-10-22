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
            if (extractFromCanliPlayerDirect(playerUrl, data, callback)) {
                foundLinks = true
                return@forEach
            }
        }
    }

    // Method 2: Look for script tags containing video configurations
    document.select("script").forEach { script ->
        val scriptContent = script.html()
        if (scriptContent.contains("canliplayer") || scriptContent.contains("fireplayer")) {
            println("Found script with canliplayer reference")
            
            // Try to extract direct video URL from script
            val videoPatterns = listOf(
                """file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
                """source\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
                """src\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
                """(https?://[^"'\s]+\.(?:mp4|m3u8)(?:\?[^"'\s]*)?)""",
                """videoUrl\s*:\s*["'](https?://[^"']+)["']""",
                """url\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']"""
            )
            
            videoPatterns.forEach { pattern ->
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(scriptContent).forEach { match ->
                    val videoUrl = fixUrl(match.groupValues[1])
                    if (isVideoUrl(videoUrl)) {
                        println("Found direct video in script: $videoUrl")
                        createVideoLink(videoUrl, data, callback, "Script Direct")
                        foundLinks = true
                        return@forEach
                    }
                }
            }
        }
    }

    // Method 3: Look for iframe embeds
    document.select("iframe[src]").forEach { iframe ->
        val iframeSrc = iframe.attr("src")
        if (iframeSrc.isNotBlank()) {
            val fixedUrl = fixUrl(iframeSrc)
            println("Found iframe: $fixedUrl")
            if (extractFromIframe(fixedUrl, callback, subtitleCallback)) {
                foundLinks = true
            }
        }
    }

    // Method 4: Look for data attributes that might contain video URLs
    document.select("[data-src], [data-file], [data-video], [data-url]").forEach { element ->
        listOf("data-src", "data-file", "data-video", "data-url").forEach { attr ->
            element.attr(attr).takeIf { it.isNotBlank() }?.let { url ->
                val fixedUrl = fixUrl(url)
                if (isVideoUrl(fixedUrl)) {
                    println("Found data attribute video: $fixedUrl")
                    createVideoLink(fixedUrl, data, callback, "Data Attribute")
                    foundLinks = true
                }
            }
        }
    }

    // Method 5: Direct video elements (fallback)
    if (!foundLinks) {
        document.select("video source, video[src]").forEach { source ->
            val videoUrl = source.attr("src").let { fixUrl(it) }
            if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
                println("Found direct video element: $videoUrl")
                createVideoLink(videoUrl, data, callback, "Video Element")
                foundLinks = true
            }
        }
    }

    if (!foundLinks) {
        println("No video links found after all extraction methods")
        // Try one more aggressive approach - look for any m3u8 or mp4 in the entire HTML
        val aggressivePatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """["'](https?://[^"']+\.m3u8)["']""",
            """["'](https?://[^"']+\.mp4)["']"""
        )
        
        aggressivePatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl) && !videoUrl.contains("placeholder")) {
                    println("Found aggressive pattern video: $videoUrl")
                    createVideoLink(videoUrl, data, callback, "Aggressive Pattern")
                    foundLinks = true
                    return@forEach
                }
            }
        }
    }

    return foundLinks
}

// ===== DIRECT CANLIPLAYER EXTRACTION =====
private suspend fun extractFromCanliPlayerDirect(
    playerUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        println("Extracting directly from CanliPlayer: $playerUrl")
        
        val response = app.get(playerUrl, headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        ))

        val document = response.document
        val html = response.text

        println("CanliPlayer page loaded, analyzing structure...")

        // Method 1: Look for video elements directly
        document.select("video source, video[src]").forEach { source ->
            val videoUrl = source.attr("src").let { fixUrl(it) }
            if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
                println("Found CanliPlayer direct video element: $videoUrl")
                createVideoLink(videoUrl, playerUrl, callback, "CanliPlayer Direct")
                return true
            }
        }

        // Method 2: Look for JavaScript variables containing video URLs
        val jsPatterns = listOf(
            """file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
            """source\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
            """src\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
            """videoUrl\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
            """url\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
            """hlsUrl\s*:\s*["'](https?://[^"']+\.m3u8)["']""",
            """\.setup\([^)]+\)[^{]*\{[^}]*file\s*:\s*["'](https?://[^"']+)["']""",
            """jwplayer\([^)]+\)\.setup\([^)]*file\s*:\s*["']([^"']+)["']"""
        )

        jsPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found CanliPlayer JS video: $videoUrl")
                    createVideoLink(videoUrl, playerUrl, callback, "CanliPlayer JS")
                    return true
                }
            }
        }

        // Method 3: Look for data attributes
        document.select("[data-file], [data-src], [data-url]").forEach { element ->
            listOf("data-file", "data-src", "data-url").forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { url ->
                    val videoUrl = fixUrl(url)
                    if (isVideoUrl(videoUrl)) {
                        println("Found CanliPlayer data attribute video: $videoUrl")
                        createVideoLink(videoUrl, playerUrl, callback, "CanliPlayer Data")
                        return true
                    }
                }
            }
        }

        // Method 4: Look for common video hosting patterns in CanliPlayer
        val hostingPatterns = listOf(
            """(https?://[^"'\s]+\.(?:mp4|m3u8)(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+/videos/[^"'\s]+\.(?:mp4|m3u8))""",
            """(https?://[^"'\s]+/stream/[^"'\s]+\.(?:mp4|m3u8))""",
            """(https?://[^"'\s]+/hls/[^"'\s]+\.m3u8)"""
        )

        hostingPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl) && !videoUrl.contains("placeholder")) {
                    println("Found CanliPlayer hosting pattern video: $videoUrl")
                    createVideoLink(videoUrl, playerUrl, callback, "CanliPlayer Hosting")
                    return true
                }
            }
        }

        // Method 5: Try to find iframe within CanliPlayer
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            if (iframeSrc != null) {
                println("Found iframe in CanliPlayer: $iframeSrc")
                if (extractFromIframe(iframeSrc, callback) {}) {
                    return true
                }
            }
        }

        println("No video found in CanliPlayer after all methods")

    } catch (e: Exception) {
        println("Error extracting from CanliPlayer $playerUrl: ${e.message}")
        e.printStackTrace()
    }
    
    return false
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
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        ))

        val html = response.text

        // Look for direct video URLs with multiple patterns
        val videoPatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """file\s*:\s*["'](https?://[^"']+)["']""",
            """source\s*:\s*["'](https?://[^"']+)["']""",
            """src\s*:\s*["'](https?://[^"']+)["']""",
            """<source[^>]+src\s*=\s*["'](https?://[^"']+)["']""",
            """video[^>]+src\s*=\s*["'](https?://[^"']+)["']"""
        )

        videoPatterns.forEach { pattern ->
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
                    "Referer" to referer,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Origin" to mainUrl.split("/").take(3).joinToString("/")
                )
            }
        )
    } catch (e: Exception) {
        println("Error creating video link: ${e.message}")
    }
}

// ===== IMPROVED VIDEO URL DETECTION =====
private fun isVideoUrl(url: String): Boolean {
    if (url.isBlank()) return false
    
    val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mkv", ".avi", ".mov", ".flv")
    val videoKeywords = listOf("video", "stream", "m3u8", "mp4", "hls", "dash", "fireplayer")
    
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

// ===== SPECIFIC PLAYER EXTRACTORS =====

private suspend fun extractFromCanliPlayer(
    html: String,
    playerUrl: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("Extracting from CanliPlayer")
    
    val patterns = listOf(
        """file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
        """source\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
        """src\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8))["']""",
        """(https?://[^"'\s]+\.fireplayer\.com[^"'\s]*\.(?:mp4|m3u8))""",
        """(https?://[^"'\s]+/video/[^"'\s]+\.(?:mp4|m3u8))"""
    )

    patterns.forEach { pattern ->
        Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val videoUrl = fixUrl(match.groupValues[1])
            if (isVideoUrl(videoUrl)) {
                println("Found CanliPlayer video: $videoUrl")
                createVideoLink(videoUrl, playerUrl, callback, "CanliPlayer")
                return true
            }
        }
    }
    
    return false
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
