package com.kreastream

import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

class BetaplayerExtractor : ExtractorApi() {
    override val name = "Betaplayer"
    override val mainUrl = "https://betaplayer.site"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src") ?: return emptyList()

        return listOf(
            newExtractorLink(
                this.name,
                this.name,
                fixUrl(videoUrl),
                url,
                Qualities.Unknown.value,
                videoUrl.endsWith(".m3u8")
            )
        )
    }
}
