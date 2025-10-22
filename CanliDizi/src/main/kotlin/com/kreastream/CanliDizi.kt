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

        // Method 1: Look for embedded video players (canliplayer.com, betaplayer.site, etc.)
        val embeddedPlayerPatterns = listOf(
            """(https?://[^"'\s]*canliplayer\.com[^"'\s]*)""",
            """(https?://[^"'\s]*betaplayer\.site[^"'\s]*)""",
            """(https?://[^"'\s]*player\.[^"'\s]*)""",
            """(https?://[^"'\s]*embed\.?[^"'\s]*)""",
            """(https?://[^"'\s]*video\.?[^"'\s]*)""",
            """src=["'](https?://[^"']*/(?:embed|video|player)/[^"']*)["']"""
        )

        embeddedPlayerPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                if (extractFromExternalPlayer(playerUrl, data, callback, subtitleCallback)) {
                    foundLinks = true
                }
            }
        }

        // Method 2: Direct video elements
        document.select("video source, video[src], audio source, audio[src]").forEach { videoElement ->
            videoElement.attr("src").let { fixUrl(it) }.takeIf { it.isNotBlank() && isVideoUrl(it) }?.let { videoUrl ->
                createVideoLink(videoUrl, data, callback, "Direct Video")
                foundLinks = true
            }
        }

        // Method 3: Iframe extraction
        document.select("iframe[src], embed[src]").forEach { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() }?.let { iframeSrc ->
                val fixedIframeUrl = fixUrl(iframeSrc)
                if (extractFromIframe(fixedIframeUrl, callback, subtitleCallback)) {
                    foundLinks = true
                }
            }
        }

        // Method 4: Data attributes
        document.select("[data-video-src], [data-src], [data-file], [data-url]").forEach { element ->
            listOf("data-video-src", "data-src", "data-file", "data-url").forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { fixUrl(it) }?.let { videoUrl ->
                    if (isVideoUrl(videoUrl)) {
                        createVideoLink(videoUrl, data, callback, "Data Attribute")
                        foundLinks = true
                    }
                }
            }
        }

        // Method 5: JavaScript extraction
        document.select("script").forEach { script ->
            extractVideoUrlsFromJavaScript(script.html()).forEach { videoUrl ->
                if (isVideoUrl(videoUrl)) {
                    createVideoLink(videoUrl, data, callback, "JavaScript")
                    foundLinks = true
                }
            }
        }

        // Method 6: Regex patterns for direct video files
        val regexPatterns = listOf(
            """(?:src|file|videoUrl|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv|avi|mov))["']""",
            """(?:url|source)\s*\(\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv|avi|mov))["']\s*\)""",
            """["'](https?://[^"']+\.(?:mp4|m3u8|webm|mkv|avi|mov))["']""",
            """file:\s*["']([^"']+\.m3u8)["']""",
            """hlsUrl\s*[:=]\s*["']([^"']+\.m3u8)["']"""
        )

        regexPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                match.groupValues[1].let { fixUrl(it) }.takeIf { isVideoUrl(it) }?.let { videoUrl ->
                    createVideoLink(videoUrl, data, callback, "Regex")
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    // ===== EXTERNAL PLAYER EXTRACTION =====
    private suspend fun extractFromExternalPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            println("Extracting from external player: $playerUrl")
            
            val response = app.get(playerUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept-Encoding" to "gzip, deflate, br",
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            ))

            val document = response.document
            val html = response.text

            // Try to extract direct video URLs from the player page
            val videoUrls = mutableListOf<String>()

            // Look for direct video sources
            document.select("video source, video[src]").forEach { source ->
                source.attr("src").takeIf { it.isNotBlank() }?.let { videoUrls.add(fixUrl(it)) }
            }

            // Look for video URLs in data attributes
            document.select("[data-src], [data-file], [data-video]").forEach { element ->
                listOf("data-src", "data-file", "data-video").forEach { attr ->
                    element.attr(attr).takeIf { it.isNotBlank() }?.let { videoUrls.add(fixUrl(it)) }
                }
            }

            // Extract from JavaScript
            videoUrls.addAll(extractVideoUrlsFromJavaScript(html))

            // Special handling for specific players
            when {
                playerUrl.contains("canliplayer.com") -> {
                    // Extract from canliplayer.com
                    val canliPatterns = listOf(
                        """file:\s*["']([^"']+\.(?:mp4|m3u8))["']""",
                        """source\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']""",
                        """(https?://[^"'\s]+\.(?:mp4|m3u8))"""
                    )
                    
                    canliPatterns.forEach { pattern ->
                        Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                            match.groupValues[1].let { fixUrl(it) }?.let { videoUrls.add(it) }
                        }
                    }
                }
                
                playerUrl.contains("betaplayer.site") -> {
                    // Extract from betaplayer.site
                    val betaPatterns = listOf(
                        """(https?://[^"'\s]+\.(?:mp4|m3u8))""",
                        """file\s*=\s*["']([^"']+)["']""",
                        """src\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']"""
                    )
                    
                    betaPatterns.forEach { pattern ->
                        Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                            match.groupValues[1].let { fixUrl(it) }?.let { videoUrls.add(it) }
                        }
                    }
                }
            }

            // Create links for found video URLs
            videoUrls.distinct().forEach { videoUrl ->
                if (isVideoUrl(videoUrl)) {
                    createVideoLink(videoUrl, playerUrl, callback, "External Player")
                    return true
                }
            }

            // If no direct video found, try to extract from iframes within the player
            document.select("iframe[src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                if (iframeSrc != null && extractFromIframe(iframeSrc, callback, subtitleCallback)) {
                    return true
                }
            }

        } catch (e: Exception) {
            println("Error extracting from external player $playerUrl: ${e.message}")
            e.printStackTrace()
        }
        
        return false
    }

    // ===== IFRAME EXTRACTION (UPDATED) =====
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

            val document = response.document
            val html = response.text

            // Look for direct video sources in the iframe
            document.select("video source, video[src]").forEach { source ->
                val videoUrl = source.attr("src").let { fixUrl(it) }
                if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
                    createVideoLink(videoUrl, url, callback, "Iframe Direct")
                    return true
                }
            }

            // Look for video URLs in data attributes
            document.select("[data-src], [data-file], [data-video]").forEach { element ->
                listOf("data-src", "data-file", "data-video").forEach { attr ->
                    element.attr(attr).takeIf { it.isNotBlank() }?.let { videoUrl ->
                        if (isVideoUrl(videoUrl)) {
                            createVideoLink(fixUrl(videoUrl), url, callback, "Iframe Data Attribute")
                            return true
                        }
                    }
                }
            }

            // Extract from JavaScript in iframe
            extractVideoUrlsFromJavaScript(html).forEach { videoUrl ->
                if (isVideoUrl(videoUrl)) {
                    createVideoLink(videoUrl, url, callback, "Iframe JavaScript")
                    return true
                }
            }

            // Try to find embedded players within the iframe
            val embeddedPatterns = listOf(
                """(https?://[^"'\s]*\.(?:mp4|m3u8))""",
                """file\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']""",
                """source\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']"""
            )

            embeddedPatterns.forEach { pattern ->
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                    val videoUrl = fixUrl(match.groupValues[1])
                    if (isVideoUrl(videoUrl)) {
                        createVideoLink(videoUrl, url, callback, "Iframe Embedded")
                        return true
                    }
                }
            }

        } catch (e: Exception) {
            println("Error extracting from iframe $url: ${e.message}")
            e.printStackTrace()
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

            println("Found video: $videoUrl (Quality: $quality, Type: $type)")

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

    private fun isVideoUrl(url: String): Boolean {
        return (url.contains(".mp4") || 
            url.contains(".m3u8") || 
            url.contains(".webm") || 
            url.contains("video") ||
            url.contains("stream")) &&
            !url.contains("data:image") &&
            !url.contains("base64") &&
            !url.contains("placeholder")
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
