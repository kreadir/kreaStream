package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries)
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
        
        // Parse the series slider
        val series = document.select("div.owl-item:not(.cloned) div.list-series").mapNotNull {
            parseSeriesItem(it)
        }

        if (series.isNotEmpty()) {
            all.add(HomePageList("Popular Series", series, isHorizontalImages = true))
        }
        
        return newHomePageResponse(all)
    }

    private fun parseSeriesItem(element: Element): TvSeriesSearchResponse? {
        val link = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val title = element.selectFirst(".serie-name a")?.text()?.trim() ?: return null
        val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = element.selectFirst(".episode-name")?.text()?.trim()?.toIntOrNull()
        val rating = element.selectFirst(".episode-date")?.text()
            ?.removePrefix("IMDb:")
            ?.trim()
            ?.replace(",", ".")
            ?.toFloatOrNull()
        
        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
            this.rating = rating
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        val title = document.selectFirst("h1.series-title")?.text()?.trim() 
            ?: throw ErrorLoadingException("Title not found")
        val poster = document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }
        val description = document.selectFirst("div.description")?.text()?.trim()
        
        val episodes = document.select("div.episode-list div.episode").mapNotNull { element ->
            parseEpisode(element)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun parseEpisode(element: Element): Episode? {
        val epTitle = element.selectFirst("span.episode-title")?.text()?.trim() ?: "Episode"
        val epUrl = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val epNum = element.selectFirst("span.episode-number")?.text()?.toIntOrNull() ?: 1
        val season = element.selectFirst("span.season-number")?.text()?.toIntOrNull() ?: 1
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
            val isM3u8 = videoUrl.contains(".m3u8")

            callback.invoke(
                newExtractorLink(
                    "$name - Direct",
                    name,
                    videoUrl,
                    data,
                    quality,
                    isM3u8,
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
        val isM3u8 = videoUrl.contains(".m3u8")

        callback.invoke(
            newExtractorLink(
                sourceName,
                name,
                videoUrl,
                referer,
                quality,
                isM3u8,
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
        
        return document.select("div.search-results div.result, div.series-item").mapNotNull { element ->
            parseSeriesItem(element)
        }
    }

    // Helper function for URL encoding
    private fun String.encodeToUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}