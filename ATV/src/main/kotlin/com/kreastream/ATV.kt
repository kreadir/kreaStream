package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ATV : MainAPI() {
    override var name = "ATV"
    override var mainUrl = "https://www.atv.com.tr"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // ===================== MODULAR SELECTORS =====================
    private data class Sel(
        val mainItems: String = ".series-card, .card-item, a[href*='/dizi/']",
        val nextPage: String = ".pagination .next, a[rel='next']",

        val cardLink: String = "a",
        val cardTitle: String = "h3, .title, img",
        val cardPoster: String = "img[data-src], img[src]",

        val pageTitle: String = "h1, .seo-h1, meta[property='og:title']",
        val pagePoster: String = "meta[property='og:image'], img.series-poster",
        val description: String = ".description, .synopsis, meta[name='description']",
        val genres: String = ".genre a, .tags a",
        val episodes: String = "a[href*='/bolum/'], .episode-card a",

        val playerIframe: String = "iframe[src*='player'], iframe#player, .player iframe",
        val jsFileRegex: Regex = """["']file["']\s*:\s*["']([^"']+)["']""".toRegex()
    )

    private val s = Sel()
    // ============================================================

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/eski-diziler" to "Arşiv Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        val items = doc.select(s.mainItems).mapNotNull { it.toSearchResult() }
        val hasNext = doc.select(s.nextPage).isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(s.cardLink) ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.contains("/dizi/") && !href.contains("/webtv/")) return null

        val title = a.selectFirst(s.cardTitle)?.ownText()
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst("img")?.attr("alt")
            ?: return null

        val poster = a.selectFirst(s.cardPoster)?.attr("abs:src")
            ?: a.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val safeQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/arama?q=$safeQuery").document
        return doc.select("a[href*='/dizi/'], a[href*='/webtv/']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(s.pageTitle)?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim() ?: "Bilinmeyen Dizi"

        val poster = doc.selectFirst(s.pagePoster)?.let {
            if (it.tagName() == "meta") it.attr("content") else it.attr("abs:src")
        }

        val plot = doc.selectFirst(s.description)?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val tags = doc.select(s.genres).map { it.text() }

        val episodes = doc.select(s.episodes).mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            if (!href.contains("/bolum/")) return@mapNotNull null
            val name = el.text().trim().ifBlank { "Bölüm" }
            newEpisode(href) { this.name = name }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = app.get(data)
        val doc = res.document

        // 1. Iframe player
        doc.selectFirst(s.playerIframe)?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        // 2. Direct JS file
        doc.select("script").forEach { script ->
            s.jsFileRegex.find(script.data())?.groupValues?.get(1)?.let { videoUrl ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Direkt",
                        url = videoUrl.trim()
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = videoUrl.contains(".m3u8")
                    }
                )
            }
        }

        // 3. WebView Resolver (new 2025 API)
        try {
            WebViewResolver().apply {
                // Intercept any .m3u8 or .mp4
                addRequestFilter { request ->
                    request.url.toString().let { url ->
                        url.contains(".m3u8", ignoreCase = true) ||
                        url.contains(".mp4", ignoreCase = true) ||
                        url.contains("master", ignoreCase = true)
                    }
                }

                addResponseHandler { response ->
                    response.request.url.toString().let { url ->
                        if (url.contains(".m3u8") || url.contains(".mp4")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - Protected",
                                    url = url
                                ){
                                    this.referer = response.request.headers["Referer"] ?: data,
                                    this.quality = Qualities.Unknown.value,
                                    //this.isM3u8 = url.contains(".m3u8"),
                                    this.headers = response.request.headers
                                }
                            )
                        }
                    }
                }
            }.resolveUsingWebView(res.text, res.url)
        } catch (e: Exception) {
            // Ignore WebView errors
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + url.removePrefix("/")
    }
}