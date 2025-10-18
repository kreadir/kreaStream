package com.kreastream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.utils.*

class CanliplayerExtractor : ExtractorApi() {
    override val name = "Canliplayer"
    override val mainUrl = "https://canliplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: Regex("""file:\s*["'](https?://[^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: return emptyList()

        return listOf(
            newExtractorLink("Canliplayer", "Canliplayer", fixUrl(videoUrl), url, Qualities.Unknown.value, videoUrl.endsWith(".m3u8"))
        )
    }
}
