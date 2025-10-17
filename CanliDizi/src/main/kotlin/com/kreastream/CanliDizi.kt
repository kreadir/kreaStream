package com.kreastream

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true

    // 🔧 Utility: Parse iframe links from a document
    private fun parseIframeLinks(doc: org.jsoup.nodes.Document): List<String> {
        return doc.select("iframe[data-wpfc-original-src], iframe[src]").mapNotNull { iframe ->
            val rawSrc = iframe.attr("data-wpfc-original-src").ifEmpty { iframe.attr("src") }
            val fixedSrc = fixUrl(rawSrc)

            when {
                fixedSrc.contains("betaplayer.site") -> {
                    val videoId = fixedSrc.substringAfterLast("/")
                    "https://betaplayer.site/embed/$videoId"
                }
                fixedSrc.contains("canliplayer.com") -> {
                    val videoId = fixedSrc.substringAfterLast("/")
                    "https://canliplayer.com/fireplayer/video/$videoId"
                }
                else -> fixedSrc
            }
        }
    }

    // 🔧 Utility: Convert episode/movie element to SearchResponse
    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val img = selectFirst("img")
        val posterAttr = if (img?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
        val poster = img?.attr(posterAttr)?.let { fixUrl(it) }
        val titleElem = selectFirst("div.serie-name")
        val epElem = selectFirst("div.episode-name")
        val href = fixUrl(a.attr("href"))
        val isSeries = href.contains("kategori")
        val isMovie = href.contains("-izle.html") && !href.contains("bolum")

        val title = if (isMovie) epElem?.text() ?: titleElem?.text() ?: "" else titleElem?.text() ?: epElem?.text() ?: ""
        val year = epElem?.text()?.toIntOrNull()

        return when {
            isSeries -> newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
                this.year = year
            }
            isMovie -> newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
            else -> {
                val epTitle = "$title ${epElem?.text() ?: ""}".trim()
                newMovieSearchResponse(epTitle, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        doc.select("div.episodes.episode").forEachIndexed { index, section ->
            val title = section.selectFirst("h2")?.text()?.trim() ?: "Bölüm $index"
            val items = section.select("div.list-episodes div.episode-box").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) lists.add(HomePageList(title, items))
        }

        val popularItems = doc.select("div.diziler div.owl-item").mapNotNull { it.toSearchResponse() }
        if (popularItems.isNotEmpty()) lists.add(HomePageList("Popüler Diziler", popularItems))

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document
        return doc.select("div.episodes.episode div.list-episodes div.episode-box").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
    val doc = app.get(url).document
    val title = doc.selectFirst("div.title-border")?.text()?.trim()
        ?: doc.selectFirst("title")?.text()?.split(" | ")?.getOrNull(0)?.trim()
        ?: ""
    val posterElem = doc.selectFirst("div.poster img")
    val posterAttr = if (posterElem?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
    val poster = posterElem?.attr(posterAttr)?.let { fixUrl(it) }
    val description = doc.selectFirst("div.synopsis")?.text()?.trim()

    return if (url.contains("kategori")) {
        val episodes = doc.select("div.episodes.episode div.list-episodes div.episode-box").mapIndexedNotNull { index, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val epName = el.selectFirst("div.episode-name")?.text()?.trim() ?: ""
            val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)
            val epUrl = fixUrl(a.attr("href"))
            val epImg = el.selectFirst("img")
            val epPosterAttr = if (epImg?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val epPoster = epImg?.attr(epPosterAttr)?.let { fixUrl(it) }
            val epDate = el.selectFirst("div.episode-date")?.text()?.trim()

            newEpisode(epUrl) {
                this.name = epName
                this.season = 1
                this.episode = epNum
                this.posterUrl = epPoster
                this.description = epDate
            }
        }

        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    } else {
        val type = if (url.contains("bolum")) TvType.TvSeries else TvType.Movie

        // 🔥 Extract raw iframe URLs for extractor routing
        val iframeUrls = doc.select("iframe[data-wpfc-original-src], iframe[src]").mapNotNull { iframe ->
            val rawSrc = iframe.attr("data-wpfc-original-src").ifEmpty { iframe.attr("src") }
            fixUrl(rawSrc)
        }

        val streamUrl = iframeUrls.firstOrNull() ?: throw ErrorLoadingException("No playable iframe found")

        newMovieLoadResponse(title, url, type, streamUrl) {
            this.posterUrl = poster
            this.plot = description
        }
    }
}

}
