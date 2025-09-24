package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true

    private fun Element.toSearchResponse(): SearchResponse {
        val a = selectFirst("a") ?: throw ErrorLoadingException("No link found")
        val img = selectFirst("img")
        val posterAttr = if (img?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
        val poster = fixUrl(img?.attr(posterAttr) ?: "")
        val titleElem = selectFirst("div.serie-name")
        val epElem = selectFirst("div.episode-name")
        val href = fixUrl(a.attr("href"))
        val isSeries = href.contains("kategori")
        val isMovie = href.contains("-izle.html") && !href.contains("bolum")

        val title = if (isMovie) epElem?.text() ?: titleElem?.text() ?: "" else titleElem?.text() ?: epElem?.text() ?: ""

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
                this.year = epElem?.text()?.toIntOrNull()
                this.quality = getQualityFromString(img?.attr("title"))
            }
        } else if (isMovie) {
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
                this.quality = getQualityFromString(img?.attr("title"))
            }
        } else {
            // Episode treated as movie for simplicity
            val epTitle = "$title ${epElem?.text() ?: ""}".trim()
            newMovieSearchResponse(epTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = getQualityFromString(img?.attr("title"))
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        // Popüler Diziler
        val popularItems = doc.select("div.diziler div.owl-item").map { it.toSearchResponse() }
        if (popularItems.isNotEmpty()) lists.add(HomePageList("Popüler Diziler", popularItems))

        // Yerli Diziler
        val yerliSection = doc.select("div.episodes.episode").getOrNull(0)
        val yerliItems = yerliSection?.select("div.list-episodes")?.map { it.selectFirst("div.episode-box")?.toSearchResponse()!! } ?: emptyList()
        if (yerliItems.isNotEmpty()) lists.add(HomePageList("Yerli Diziler", yerliItems))

        // Dijital Diziler
        val digitalSection = doc.select("div.episodes.episode").getOrNull(1)
        val digitalItems = digitalSection?.select("div.list-episodes")?.map { it.selectFirst("div.episode-box")?.toSearchResponse()!! } ?: emptyList()
        if (digitalItems.isNotEmpty()) lists.add(HomePageList("Dijital Diziler", digitalItems))

        // Filmler
        val filmsSection = doc.select("div.episodes.episode").getOrNull(2)
        val filmsItems = filmsSection?.select("div.list-episodes")?.map { it.selectFirst("div.episode-box")?.toSearchResponse()!! } ?: emptyList()
        if (filmsItems.isNotEmpty()) lists.add(HomePageList("Filmler", filmsItems))

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document
        return doc.select("div.episodes.episode div.list-episodes div.episode-box").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        if (url.contains("kategori")) {
            // Series page
            val title = doc.selectFirst("div.title-border")?.text() ?: doc.selectFirst("title")?.text()?.split(" | ")?.get(0) ?: ""
            val posterElem = doc.selectFirst("div.poster img")
            val posterAttr = if (posterElem?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val poster = fixUrl(posterElem?.attr(posterAttr) ?: "")
            val description = doc.selectFirst("div.synopsis")?.text() // Assuming there's a synopsis div
            val ratingStr = doc.selectFirst("div.episode-date")?.text()?.replace("IMDb: ", "")?.replace(",", ".")?.toFloatOrNull()
            val rating = (ratingStr?.times(10))?.toInt()
            val episodes = doc.select("div.episodes.episode div.list-episodes div.episode-box").mapIndexedNotNull { index, el ->
                val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
                val epName = el.selectFirst("div.episode-name")?.text() ?: ""
                val epNum = epName.replace(".Bölüm", "").trim().toIntOrNull() ?: (index + 1)
                val epUrl = fixUrl(a.attr("href"))
                val epImg = el.selectFirst("img")
                val epPosterAttr = if (epImg?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
                val epPoster = fixUrl(epImg?.attr(epPosterAttr) ?: "")
                val epDate = el.selectFirst("div.episode-date")?.text()
                newEpisode(epUrl) {
                    this.name = epName
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.description = epDate
                }
            }.reversed() // Newest first?

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.rating = rating
            }
        } else {
            // Movie or Episode
            val title = doc.selectFirst("title")?.text()?.split(" | ")?.get(0) ?: ""
            val posterElem = doc.selectFirst("div.poster img")
            val posterAttr = if (posterElem?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val poster = fixUrl(posterElem?.attr(posterAttr) ?: "")
            val description = doc.selectFirst("div.synopsis")?.text()
            val ratingStr = doc.selectFirst("div.episode-date")?.text()?.replace("IMDb: ", "")?.replace(",", ".")?.toFloatOrNull()
            val rating = (ratingStr?.times(10))?.toInt()
            val type = if (url.contains("bolum")) TvType.TvSeries else TvType.Movie

            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = description
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Find all part links like /2, /3 etc.
        val partLinks = doc.select("a[href*=\"$data/\"]").map { fixUrl(it.attr("href")) }.toMutableList()
        if (partLinks.isEmpty()) partLinks.add(data) else partLinks.add(0, data) // Include the main page if parts exist

        partLinks.apmap { partUrl ->
            val partDoc = app.get(partUrl).document
            val iframe = partDoc.selectFirst("iframe")
            val iframeAttr = if (iframe?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val iframeSrc = iframe?.attr(iframeAttr)
                ?: partDoc.selectFirst("div.player embed")?.attr("src")
                ?: return@apmap

            loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}