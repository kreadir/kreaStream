package com.kreastream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class BetaplayerExtractor {
    suspend fun extract(url: String): List<ExtractorLink> {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: Regex("""file:\s*["'](https?://[^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: return emptyList()

        return listOf(
            ExtractorLink("Betaplayer", "Betaplayer", fixUrl(videoUrl), url, Qualities.Unknown.value, videoUrl.endsWith(".m3u8"))
        )
    }
}
