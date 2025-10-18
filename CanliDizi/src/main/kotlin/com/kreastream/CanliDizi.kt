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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        val all = ArrayList<HomePageList>()
        
        // Parse popular series from the main slider
        val popularSeries = document.select("div.owl-item:not(.cloned) div.list-series, div.owl-item.active div.list-series").mapNotNull {
            parseSeriesItem(it)
        }

        if (popularSeries.isNotEmpty()) {
            all.add(HomePageList("Popular Series", popularSeries, isHorizontalImages = true))
        }

        // Parse latest episodes
        val latestEpisodes = document.select("div.episode-list .episode-box, .new-episodes .episode-item").mapNotNull {
            parseEpisodeItem(it)
        }

        if (latestEpisodes.isNotEmpty()) {
            all.add(HomePageList("Latest Episodes", latestEpisodes))
        }

        // Parse series list from other sections
        val seriesList = document.select("div.series-list .series-item, .dizi-list .dizi-item").mapNotNull {
            parseSeriesItem(it)
        }

        if (seriesList.isNotEmpty()) {
            all.add(HomePageList("All Series", seriesList))
        }
        
        return if (all.isEmpty()) null else newHomePageResponse(all)
    }

    private fun parseSeriesItem(element: Element): TvSeriesSearchResponse? {
        // Try multiple possible selectors for link
        val link = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } 
            ?: element.selectFirst(".serie-name a, .title a, h3 a, h4 a")?.attr("href")?.let { fixUrl(it) }
            ?: return null

        // Try multiple possible selectors for title
        val title = element.selectFirst(".serie-name, .title, h3, h4")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("title")?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        // Try multiple possible selectors for poster
        val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: element.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        // Try to extract year from various places
        val year = element.selectFirst(".episode-name, .year, .date")?.text()?.trim()?.toIntOrNull()
            ?: element.selectFirst(".serie-name")?.nextElementSibling()?.text()?.trim()?.toIntOrNull()

        // Try to extract rating
        val ratingText = element.selectFirst(".episode-date, .rating, .imdb")?.text()?.trim()
        val rating = ratingText?.let { text ->
            when {
                text.contains("IMDb") -> text.removePrefix("IMDb:").trim().replace(",", ".").toFloatOrNull()
                text.contains("/") -> text.substringBefore("/").trim().toFloatOrNull()
                else -> text.toFloatOrNull()
            }
        }

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(rating)
        }
    }

    private fun parseEpisodeItem(element: Element): TvSeriesSearchResponse? {
        val link = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val title = element.selectFirst(".serie-name, .title, .episode-title")?.text()?.trim() 
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: element.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        // Extract episode info from title or other elements
        val episodeInfo = element.selectFirst(".episode-name, .episode-number")?.text()?.trim()

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
            this.episode = parseEpisodeNumber(episodeInfo)
        }
    }

    private fun parseEpisodeNumber(text: String?): Int? {
        if (text == null) return null
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        // Try multiple possible selectors for title
        val title = document.selectFirst("h1.series-title, h1.title, h1.entry-title")?.text()?.trim() 
            ?: throw ErrorLoadingException("Title not found")
        
        // Try multiple possible selectors for poster
        val poster = document.selectFirst("img.poster, .poster img, .series-poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".wp-post-image, .attachment-post-thumbnail")?.attr("src")?.let { fixUrl(it) }

        // Try multiple possible selectors for description
        val description = document.selectFirst("div.description, .plot, .synopsis, .entry-content")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // Try to extract year
        val year = document.selectFirst(".year, .release-date")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        // Try to extract rating
        val ratingText = document.selectFirst(".rating, .imdb-rating, .score")?.text()?.trim()
        val rating = ratingText?.let { text ->
            when {
                text.contains("IMDb") -> text.removePrefix("IMDb:").trim().replace(",", ".").toFloatOrNull()
                text.contains("/") -> text.substringBefore("/").trim().toFloatOrNull()
                else -> text.toFloatOrNull()
            }
        }
        
        val episodes = document.select("div.episode-list div.episode, .episodes li, .season-episodes li").mapNotNull { element ->
            parseEpisode(element)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.score = Score.from10(rating)
        }
    }

    private fun parseEpisode(element: Element): Episode? {
        val epTitle = element.selectFirst("span.episode-title, .title, a")?.text()?.trim() ?: "Episode"
        val epUrl = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        
        // Try to extract episode number
        val epNum = element.selectFirst("span.episode-number, .episode-num, .number")?.text()?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        // Try to extract season number
        val season = element.selectFirst("span.season-number, .season-num")?.text()?.toIntOrNull()
            ?: 1

        val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newEpisode(epUrl) {
            this.name = epTitle
            this.episode = epNum
            this.season = season
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        // First, try to find direct video sources
        val videoElement = document.selectFirst("video source")
        if (videoElement != null) {
            val videoUrl = videoElement.attr("src")?.let { fixUrl(it) } ?: return false
            val quality = determineQuality(videoUrl)
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    "$name - Direct",
                    name,
                    videoUrl,
                    data,
                    quality,
                    type,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)
                )
            )
            return true
        }

        // If no direct video, look for iframes
        val iframes = document.select("iframe[data-wpfc-original-src], iframe[src]")
        var foundLinks = false

        for (iframe in iframes) {
            // Try data-wpfc-original-src first, then fall back to src
            val iframeSrc = iframe.attr("data-wpfc-original-src").ifBlank {
                iframe.attr("src")
            }
            
            if (iframeSrc.isNotBlank()) {
                val fixedIframeUrl = fixUrl(iframeSrc)
                foundLinks = foundLinks || extractFromIframe(fixedIframeUrl, callback, subtitleCallback)
            }
        }

        return foundLinks
    }

    private suspend fun extractFromIframe(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
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

            // Method 1: Direct video sources
            val videoSources = document.select("video source, video[src]")
            for (source in videoSources) {
                val videoUrl = source.attr("src").let { fixUrl(it) }
                if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
                    return createExtractorLink(videoUrl, url, callback, "$name - Direct")
                }
            }

            // Method 2: JavaScript variable extraction
            val jsVideoUrls = extractVideoUrlsFromJavaScript(html)
            for (videoUrl in jsVideoUrls) {
                if (isVideoUrl(videoUrl)) {
                    return createExtractorLink(videoUrl, url, callback, "$name - JS")
                }
            }

            // Method 3: Data attribute extraction
            val dataVideoUrls = document.select("[data-video-src], [data-src]").mapNotNull {
                it.attr("data-video-src").ifBlank { it.attr("data-src") }
            }
            for (videoUrl in dataVideoUrls) {
                val fixedUrl = fixUrl(videoUrl)
                if (isVideoUrl(fixedUrl)) {
                    return createExtractorLink(fixedUrl, url, callback, "$name - Data")
                }
            }

            // Method 4: Regex pattern matching in entire HTML
            val regexPatterns = listOf(
                """(?:src|file|video|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']""",
                """(?:url|source)\s*\(\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']\s*\)""",
                """["'](https?://[^"']+\.(?:mp4|m3u8|webm|mkv))["']""",
                """(https?://[^\s<>"']+\.(?:mp4|m3u8|webm|mkv))"""
            )

            for (pattern in regexPatterns) {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val matches = regex.findAll(html)
                for (match in matches) {
                    val videoUrl = fixUrl(match.groupValues[1])
                    if (isVideoUrl(videoUrl)) {
                        return createExtractorLink(videoUrl, url, callback, "$name - Regex")
                    }
                }
            }

            // Method 5: Check for common video hosting APIs
            if (url.contains("canliplayer.com")) {
                val apiMatch = Regex("""["']?(?:video|file)Id["']?\s*:\s*["']([^"']+)["']""").find(html)
                if (apiMatch != null) {
                    val videoId = apiMatch.groupValues[1]
                    // Try to construct direct URL (this might need adjustment based on the actual API)
                    val constructedUrl = "https://canliplayer.com/stream/$videoId"
                    return createExtractorLink(constructedUrl, url, callback, "$name - API")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun extractVideoUrlsFromJavaScript(html: String): List<String> {
        val videoUrls = mutableListOf<String>()
        
        // Common video URL patterns in JavaScript
        val patterns = listOf(
            """(?:src|file|videoUrl|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']""",
            """(?:url|source)\s*\(\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']\s*\)""",
            """(?:hls|m3u8)Url\s*[:=]\s*["']([^"']+\.m3u8)["']""",
            """(?:mp4|video)Url\s*[:=]\s*["']([^"']+\.mp4)["']""",
            """(?:file|src)\s*:\s*\[[^\]]*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["'][^\]]*\]"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            matches.forEach { match ->
                videoUrls.add(fixUrl(match.groupValues[1]))
            }
        }

        return videoUrls.distinct()
    }

    private fun isVideoUrl(url: String): Boolean {
        return url.contains(".mp4") || 
               url.contains(".m3u8") || 
               url.contains(".webm") || 
               url.contains(".mkv") ||
               url.contains("video") ||
               url.contains("stream")
    }

    private fun createExtractorLink(
        videoUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        sourceName: String
    ): Boolean {
        val quality = determineQuality(videoUrl)
        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            ExtractorLink(
                source = sourceName,
                name = name,
                url = videoUrl,
                referer = referer,
                quality = quality,
                type = type,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            )
        )
        return true
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

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/search?q=${query.encodeToUrl()}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        return document.select("div.search-results div.result, div.series-item, .dizi-item").mapNotNull { element ->
            parseSeriesItem(element)
        }
    }

    // Helper function for URL encoding
    private fun String.encodeToUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}