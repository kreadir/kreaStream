package com.kreastream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.ExtractorApi

class CanliplayerExtractor : ExtractorApi() {
    override val name = "Canliplayer"
    override val mainUrl = "https://canliplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: Regex("""file:\s*["'](https?://[^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val isM3u8 = videoUrl.endsWith(".m3u8")

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = fixUrl(videoUrl),
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.Direct
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
