package com.kreastream
import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.fixUrl

import org.jsoup.Jsoup

class BetaplayerExtractor : ExtractorApi() {
    override val name = "Betaplayer"
    override val mainUrl = "https://betaplayer.site"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src") ?: return emptyList()

        return listOf(
            newExtractorLink(
                name = name,
                source = name,
                url = fixUrl(videoUrl),
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = videoUrl.endsWith(".m3u8")
            )
        )
    }
}
