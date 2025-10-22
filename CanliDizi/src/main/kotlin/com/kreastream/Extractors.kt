package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*

interface VideoExtractor {
    val name: String
    suspend fun extract(
        app: App,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean
}

class YouTubeExtractor : VideoExtractor {
    override val name = "YouTube"
    
    override suspend fun extract(
        app: App,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("$name extractor started")
        
        val patterns = listOf(
            """youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""",
            """youtu\.be/([a-zA-Z0-9_-]{11})""",
            """youtube\.com/embed/([a-zA-Z0-9_-]{11})""",
            """videoId["']?\s*:\s*["']([^"']{11})["']""",
            """source\s*:\s*["'](https?://www\.youtube\.com/watch\?v=([^"']{11}))["']"""
        )

        for (pattern in patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val youtubeUrl = when {
                    pattern.contains("youtube\\.com/watch\\?v=") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("youtu\\.be/") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("youtube\\.com/embed/") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("videoId") -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    pattern.contains("source") && match.groupValues.size > 2 -> 
                        match.groupValues[1]
                    else -> 
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                }

                if (isValidYouTubeUrl(youtubeUrl)) {
                    println("Found valid YouTube URL: $youtubeUrl")
                    if (createYouTubeLink(youtubeUrl, referer, callback)) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    private fun isValidYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com/watch?v=") && 
               url.length >= 30 &&
               !url.contains("no-referrer")
    }
    
    suspend fun createYouTubeLink( // Made public
        youtubeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
            if (videoId.length != 11) return false

            callback.invoke(
                newExtractorLink(
                    "YouTube",
                    "YouTube",
                    youtubeUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to CanliDizi.USER_AGENT,
                        "Referer" to referer
                    )
                }
            )
            
            println("Successfully created YouTube extractor link")
            return true
        } catch (e: Exception) {
            println("Error creating YouTube link: ${e.message}")
            return false
        }
    }
}

class CanliPlayerExtractor : VideoExtractor {
    override val name = "CanliPlayer"
    
