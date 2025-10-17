package com.kreastream

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import java.util.regex.Pattern

class CanliDiziProvider : ExtractorApi() {
    override var name = "CanliDizi14"
    override var mainUrl = "https://www.canlidizi14.com"
    override var requiresReferer = false
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getUrl(
        id: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val doc = app.get(id, referer = referer).document

            // Common pattern: iframe embeds from external hosts
            doc.select("iframe[src]").forEach { elem ->
                val iframe = fixUrl(elem.attr("src"))
                if (Pattern.compile("(?i)(embed|player|video)").matcher(iframe).find()) {
                    loadExtractor(iframe, referer, subtitleCallback, callback)
                }
            }

            // Fallback: Direct <video> tags or sources
            doc.select("video source[src], video[src]").forEach { elem ->
                val source = fixUrl(elem.attr("src"))
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = source,
                        referer = referer ?: "",
                        quality = Qualities.Unknown.value
                    )
                )
            }

            // Fallback: Direct m3u8 links in anchors or scripts
            doc.select("a[href*=.m3u8], script:contains(.m3u8)").forEach { elem ->
                var m3u8: String? = null
                if (elem.tagName() == "a") {
                    m3u8 = fixUrl(elem.attr("href"))
                } else {
                    // Simple JS extraction if needed (e.g., var player = {...})
                    val script = elem.data()
                    val m3u8Matcher = Pattern.compile("['\"]([^'\"]*\\.m3u8)['\"]").matcher(script)
                    if (m3u8Matcher.find()) {
                        m3u8 = fixUrl(m3u8Matcher.group(1))
                    }
                }
                m3u8?.let {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = it,
                            referer = referer ?: "",
                            quality = Qualities.P720.value
                        )
                    )
                }
            }

            // If JSON config in script (common for players like JWPlayer)
            doc.select("script").forEach { script ->
                val scriptText = script.data()
                val jsonMatcher = Pattern.compile("\\{[^}]*playlist[^}]*\\}").matcher(scriptText)
                if (jsonMatcher.find()) {
                    val jsonStr = jsonMatcher.group()
                    try {
                        val json = JSONObject(jsonStr)
                        val playlist = json.optJSONArray("playlist")
                        if (playlist != null && playlist.length() > 0) {
                            val sources = playlist.getJSONObject(0).optJSONArray("sources")
                            if (sources != null) {
                                for (i in 0 until sources.length()) {
                                    val obj = sources.getJSONObject(i)
                                    val file = obj.optString("file")
                                    val label = obj.optString("label", "Unknown")
                                    if (file.contains(".m3u8") || file.contains(".mp4")) {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = name,
                                                url = fixUrl(file),
                                                referer = referer ?: "",
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