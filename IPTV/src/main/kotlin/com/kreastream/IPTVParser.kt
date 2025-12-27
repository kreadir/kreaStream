package com.kreastream

import java.io.InputStream

data class Link(
    val name: String, 
    val link: String,
    val order: Int = 0,
    val showAsSection: Boolean = false,
    val isActive: Boolean = true,
    val showAsEpisodes: Boolean = true
)

data class Playlist(
    val items: List<PlaylistItem> = emptyList()
)

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
    val key: String? = null,
    val keyid: String? = null,
)

data class LoadData(
    val url: String, 
    val title: String, 
    val poster: String, 
    val group: String,
    val key: String? = null,
    val keyid: String? = null,
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        var isValid = false
        val reader = input.bufferedReader()
        val firstLine = reader.readLine().isExtendedM3u()
        if (firstLine != null) {
            isValid = true
        }
        if (!isValid) {
            reader.lineSequence().forEach { line ->
                if (line.startsWith(EXT_INF)) {
                    isValid = true
                    return@forEach
                }
            }
        }
        if (!isValid) {
            throw PlaylistParserException.InvalidHeader()
        }

        val playlistItems = mutableListOf<PlaylistItem>()
        var currentItem: PlaylistItem? = null

        reader.lineSequence().forEach { line ->
            val cleanedLine = line.removePrefix("# ").trim()
            try {
                when {
                    cleanedLine.startsWith(EXT_INF) -> {
                        currentItem = PlaylistItem(
                            title = cleanedLine.getTitle(),
                            attributes = cleanedLine.getAttributes()
                        )
                    }
                    cleanedLine.startsWith(EXT_VLC_OPT) -> {
                        val userAgent = cleanedLine.getTagValue("http-user-agent")
                        val referrer = cleanedLine.getTagValue("http-referrer")
                        val headers = mutableMapOf<String, String>()
                        if (userAgent != null) headers["user-agent"] = userAgent
                        if (referrer != null) headers["referrer"] = referrer

                        currentItem = currentItem?.copy(headers = headers)
                    }
                    !cleanedLine.startsWith("#") -> {
                        val url = cleanedLine.getUrl()
                        if (url != null) {
                            val userAgent = cleanedLine.getUrlParameter("user-agent")
                            val referrer = cleanedLine.getUrlParameter("referer")
                            val key = cleanedLine.getUrlParameter("key")
                            val keyid = cleanedLine.getUrlParameter("keyid")

                            val urlHeaders = mutableMapOf<String, String>().apply {
                                if (referrer != null) put("referrer", referrer)
                            }

                            currentItem = currentItem?.copy(
                                url = url,
                                headers = currentItem!!.headers + urlHeaders,
                                userAgent = userAgent ?: currentItem!!.userAgent,
                                key = key ?: currentItem!!.key,
                                keyid = keyid ?: currentItem!!.keyid
                            )
                            playlistItems.add(currentItem!!)
                        }
                        currentItem = null
                    }
                }
            } catch (e: Exception) {
            }
        }
        return Playlist(playlistItems)
    }

    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    private fun String.isExtendedM3u(): Boolean = startsWith(EXT_M3U)

    private fun String.getTitle(): String? {
        return substringAfterLast(",").replaceQuotesAndTrim()
    }

    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    private fun String.getUrlParameter(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val keyRegex = Regex("(?i)$key=([^&]*)")
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()

        return keyRegex.find(paramsString)?.groups?.get(1)?.value
    }

    private fun String.getAttributes(): Map<String, String> {
        val attributesRegex = Regex("""(\w+-\w+|tvg-\w+)="([^"]*)""")
        return attributesRegex.findAll(this)
            .associate { it.groupValues[1] to it.groupValues[2].replaceQuotesAndTrim() }
    }

    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }

    companion object {
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader : PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}