    override suspend fun extract(
        app: App,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("$name extractor started")
        
        // Look for canliplayer.com URLs
        val playerPatterns = listOf(
            """(https?://[^"'\s]*canliplayer\.com/fireplayer/video/[^"'\s]*)""",
            """(https?://[^"'\s]*canliplayer\.com[^"'\s]*video[^"'\s]*)""",
            """(https?://[^"'\s]*fireplayer\.com[^"'\s]*)"""
        )

        for (pattern in playerPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                println("Found CanliPlayer URL: $playerUrl")
                
                try {
                    val response = app.get(playerUrl, headers = mapOf(
                        "User-Agent" to CanliDizi.USER_AGENT,
                        "Referer" to referer
                    ))
                    
                    val playerHtml = response.text
                    
                    // Look for YouTube IDs in packed JavaScript
                    if (extractFromPackedJavaScript(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                    // Look for direct video URLs
                    if (extractDirectVideos(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("Error fetching CanliPlayer: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractFromPackedJavaScript(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Look for packed arrays containing YouTube IDs
        val packedPatterns = listOf(
            """split\('\|'\)[^)]+\)[^,]+,\d+,[^,]+,'([^']+)'\)""",
            """eval\(function\([^)]+\)\s*\([^,]+,\s*[^,]+,\s*[^,]+,\s*'([^']+)'\)"""
        )
        
        for (pattern in packedPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val packedString = match.groupValues[1]
                println("Found packed string: $packedString")
                
                val parts = packedString.split('|')
                for (part in parts) {
                    if (isValidYouTubeId(part)) {
                        val youtubeUrl = "https://www.youtube.com/watch?v=$part"
                        println("Found YouTube ID in packed array: $part")
                        
                        val youtubeExtractor = YouTubeExtractor()
                        if (youtubeExtractor.createYouTubeLink(youtubeUrl, referer, callback)) {
                            return true
                        }
                    }
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractDirectVideos(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoPatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """file\s*:\s*["'](https?://[^"']+)["']""",
            """source\s*:\s*["'](https?://[^"']+)["']"""
        )
        
        for (pattern in videoPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found direct video in CanliPlayer: $videoUrl")
                    createVideoLink(videoUrl, referer, callback, "CanliPlayer")
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun isValidYouTubeId(id: String): Boolean {
        return id.length == 11 && 
               id.matches(Regex("[a-zA-Z0-9_-]+")) &&
               id !in listOf("fireplayer", "FirePlayer", "jwplayer8", "videojsSkin", 
                           "beezPlayer", "youtubeApi", "no-referrer", "referrer", "canliplayer")
    }
}

class BetaPlayerExtractor : VideoExtractor {
    override val name = "BetaPlayer"
    
    override suspend fun extract(
        app: App,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("$name extractor started")
        
        val playerPatterns = listOf(
            """(https?://[^"'\s]*betaplayer\.site/embed/[^"'\s]*)""",
            """(https?://[^"'\s]*betaplayer\.site[^"'\s]*)"""
        )

        for (pattern in playerPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val playerUrl = fixUrl(match.groupValues[1])
                println("Found BetaPlayer URL: $playerUrl")
                
                try {
                    val response = app.get(playerUrl, headers = mapOf(
                        "User-Agent" to CanliDizi.USER_AGENT,
                        "Referer" to referer
                    ))
                    
                    val playerHtml = response.text
                    
                    // Try YouTube extraction first
                    val youtubeExtractor = YouTubeExtractor()
                    if (youtubeExtractor.extract(app, playerHtml, playerUrl, callback, subtitleCallback)) {
                        return true
                    }
                    
                    // Then try direct videos
                    if (extractDirectVideos(playerHtml, playerUrl, callback)) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("Error fetching BetaPlayer: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    private suspend fun extractDirectVideos(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoPatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """file\s*:\s*["'](https?://[^"']+)["']""",
            """source\s*:\s*["'](https?://[^"']+)["']"""
        )
        
        for (pattern in videoPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found direct video in BetaPlayer: $videoUrl")
                    createVideoLink(videoUrl, referer, callback, "BetaPlayer")
                    return true
                }
            }
        }
        
        return false
    }
}

class IframeExtractor : VideoExtractor {
    override val name = "Iframe"
    
    override suspend fun extract(
        app: App,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("$name extractor started")
        
        val document = app.get(referer, headers = mapOf("User-Agent" to CanliDizi.USER_AGENT)).document
        
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                val fixedUrl = fixUrl(iframeSrc)
                println("Found iframe: $fixedUrl")
                
                try {
                    val response = app.get(fixedUrl, headers = mapOf(
                        "User-Agent" to CanliDizi.USER_AGENT,
                        "Referer" to referer
                    ))
                    
                    val iframeHtml = response.text
                    
                    // Try all other extractors on the iframe content
                    val extractors = listOf(
                        YouTubeExtractor(),
                        DirectVideoExtractor()
                    )
                    
                    for (extractor in extractors) {
                        if (extractor.extract(app, iframeHtml, fixedUrl, callback, subtitleCallback)) {
                            return true
                        }
                    }
                    
                } catch (e: Exception) {
                    println("Error fetching iframe: ${e.message}")
                }
            }
        }
        
        return false
    }
}

class DirectVideoExtractor : VideoExtractor {
    override val name = "DirectVideo"
    
    override suspend fun extract(
        app: App,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("$name extractor started")
        
        val videoPatterns = listOf(
            """(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""",
            """(https?://[^"'\s]+\.webm(?:\?[^"'\s]*)?)""",
            """file\s*:\s*["'](https?://[^"']+)["']""",
            """source\s*:\s*["'](https?://[^"']+)["']""",
            """videoUrl\s*:\s*["'](https?://[^"']+)["']""",
            """src\s*:\s*["'](https?://[^"']+)["']"""
        )
        
        for (pattern in videoPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = fixUrl(match.groupValues[1])
                if (isVideoUrl(videoUrl)) {
                    println("Found direct video URL: $videoUrl")
                    createVideoLink(videoUrl, referer, callback, "Direct")
                    return true
                }
            }
        }
        
        return false
    }
}

// Helper functions for extractors
private fun isVideoUrl(url: String): Boolean {
    if (url.isBlank()) return false
    
    val videoExtensions = listOf(".mp4", ".m3u8", ".webm", ".mkv", ".avi", ".mov", ".flv")
    val videoKeywords = listOf("video", "stream", "m3u8", "mp4", "hls", "dash")
    
    return (videoExtensions.any { url.contains(it, ignoreCase = true) } ||
            videoKeywords.any { url.contains(it, ignoreCase = true) }) &&
            !url.contains("data:image") &&
            !url.contains("base64") &&
            !url.contains("placeholder") &&
            !url.contains("blank") &&
            !url.contains("logo") &&
            !url.contains("ads") &&
            !url.contains("banner")
}

private suspend fun createVideoLink(
    videoUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
    sourceName: String
) {
    try {
        val quality = determineQuality(videoUrl)
        val type = when {
            videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
            videoUrl.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        println("Creating video link: $videoUrl (Quality: $quality, Type: $type)")

        callback.invoke(
            newExtractorLink(
                "Canlı Dizi - $sourceName",
                "Canlı Dizi",
                videoUrl,
                type
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to CanliDizi.USER_AGENT,
                    "Referer" to referer
                )
            }
        )
    } catch (e: Exception) {
        println("Error creating video link: ${e.message}")
    }
}

private fun determineQuality(url: String): Int {
    return when {
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun fixUrl(url: String): String {
    return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.canlidizi14.com$url"
        else -> url
    }
}