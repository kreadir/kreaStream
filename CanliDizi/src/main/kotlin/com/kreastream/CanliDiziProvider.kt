package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorSubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class CanliDiziExtractor : ExtractorApi() {
    override val name = "CanliDizi"
    override val mainUrl = "https://www.canlidizi14.com"
    override val requiresReferer = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TVSeries)

    override suspend fun getUrl(
        id: String,
        referer: String?,
        subtitleCallback: (ExtractorSubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val doc = app.get(id, referer = referer).document

            // Common pattern: iframe embeds from external hosts
            doc.select("iframe[src]").forEach { elem ->
                val iframe = fixUrl(elem.attr("src"), mainUrl)
                if (iframe.contains(Regex("(?i)(embed|player|video)"))) {
                    loadExtractor(iframe, id, subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                link.name,
                                link.source,
                                link.url,
                                link.referer ?: id,
                                link.quality,
                                link.m3u8Quality,
                                link.subtitles,
                                link.language
                            )
                        )
                    }
                }
            }

            // Fallback: Direct <video> tags or sources
            doc.select("video source[src], video[src]").forEach { elem ->
                val source = fixUrl(elem.attr("src"), mainUrl)
                callback(
                    ExtractorLink(
                        name,
                        name,
                        source,
                        referer = id,
                        quality = Qualities.Unknown.value
                    )
                )
            }

            // Fallback: Direct m3u8 links in anchors or scripts
            doc.select("a[href*=.m3u8], script:contains(.m3u8)").forEach { elem ->
                val m3u8 = if (elem.tagName() == "a") {
                    fixUrl(elem.attr("href"), mainUrl)
                } else {
                    // Simple JS extraction if needed (e.g., var player = {...})
                    val script = elem.data()
                    val m3u8Match = Regex("['\"]([^'\"]*\\.m3u8)['\"]").find(script)
                    m3u8Match?.groupValues?.get(1)?.let { fixUrl(it, mainUrl) }
                }
                m3u8?.let {
                    callback(
                        ExtractorLink(
                            name,
                            name,
                            it,
                            referer = id,
                            quality = Qualities.HD.value
                        )
                    )
                }
            }

            // If JSON config in script (common for players like JWPlayer)
            doc.select("script").forEach { script ->
                val jsonMatch = Regex("\\{[^}]*playlist[^}]*\\}").find(script.data())
                jsonMatch?.value?.let { jsonStr ->
                    try {
                        val json = parseJson(jsonStr)
                        val sources = json?.optJSONArray("playlist")?.optJSONObject(0)?.optJSONArray("sources")
                        sources?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val file = obj.optString("file")
                                val label = obj.optString("label", "Unknown")
                                if (file.contains(".m3u8") || file.contains(".mp4")) {
                                    callback(
                                        ExtractorLink(
                                            name,
                                            name,
                                            fixUrl(file, mainUrl),
                                            referer = id,
                                            quality = label.toIntOrNull() ?: Qualities.Unknown.value
                                        )
                                    )
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