package com.kreastream

import com.lagradost.cloudstream3.*

suspend fun checkLinkType(url: String, headers: Map<String, String> = emptyMap()): String {
    return try {
        when {
            url.contains(".m3u8", ignoreCase = true) -> "m3u8"
            url.contains(".mpegts", ignoreCase = true) -> "mpegts"
            url.contains(".mp4", ignoreCase = true) -> "mp4"
            url.contains(".flv", ignoreCase = true) -> "flv"
            else -> {
                val response = app.head(url, referer = headers["referrer"] ?: "")
                val contentType = response.headers["Content-Type"] ?: ""
                when {
                    contentType.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
                    contentType.contains("application/x-mpegurl", ignoreCase = true) ||
                    contentType.contains("application/json", ignoreCase = true) ||
                    contentType.startsWith("text/") -> "m3u8"
                    else -> "unknown"
                }
            }
        }
    } catch (e: Exception) {
        "unknown"
    }
}