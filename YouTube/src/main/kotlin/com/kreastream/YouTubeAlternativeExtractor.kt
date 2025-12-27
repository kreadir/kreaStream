package com.kreastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Alternative YouTube extractor that uses direct YouTube API calls
 * as a fallback when NewPipe fails or returns limited quality streams
 */
class YouTubeAlternativeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube Alternative"

    private val videoIdRegex = Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            Log.d("YouTubeAlternativeExtractor", "Starting alternative extraction for URL: $url")
            
            val videoId = extractVideoId(url)
            if (videoId == null) {
                Log.w("YouTubeAlternativeExtractor", "Could not extract video ID from URL: $url")
                return
            }
            
            Log.d("YouTubeAlternativeExtractor", "Extracted video ID: $videoId")
            
            // Try to get video info using YouTube's player API
            val playerResponse = getPlayerResponse(videoId)
            if (playerResponse != null) {
                parsePlayerResponse(playerResponse, callback, subtitleCallback)
            } else {
                Log.w("YouTubeAlternativeExtractor", "Failed to get player response for video: $videoId")
            }
            
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeExtractor", "Error in alternative extraction: ${e.message}")
        }
    }
    
    private fun extractVideoId(url: String): String? {
        val matcher = videoIdRegex.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }
    
    private suspend fun getPlayerResponse(videoId: String): JSONObject? {
        return try {
            // Try multiple approaches to get player response
            
            // Method 1: Try embed page first (most reliable)
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            val embedResponse = app.get(embedUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept-Encoding" to "gzip, deflate",
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1"
            )).text
            
            // Extract player response from embed page
            val playerResponsePattern = Pattern.compile("var ytInitialPlayerResponse = (\\{.*?\\});")
            val matcher = playerResponsePattern.matcher(embedResponse)
            
            if (matcher.find()) {
                val playerResponseStr = matcher.group(1)
                if (playerResponseStr != null) {
                    Log.d("YouTubeAlternativeExtractor", "Found player response in embed page")
                    return JSONObject(playerResponseStr)
                }
            }
            
            // Method 2: Try watch page if embed failed
            Log.d("YouTubeAlternativeExtractor", "Embed method failed, trying watch page...")
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val watchResponse = app.get(watchUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Cookie" to "CONSENT=YES+cb.20210328-17-p0.en+FX+667",
                "DNT" to "1",
                "Connection" to "keep-alive"
            )).text
            
            // Try different patterns for watch page
            val patterns = listOf(
                "var ytInitialPlayerResponse = (\\{.*?\\});",
                "ytInitialPlayerResponse\":(\\{.*?\\}),\"",
                "\"playerResponse\":(\\{.*?\\}),\"",
                "window\\[\"ytInitialPlayerResponse\"\\] = (\\{.*?\\});"
            )
            
            for (pattern in patterns) {
                val patternMatcher = Pattern.compile(pattern).matcher(watchResponse)
                if (patternMatcher.find()) {
                    val playerResponseStr = patternMatcher.group(1)
                    if (playerResponseStr != null) {
                        Log.d("YouTubeAlternativeExtractor", "Found player response in watch page with pattern: $pattern")
                        return JSONObject(playerResponseStr)
                    }
                }
            }
            
            Log.w("YouTubeAlternativeExtractor", "Could not find player response in any page")
            null
            
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeExtractor", "Error getting player response: ${e.message}")
            null
        }
    }
    
    private suspend fun parsePlayerResponse(
        playerResponse: JSONObject,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val streamingData = playerResponse.optJSONObject("streamingData")
            if (streamingData == null) {
                Log.w("YouTubeAlternativeExtractor", "No streaming data found in player response")
                return
            }
            
            var linksAdded = 0
            
            // Parse regular formats (video + audio combined) - these work without decryption
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                Log.d("YouTubeAlternativeExtractor", "Found ${formats.length()} regular formats")
                
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    val url = format.optString("url")
                    val quality = format.optString("qualityLabel", "")
                    val itag = format.optInt("itag", 0)
                    val height = format.optInt("height", 0)
                    
                    if (url.isNotEmpty()) {
                        val qualityInt = if (height > 0) height else parseQualityFromLabel(quality)
                        val qualityLabel = if (quality.isNotEmpty()) quality else "${height}p"
                        
                        Log.d("YouTubeAlternativeExtractor", "Adding combined stream: ${qualityLabel}, itag=$itag")
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "${this.name} - ${qualityLabel} (Combined)",
                                url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.youtube.com/"
                                this.quality = qualityInt
                                // Add YouTube-specific headers for stream access
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                    "Accept" to "*/*",
                                    "Accept-Language" to "en-US,en;q=0.9",
                                    "Origin" to "https://www.youtube.com",
                                    "Referer" to "https://www.youtube.com/"
                                )
                            }
                        )
                        linksAdded++
                    }
                }
            }
            
            // Parse regular formats (video + audio combined) - these work reliably
            val regularFormats = streamingData.optJSONArray("formats")
            if (regularFormats != null) {
                Log.d("YouTubeAlternativeExtractor", "Found ${regularFormats.length()} regular formats")
                
                for (i in 0 until regularFormats.length()) {
                    val format = regularFormats.getJSONObject(i)
                    val url = format.optString("url")
                    val quality = format.optString("qualityLabel", "")
                    val itag = format.optInt("itag", 0)
                    val height = format.optInt("height", 0)
                    
                    if (url.isNotEmpty()) {
                        val qualityInt = if (height > 0) height else parseQualityFromLabel(quality)
                        val qualityLabel = if (quality.isNotEmpty()) quality else "${height}p"
                        
                        Log.d("YouTubeAlternativeExtractor", "Adding combined stream: ${qualityLabel}, itag=$itag")
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "${this.name} - ${qualityLabel}",
                                url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.youtube.com/"
                                this.quality = qualityInt
                                // Add YouTube-specific headers for stream access
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                    "Accept" to "*/*",
                                    "Accept-Language" to "en-US,en;q=0.9",
                                    "Origin" to "https://www.youtube.com",
                                    "Referer" to "https://www.youtube.com/"
                                )
                            }
                        )
                        linksAdded++
                    }
                }
            }
            
            // Only add adaptive formats if no combined formats were found
            if (linksAdded == 0) {
                Log.d("YouTubeAlternativeExtractor", "No combined formats found, trying adaptive formats...")
                
                // Parse adaptive formats but only add a few key ones to avoid overwhelming
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    Log.d("YouTubeAlternativeExtractor", "Found ${adaptiveFormats.length()} adaptive formats")
                    
                    // Look for specific quality video streams that might work
                    for (i in 0 until adaptiveFormats.length()) {
                        val format = adaptiveFormats.getJSONObject(i)
                        val url = format.optString("url")
                        val mimeType = format.optString("mimeType", "")
                        val quality = format.optString("qualityLabel", "")
                        val itag = format.optInt("itag", 0)
                        val height = format.optInt("height", 0)
                        
                        // Only add video streams that might have audio (some adaptive streams do)
                        if (url.isNotEmpty() && mimeType.contains("video") && height <= 480) {
                            val qualityInt = if (height > 0) height else parseQualityFromLabel(quality)
                            val qualityLabel = if (quality.isNotEmpty()) quality else "${height}p"
                            
                            Log.d("YouTubeAlternativeExtractor", "Adding adaptive stream: ${qualityLabel}, itag=$itag")
                            
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "${this.name} - ${qualityLabel}",
                                    url,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = qualityInt
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                        "Accept" to "*/*",
                                        "Accept-Language" to "en-US,en;q=0.9",
                                        "Origin" to "https://www.youtube.com",
                                        "Referer" to "https://www.youtube.com/"
                                    )
                                }
                            )
                            linksAdded++
                        }
                    }
                }
            }
            
            // Parse HLS manifest if available
            val hlsManifestUrl = streamingData.optString("hlsManifestUrl")
            if (hlsManifestUrl.isNotEmpty()) {
                Log.d("YouTubeAlternativeExtractor", "Adding HLS manifest")
                
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "${this.name} - HLS",
                        hlsManifestUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://www.youtube.com/"
                        this.quality = Qualities.Unknown.value
                        // Add YouTube-specific headers for HLS access
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Origin" to "https://www.youtube.com",
                            "Referer" to "https://www.youtube.com/"
                        )
                    }
                )
                linksAdded++
            }
            
            Log.d("YouTubeAlternativeExtractor", "Alternative extraction added $linksAdded links")
            
            // Parse captions/subtitles
            val captions = playerResponse.optJSONObject("captions")
            if (captions != null) {
                val captionTracks = captions.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks")
                
                if (captionTracks != null) {
                    for (i in 0 until captionTracks.length()) {
                        val track = captionTracks.getJSONObject(i)
                        val baseUrl = track.optString("baseUrl")
                        val languageCode = track.optString("languageCode")
                        
                        if (baseUrl.isNotEmpty() && languageCode.isNotEmpty()) {
                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    lang = languageCode,
                                    url = baseUrl
                                )
                            )
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeExtractor", "Error parsing player response: ${e.message}")
        }
    }
    
    private fun parseQualityFromLabel(qualityLabel: String): Int {
        return when {
            qualityLabel.contains("2160") || qualityLabel.contains("4K") -> 2160
            qualityLabel.contains("1440") -> 1440
            qualityLabel.contains("1080") -> 1080
            qualityLabel.contains("720") -> 720
            qualityLabel.contains("480") -> 480
            qualityLabel.contains("360") -> 360
            qualityLabel.contains("240") -> 240
            qualityLabel.contains("144") -> 144
            else -> Qualities.Unknown.value
        }
    }
}