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
        val all = ArrayList<HomePageList>()
        
        // Get Yerli Diziler from dedicated page with smart pagination
        try {
            val yerliDiziler = mutableListOf<TvSeriesSearchResponse>()
            
            // Parse first page to get pagination info
            val firstPageUrl = "$mainUrl/diziler"
            val firstPageDocument = app.get(firstPageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
            
            // Get all page numbers from pagination
            val pageLinks = firstPageDocument.select("div.paginate-links a.page-numbers, div.paginate-links span.page-numbers")
            val pageNumbers = mutableSetOf<Int>()
            
            // Add page 1
            pageNumbers.add(1)
            
            // Extract page numbers from links
            pageLinks.forEach { element ->
                when {
                    element.attr("href").contains("/page/") -> {
                        val pageNum = Regex("""/page/(\d+)""").find(element.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                        pageNum?.let { pageNumbers.add(it) }
                    }
                    element.hasClass("current") -> {
                        val pageNum = element.text().toIntOrNull()
                        pageNum?.let { pageNumbers.add(it) }
                    }
                }
            }
            
            // If no pagination found, just use first page
            val pagesToParse = if (pageNumbers.isNotEmpty()) pageNumbers.sorted() else listOf(1)
            
            println("Found pages: $pagesToParse")
            
            // Parse all detected pages
            for (pageNum in pagesToParse) {
                val pageUrl = if (pageNum == 1) firstPageUrl else "$mainUrl/diziler/page/$pageNum"
                try {
                    val pageDocument = if (pageNum == 1) firstPageDocument 
                        else app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
                    
                    val pageItems = pageDocument.select("div.seriescontent div.single-item").mapNotNull {
                        parseDizilerItem(it)
                    }
                    
                    yerliDiziler.addAll(pageItems)
                    println("Parsed ${pageItems.size} items from page $pageNum")
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Continue with next page even if one fails
                    continue
                }
            }
            
            if (yerliDiziler.isNotEmpty()) {
                all.add(HomePageList("Yerli Diziler (${pagesToParse.size} Sayfa)", yerliDiziler))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return if (all.isEmpty()) null else newHomePageResponse(all)
    }

    private fun parseDizilerItem(element: Element): TvSeriesSearchResponse? {
        // Get link from cat-img > a
        val link = element.selectFirst("div.cat-img a")?.attr("href")?.let { fixUrl(it) } ?: return null
        
        // Get title from categorytitle > a
        val title = element.selectFirst("div.categorytitle a")?.text()?.trim()
            ?: element.selectFirst("div.cat-img img")?.attr("alt")?.trim()
            ?: element.selectFirst("div.cat-img img")?.attr("title")?.trim()
            ?: return null

        // Handle lazy-loaded images with data-wpfc-original-src
        val poster = element.selectFirst("div.cat-img img")?.let { img ->
            img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        // Extract year from the dizimeta sections - look for "Yapım Yılı"
        val year = element.select("div.cat-container-in div").mapNotNull { div ->
            val text = div.text().trim()
            if (text.contains("Yapım Yılı")) {
                Regex("""(\d{4})""").find(text)?.value?.toIntOrNull()
            } else {
                null
            }
        }.firstOrNull()

        // Extract rating from imdbp div
        val ratingText = element.selectFirst("div.imdbp")?.text()?.trim()
        val score = ratingText?.let { text ->
            when {
                text.contains("IMDb") -> {
                    val ratingValue = Regex("""IMDb:\s*([\d,]+)""").find(text)?.groupValues?.get(1)
                        ?.replace(",", ".")?.toFloatOrNull()
                    ratingValue?.times(10)?.toInt()
                }
                else -> null
            }
        }

        // Extract description from cat_ozet div
        val description = element.selectFirst("div.cat_ozet")?.text()?.trim()

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    // Keep the old parseSeriesItem for other pages that might use the old structure
    private fun parseSeriesItem(element: Element): TvSeriesSearchResponse? {
        val link = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val title = element.selectFirst(".serie-name a, .serie-name")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("title")?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        // Handle lazy-loaded images with data-wpfc-original-src
        val poster = element.selectFirst("img")?.let { img ->
            img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        // Extract year from episode-name
        val year = element.selectFirst(".episode-name")?.text()?.trim()?.toIntOrNull()

        // Extract rating from episode-date
        val ratingText = element.selectFirst(".episode-date")?.text()?.trim()
        val score = ratingText?.let { text ->
            when {
                text.contains("IMDb") -> {
                    val ratingValue = text.removePrefix("IMDb:").trim().replace(",", ".").toFloatOrNull()
                    ratingValue?.times(10)?.toInt()
                }
                else -> null
            }
        }

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    private fun parseEpisodeItem(element: Element): TvSeriesSearchResponse? {
        val link = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val title = element.selectFirst(".serie-name")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        // Handle lazy-loaded images with data-wpfc-original-src
        val poster = element.selectFirst("img")?.let { img ->
            img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

        override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        // Check if this is the new series page structure with incontentx
        val isNewStructure = document.selectFirst("div.incontentx") != null
        
        if (isNewStructure) {
            return loadNewSeriesStructure(document, url)
        } else {
            return loadOldStructure(document, url)
        }
    }

    private suspend fun loadNewSeriesStructure(document: Element, url: String): LoadResponse {
        // Extract title from h1.title-border
        val title = document.selectFirst("h1.title-border")?.text()?.trim()
            ?.replace("son bölüm izle", "")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        
        // Extract poster from cat-img img
        val poster = document.selectFirst("div.cat-img img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        // Extract description from cat_ozet
        val description = document.selectFirst("div.cat_ozet")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Extract year from dizimeta with "Yapım Yılı"
        val year = document.select("div.cat-container-in div").mapNotNull { div ->
            val text = div.text().trim()
            if (text.contains("Yapım Yılı")) {
                Regex("""(\d{4})""").find(text)?.value?.toIntOrNull()
            } else {
                null
            }
        }.firstOrNull()

        // Extract rating from dizimeta with "IMDB"
        val ratingText = document.select("div.cat-container-in div").mapNotNull { div ->
            val text = div.text().trim()
            if (text.contains("IMDB")) {
                Regex("""IMDB\s*:\s*([\d,]+)""").find(text)?.groupValues?.get(1)
            } else {
                null
            }
        }.firstOrNull()
        
        val score = ratingText?.replace(",", ".")?.toFloatOrNull()?.times(10)?.toInt()

        // Parse episodes from the new structure
        val episodes = document.select("div.bolumust a").mapNotNull { element ->
            parseNewEpisodeItem(element)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.score = Score.from10(score)
        }
    }

    private fun parseNewEpisodeItem(element: Element): Episode? {
        val epUrl = element.attr("href")?.let { fixUrl(it) } ?: return null
        
        // Extract episode title from baslik div or title attribute
        val epTitle = element.selectFirst("div.baslik")?.text()?.trim()
            ?: element.attr("title")?.trim()
            ?: "Episode"

        // Extract episode number from title
        val epNum = Regex("""(\d+)\.?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        // Extract release date from tarih div
        //val releaseDate = element.selectFirst("div.tarih")?.text()?.trim()

        return newEpisode(epUrl) {
            this.name = epTitle
            this.episode = epNum
            this.season = 1 // Default to season 1 since it's not specified in the HTML
            //this.year = releaseDate
        }
    }

    private suspend fun loadOldStructure(document: Element, url: String): LoadResponse {
        // Try multiple possible selectors for title
        val title = document.selectFirst("h1.series-title, h1.title, h1.entry-title, h1")?.text()?.trim() 
            ?: document.selectFirst(".series-name, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        
        // Try multiple possible selectors for poster
        val poster = document.selectFirst("img.poster, .poster img, .series-poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".wp-post-image, .attachment-post-thumbnail")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img[data-wpfc-original-src]")?.attr("data-wpfc-original-src")?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        // Try multiple possible selectors for description
        val description = document.selectFirst("div.description, .plot, .synopsis, .entry-content")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Try to extract year
        val year = document.selectFirst(".year, .release-date")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        // Try to extract rating and convert to score
        val ratingText = document.selectFirst(".rating, .imdb-rating, .score")?.text()?.trim()
        val score = ratingText?.let { text ->
            when {
                text.contains("IMDb") -> {
                    val ratingValue = text.removePrefix("IMDb:").trim().replace(",", ".").toFloatOrNull()
                    ratingValue?.times(10)?.toInt()
                }
                text.contains("/") -> {
                    val ratingValue = text.substringBefore("/").trim().toFloatOrNull()
                    ratingValue?.times(10)?.toInt()
                }
                else -> text.toFloatOrNull()?.times(10)?.toInt()
            }
        }
        
        // Better detection for movie vs series
        val isMovie = url.contains("/film") || 
                    url.contains("/izle.html") && 
                    !url.contains("-bolum-") &&
                    !url.contains("/kategori/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(score)
            }
        } else {
            // For series, try to parse episodes from multiple possible locations
            val episodes = mutableListOf<Episode>()
            
            // Method 1: Check if this is a series category page that lists episodes
            val categoryEpisodes = document.select("div.episodes.episode div.list-episodes div.episode-box, div.episode-list div.episode, .season-episodes li").mapNotNull { element ->
                parseEpisode(element)
            }
            episodes.addAll(categoryEpisodes)
            
            // Method 2: If no episodes found in category, check if this is a single episode page
            if (episodes.isEmpty() && (url.contains("-bolum-") || url.contains("/bolum-"))) {
                // This is likely a single episode page, create one episode
                val episode = parseSingleEpisode(document, url, title)
                if (episode != null) {
                    episodes.add(episode)
                }
            }
            
            // Method 3: Check for video player directly on the page (for single episodes)
            if (episodes.isEmpty()) {
                val hasVideoPlayer = document.select("video, iframe[src*='video'], iframe[data-wpfc-original-src*='video']").isNotEmpty()
                if (hasVideoPlayer) {
                    val episode = parseSingleEpisode(document, url, title)
                    if (episode != null) {
                        episodes.add(episode)
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    private fun parseSingleEpisode(document: Element, url: String, seriesTitle: String): Episode? {
        // Extract episode number from URL or title
        val epNum = Regex("""(\d+)\.?Bölüm""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        // Try to extract season number
        val season = Regex("""(\d+)\.?Sezon""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        // Get episode title
        val epTitle = document.selectFirst("h1, h2, .episode-title, .entry-title")?.text()?.trim()
            ?: "$seriesTitle Bölüm $epNum"

        return newEpisode(url) {
            this.name = epTitle
            this.episode = epNum
            this.season = season
            this.posterUrl = document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                ?: document.selectFirst("img[data-wpfc-original-src]")?.attr("data-wpfc-original-src")?.let { fixUrl(it) }
        }
    }

    private fun parseEpisode(element: Element): Episode? {
        val epTitle = element.selectFirst("span.episode-title, .episode-name, .title, a")?.text()?.trim() 
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: "Episode"
        
        val epUrl = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
        
        // Try to extract episode number
        val epNum = element.selectFirst("span.episode-number, .episode-num, .number")?.text()?.toIntOrNull()
            ?: Regex("""(\d+)\.?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        // Try to extract season number
        val season = element.selectFirst("span.season-number, .season-num")?.text()?.toIntOrNull()
            ?: Regex("""(\d+)\.?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        // Handle lazy-loaded images
        val poster = element.selectFirst("img")?.let { img ->
            img.attr("data-wpfc-original-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?: img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

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
    
    var foundLinks = false

    // Method 1: Look for direct video sources in video tags
    val videoElements = document.select("video source, video[src]")
    for (videoElement in videoElements) {
        val videoUrl = videoElement.attr("src").let { fixUrl(it) }
        if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
            val quality = determineQuality(videoUrl)
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    "$name - Direct",
                    name,
                    videoUrl,
                    type
                ) {
                    this.referer = data
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)
                }
            )
            foundLinks = true
        }
    }

    // Method 2: Look for iframes with video sources
    val iframes = document.select("iframe[src]")
    for (iframe in iframes) {
        val iframeSrc = iframe.attr("src")
        if (iframeSrc.isNotBlank()) {
            val fixedIframeUrl = fixUrl(iframeSrc)
            if (extractFromIframe(fixedIframeUrl, callback, subtitleCallback)) {
                foundLinks = true
            }
        }
    }

    // Method 3: Look for data attributes that might contain video URLs
    val dataVideoElements = document.select("[data-video-src], [data-src]")
    for (element in dataVideoElements) {
        val videoUrl = element.attr("data-video-src").ifBlank { 
            element.attr("data-src") 
        }.let { fixUrl(it) }
        
        if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
            val quality = determineQuality(videoUrl)
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    "$name - Data Attribute",
                    name,
                    videoUrl,
                    type
                ) {
                    this.referer = data
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)
                }
            )
            foundLinks = true
        }
    }

    // Method 4: Extract from JavaScript variables in script tags
    val scriptTags = document.select("script")
    for (script in scriptTags) {
        val scriptContent = script.html()
        val videoUrls = extractVideoUrlsFromJavaScript(scriptContent)
        for (videoUrl in videoUrls) {
            if (isVideoUrl(videoUrl)) {
                val quality = determineQuality(videoUrl)
                val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                callback.invoke(
                    newExtractorLink(
                        "$name - JavaScript",
                        name,
                        videoUrl,
                        type
                    ) {
                        this.referer = data
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)
                    }
                )
                foundLinks = true
            }
        }
    }

    // Method 5: Look for common video hosting patterns
    if (!foundLinks) {
        val html = document.html()
        val regexPatterns = listOf(
            """(?:src|file|videoUrl|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']""",
            """(?:url|source)\s*\(\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']\s*\)""",
            """["'](https?://[^"']+\.(?:mp4|m3u8|webm|mkv))["']""",
            """(https?://[^\s<>"']+\.(?:mp4|m3u8|webm|mkv))""",
            """file:\s*["']([^"']+\.m3u8)["']""",
            """hlsUrl\s*[:=]\s*["']([^"']+\.m3u8)["']""",
            """videoUrl\s*[:=]\s*["']([^"']+\.mp4)["']"""
        )

        for (pattern in regexPatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            for (match in matches) {
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl) && !videoUrl.contains("placeholder") && !videoUrl.contains("blank")) {
                    val quality = determineQuality(videoUrl)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        newExtractorLink(
                            "$name - Regex",
                            name,
                            videoUrl,
                            type
                        ) {
                            this.referer = data
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)
                        }
                    )
                    foundLinks = true
                    break
                }
            }
            if (foundLinks) break
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

            // Look for direct video sources in the iframe
            val videoSources = document.select("video source, video[src]")
            for (source in videoSources) {
                val videoUrl = source.attr("src").let { fixUrl(it) }
                if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
                    val quality = determineQuality(videoUrl)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        newExtractorLink(
                            "$name - Iframe Direct",
                            name,
                            videoUrl,
                            type
                        ) {
                            this.referer = url
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)
                        }
                    )
                    return true
                }
            }

            // Extract from JavaScript in iframe
            val jsVideoUrls = extractVideoUrlsFromJavaScript(html)
            for (videoUrl in jsVideoUrls) {
                if (isVideoUrl(videoUrl)) {
                    val quality = determineQuality(videoUrl)
                    val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        newExtractorLink(
                            "$name - Iframe JS",
                            name,
                            videoUrl,
                            type
                        ) {
                            this.referer = url
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)
                        }
                    )
                    return true
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun extractVideoUrlsFromJavaScript(html: String): List<String> {
        val videoUrls = mutableListOf<String>()
        
        val patterns = listOf(
            """(?:src|file|videoUrl|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']""",
            """(?:url|source)\s*\(\s*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["']\s*\)""",
            """(?:hls|m3u8)Url\s*[:=]\s*["']([^"']+\.m3u8)["']""",
            """(?:mp4|video)Url\s*[:=]\s*["']([^"']+\.mp4)["']""",
            """(?:file|src)\s*:\s*\[[^\]]*["']([^"']+\.(?:mp4|m3u8|webm|mkv))["'][^\]]*\]""",
            """file:\s*["']([^"']+\.m3u8)["']""",
            """source:\s*["']([^"']+\.m3u8)["']"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            matches.forEach { match ->
                val url = fixUrl(match.groupValues[1])
                if (isVideoUrl(url) && !url.contains("placeholder") && !url.contains("blank")) {
                    videoUrls.add(url)
                }
            }
        }

        return videoUrls.distinct()
    }

    private fun isVideoUrl(url: String): Boolean {
        return (url.contains(".mp4") || 
            url.contains(".m3u8") || 
            url.contains(".webm") || 
            url.contains(".mkv")) &&
            !url.contains("data:image") &&
            !url.contains("base64")
    }

    private suspend fun createExtractorLink(
        videoUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        sourceName: String
    ): Boolean {
        val quality = determineQuality(videoUrl)
        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                sourceName,
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
        
        return document.select("div.search-results div.result, div.series-item, .dizi-item, div.episode-box").mapNotNull { element ->
            parseSeriesItem(element) ?: parseEpisodeItem(element)
        }
    }

    // Helper function for URL encoding
    private fun String.encodeToUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
