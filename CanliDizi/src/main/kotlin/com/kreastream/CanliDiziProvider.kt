package com.kreastream

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorSubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray

class CanliDiziProvider : ExtractorApi() {
    override val name = "CanliDizi14"
    override val mainUrl = "https://www.canlidizi14.com"
    override val requiresReferer = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getUrl(
        id: String,
        referrer: String?,
        subtitleCallback: (ExtractorSubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val doc = app.get(id, referrer = referrer).document

            // Common pattern: iframe embeds from external hosts
            doc.select("iframe[src]").forEach { elem ->
                val iframe = fixUrl(elem.attr("src"))
                if (Regex("(?i)(embed|player|video)").containsMatchIn(iframe)) {
                    loadExtractor(iframe, referrer = id, subtitleCallback = subtitleCallback, callback = callback)
                }
            }

            // Fallback: Direct <video> tags or sources
            doc.select("video source[src], video[src]").forEach { elem ->
                val source = fixUrl(elem.attr("src"))
                callback(
                    newExtractorLink(name, name, source, referrer = id, quality = Qualities.Unknown.value)
                )
            }

            // Fallback: Direct m3u8 links in anchors or scripts
            doc.select("a[href*=.m3u8], script:contains(.m3u8)").forEach { elem ->
                val m3u8 = if (elem.tagName() == "a") {
                    fixUrl(elem.attr("href"))
                } else {
                    // Simple JS extraction if needed (e.g., var player = {...})
                    val script = elem.data()
                    val m3u8Match = Regex("['\"]([^'\"]*\\.m3u8)['\"]").find(script)
                    m3u8Match?.groupValues?.getOrNull(1)?.let { fixUrl(it) }
                }
                m3u8?.let {
                    callback(
                        newExtractorLink(name, name, it, referrer = id, quality = Qualities.HD.value)
                    )
                }
            }

            // If JSON config in script (common for players like JWPlayer)
            doc.select("script").forEach { script ->
                val scriptText = script.data()
                val jsonMatch = Regex("\\{[^}]*playlist[^}]*\\}").find(scriptText)
                jsonMatch?.let { match ->
                    val jsonStr = match.value
                    try {
                        val json = parseJson<JSONObject>(jsonStr)
                        val playlistArray = json.optJSONArray("playlist")
                        if (playlistArray != null && playlistArray.length() > 0) {
                            val sources = playlistArray.optJSONObject(0)?.optJSONArray("sources")
                            if (sources != null) {
                                for (i in 0 until sources.length()) {
                                    val obj = sources.getJSONObject(i)
                                    val file = obj.optString("file")
                                    val label = obj.optString("label", "Unknown")
                                    if (file.contains(".m3u8") || file.contains(".mp4")) {
                                        callback(
                                            newExtractorLink(
                                                name, name, fixUrl(file), referrer = id,
                                                quality = label.toIntOrNull() ?: Qualities.Unknown.value
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parse errors
                    }
                }
            }
        }
    }
}