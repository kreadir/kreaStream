package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val img = selectFirst("img")
        val posterAttr = if (img?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
        val poster = img?.attr(posterAttr)?.let { fixUrl(it) }
        val titleElem = selectFirst("div.serie-name")
        val epElem = selectFirst("div.episode-name")
        val title = titleElem?.text() ?: epElem?.text() ?: ""

        return when {
            href.contains("kategori") -> newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
            }
            href.contains("-izle.html") -> newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

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
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("div.episode-box").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.title-border")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull()?.trim()
            ?: ""
        val poster = doc.selectFirst("div.poster img")?.let {
            val attr = if (it.hasAttr("data-wpfc-original-src")) "data-wpfc-original-src" else "src"
            fixUrl(it.attr(attr))
        }
        val description = doc.selectFirst("div.synopsis")?.text()?.trim()

        return if (url.contains("kategori")) {
            val episodes = doc.select("div.episode-box").mapIndexedNotNull { index, el ->
                val epName = el.selectFirst("div.episode-name")?.text()?.trim() ?: ""
                val epUrl = fixUrl(el.selectFirst("a")?.attr("href") ?: return@mapIndexedNotNull null)
                val epPoster = el.selectFirst("img")?.let {
                    val attr = if (it.hasAttr("data-wpfc-original-src")) "data-wpfc-original-src" else "src"
                    fixUrl(it.attr(attr))
                }
                newEpisode(epUrl) {
                    this.name = epName
                    this.season = 1
                    this.episode = index + 1
                    this.posterUrl = epPoster
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val iframeUrls = doc.select("iframe[data-wpfc-original-src], iframe[src]").mapNotNull {
                val raw = it.attr("data-wpfc-original-src").ifEmpty { it.attr("src") }
                fixUrl(raw)
            }

            val links = iframeUrls.map {
                ExtractorLink(name, name, it, url, Qualities.Unknown.value, it.endsWith(".m3u8"))
            }

            newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }
}
