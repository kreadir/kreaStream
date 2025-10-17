package com.kreastream.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.Jsoup

class BetaplayerExtractor : ExtractorApi() {
    override val name = "Betaplayer"
    override val mainUrl = "https://betaplayer.site"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document

        val videoUrl = doc.selectFirst("video source")?.attr("src") ?: return emptyList()

        return listOf(
            ExtractorLink(
                name = this.name,
                source = this.name,
                url = fixUrl(videoUrl),
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = videoUrl.endsWith(".m3u8")
            )
        )
    }
}
