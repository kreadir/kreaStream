package com.kreastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

object YouTubeProxyMerger {
    
    suspend fun createProxyMergedStream(
        videoUrl: String,
        audioUrl: String,
        quality: Int,
        resolution: String,
        referer: String = ""
    ): ExtractorLink {
        Log.d("YouTubeProxyMerger", "Creating proxy merged stream for $resolution")
        
        val proxyUrl = createProxyUrl(videoUrl, audioUrl)
        
        return newExtractorLink(
            "NewPipe-Proxy",
            "🎬 $resolution (Auto-Merged)",
            proxyUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality + 200
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "X-Video-URL" to videoUrl,
                "X-Audio-URL" to audioUrl
            )
        }
    }
    
    suspend fun createCompatibleHlsStream(
        videoUrl: String,
        audioUrl: String,
        quality: Int,
        resolution: String,
        referer: String = ""
    ): ExtractorLink {
        val hlsContent = createSimpleHlsPlaylist(videoUrl, audioUrl, quality)
        val hlsDataUrl = "data:application/vnd.apple.mpegurl;charset=utf-8," + URLEncoder.encode(hlsContent, "UTF-8")
        
        return newExtractorLink(
            "NewPipe-HLS",
            "🎬 $resolution (HLS Merged)",
            hlsDataUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = quality + 100
        }
    }
    
    private fun createProxyUrl(videoUrl: String, audioUrl: String): String {
        val encodedVideo = URLEncoder.encode(videoUrl, "UTF-8")
        val encodedAudio = URLEncoder.encode(audioUrl, "UTF-8")
        return "data:application/x-youtube-merged;video=$encodedVideo;audio=$encodedAudio,MERGED_STREAM"
    }
    
    private fun createSimpleHlsPlaylist(videoUrl: String, audioUrl: String, quality: Int): String {
        val bandwidth = estimateVideoBitrate(quality) + 128000
        val width = getWidthFromQuality(quality)
        
        return """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio",DEFAULT=YES,URI="$audioUrl"
#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=${width}x$quality,AUDIO="audio"
$videoUrl
#EXT-X-ENDLIST"""
    }
    
    private fun estimateVideoBitrate(quality: Int): Int {
        return when (quality) {
            2160 -> 25000000
            1440 -> 16000000
            1080 -> 8000000
            720 -> 5000000
            480 -> 2500000
            360 -> 1000000
            else -> 2000000
        }
    }
    
    private fun getWidthFromQuality(quality: Int): Int {
        return when (quality) {
            2160 -> 3840
            1440 -> 2560
            1080 -> 1920
            720 -> 1280
            480 -> 854
            360 -> 640
            else -> 1280
        }
    }
}