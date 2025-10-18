package com.kreastream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.fixUrl

class BetaplayerExtractor {
    suspend fun extract(url: String): List<ExtractorLink> {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: Regex("""file:\s*["'](https?://[^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: return emptyList()

        return listOf(
            newExtractorLink("Betaplayer", "Betaplayer", fixUrl(videoUrl)) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.isM3u8 = videoUrl.endsWith(".m3u8")
            }
        )
    }
}
