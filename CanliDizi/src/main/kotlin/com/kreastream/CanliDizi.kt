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
            this.score = Score.from10(rating)
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
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = mainUrl,
                    quality = quality,
                    type = type,
                    headers = mapOf("User-Agent" to USER_AGENT)
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
                
                // Handle different iframe hosts
                when {
                    fixedIframeUrl.contains("betaplayer.site") -> {
                        foundLinks = foundLinks || handleBetaPlayer(fixedIframeUrl, callback, subtitleCallback)
                    }
                    fixedIframeUrl.contains("canliplayer.com") -> {
                        foundLinks = foundLinks || handleCanliPlayer(fixedIframeUrl, callback, subtitleCallback)
                    }
                    else -> {
                        // Generic iframe handling
                        foundLinks = foundLinks || handleGenericIframe(fixedIframeUrl, callback, subtitleCallback)
                    }
                }
            }
        }

        return foundLinks
    }

    private suspend fun handleBetaPlayer(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            val document = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )).document

            // Look for video sources in the iframe
            val videoSources = document.select("video source")
            for (source in videoSources) {
                val videoUrl = source.attr("src").let { fixUrl(it) }
                if (videoUrl.isNotBlank()) {
                    val quality = determineQuality(videoUrl)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        ExtractorLink(
                            source = "$name - BetaPlayer",
                            name = name,
                            url = videoUrl,
                            referer = url,
                            quality = quality,
                            type = type,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to url
                            )
                        )
                    )
                    return true
                }
            }

            // Look for JavaScript variables that might contain video URLs
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                // Look for common video URL patterns in JavaScript
                val videoPatterns = listOf(
                    """src\s*[:=]\s*["']([^"']+\.(mp4|m3u8|webm|mkv))["']""",
                    """file\s*[:=]\s*["']([^"']+\.(mp4|m3u8|webm|mkv))["']""",
                    """video\s*[:=]\s*["']([^"']+\.(mp4|m3u8|webm|mkv))["']"""
                )
                
                for (pattern in videoPatterns) {
                    val regex = Regex(pattern)
                    val match = regex.find(scriptContent)
                    if (match != null) {
                        val videoUrl = fixUrl(match.groupValues[1])
                        val quality = determineQuality(videoUrl)
                        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(
                            ExtractorLink(
                                source = "$name - BetaPlayer",
                                name = name,
                                url = videoUrl,
                                referer = url,
                                quality = quality,
                                type = type,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to url
                                )
                            )
                        )
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private suspend fun handleCanliPlayer(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            val document = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )).document

            // Look for video sources
            val videoSources = document.select("video source, video[src]")
            for (source in videoSources) {
                val videoUrl = source.attr("src").let { fixUrl(it) }
                if (videoUrl.isNotBlank()) {
                    val quality = determineQuality(videoUrl)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        ExtractorLink(
                            source = "$name - CanliPlayer",
                            name = name,
                            url = videoUrl,
                            referer = url,
                            quality = quality,
                            type = type,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to url
                            )
                        )
                    )
                    return true
                }
            }

            // Extract from JavaScript
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                // Look for video URLs in JavaScript
                val patterns = listOf(
                    """["'](https?://[^"']+\.(mp4|m3u8|webm|mkv))["']""",
                    """(https?://[^\s<>"']+\.(mp4|m3u8|webm|mkv))"""
                )
                
                for (pattern in patterns) {
                    val regex = Regex(pattern)
                    val matches = regex.findAll(scriptContent)
                    for (match in matches) {
                        val videoUrl = fixUrl(match.groupValues[1])
                        if (videoUrl.contains("video") || videoUrl.contains("stream")) {
                            val quality = determineQuality(videoUrl)
                            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                            callback.invoke(
                                ExtractorLink(
                                    source = "$name - CanliPlayer",
                                    name = name,
                                    url = videoUrl,
                                    referer = url,
                                    quality = quality,
                                    type = type,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to url
                                    )
                                )
                            )
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private suspend fun handleGenericIframe(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            val document = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )).document

            // Look for any video sources
            val videoSources = document.select("video source, video[src]")
            for (source in videoSources) {
                val videoUrl = source.attr("src").let { fixUrl(it) }
                if (videoUrl.isNotBlank()) {
                    val quality = determineQuality(videoUrl)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        ExtractorLink(
                            source = "$name - Iframe",
                            name = name,
                            url = videoUrl,
                            referer = url,
                            quality = quality,
                            type = type,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to url
                            )
                        )
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
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