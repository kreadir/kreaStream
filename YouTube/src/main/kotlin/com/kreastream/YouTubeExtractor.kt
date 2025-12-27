package com.kreastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.AudioStream

open class YouTubeExtractor(private val hls: Boolean) : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    constructor() : this(true)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("YoutubeExtractor", "Starting extraction for URL: $url")
        
        var linksAdded = 0
        
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
            Log.w("YoutubeExtractor", "Invalid YouTube URL: $url")
            return
        }
        
        try {
            Log.d("YoutubeExtractor", "Using NewPipe extraction...")
            
            val cleanUrl = url.replace(schemaStripRegex, "")
            Log.d("YoutubeExtractor", "Processing URL: $cleanUrl")
            
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanUrl)
            val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}

            Log.d("YoutubeExtractor", "Fetching page with NewPipe...")
            extractor.fetchPage()
            Log.d("YoutubeExtractor", "NewPipe page fetched successfully")

            val videoStreams = try {
                extractor.videoStreams
            } catch (e: Exception) {
                emptyList<VideoStream>()
            }
            
            val videoOnlyStreams = try {
                extractor.videoOnlyStreams
            } catch (e: Exception) {
                emptyList<VideoStream>()
            }
            
            val audioStreams = try {
                extractor.audioStreams
            } catch (e: Exception) {
                emptyList<AudioStream>()
            }

            Log.d("YoutubeExtractor", "NewPipe streams - Video: ${videoStreams.size}, Video-only: ${videoOnlyStreams.size}, Audio: ${audioStreams.size}")
            
            // Process regular video streams (with audio) first
            if (videoStreams.isNotEmpty()) {
                Log.d("YoutubeExtractor", "Processing ${videoStreams.size} regular video streams")
                videoStreams.forEach { stream ->
                    try {
                        val streamUrl = stream.content
                        val resolution = try {
                            stream.resolution
                        } catch (e: Exception) {
                            "${stream.width}x${stream.height}"
                        }
                        
                        val quality = if (!resolution.isNullOrEmpty()) {
                            val height = resolution.substringAfter("x").toIntOrNull() ?: 
                                        resolution.substringBefore("p").toIntOrNull() ?: 
                                        stream.height ?: 
                                        Qualities.Unknown.value
                            height
                        } else {
                            stream.height ?: Qualities.Unknown.value
                        }
                        
                        Log.d("YoutubeExtractor", "Adding NewPipe video stream: quality=$quality, resolution=$resolution")
                        
                        callback.invoke(
                            newExtractorLink(
                                "NewPipe",
                                "✅ ${resolution ?: "${quality}p"} with Audio (Ready to Play)",
                                streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: ""
                                this.quality = quality
                            }
                        )
                        linksAdded++
                    } catch (e: Exception) {
                        Log.d("YoutubeExtractor", "Failed to process NewPipe video stream: ${e.message}")
                    }
                }
            }
            
            // Process video-only streams - create simple combined links
            if (videoOnlyStreams.isNotEmpty() && audioStreams.isNotEmpty()) {
                Log.d("YoutubeExtractor", "Processing ${videoOnlyStreams.size} video-only streams with ${audioStreams.size} audio streams")
                
                val sortedVideoStreams = videoOnlyStreams.sortedByDescending { it.height ?: 0 }
                val sortedAudioStreams = audioStreams.sortedByDescending { it.averageBitrate ?: 0 }
                val bestAudio = sortedAudioStreams.firstOrNull()
                
                if (bestAudio != null) {
                    Log.d("YoutubeExtractor", "Adding video-only streams with note about audio")
                    
                    // Add video-only streams with clear labeling
                    sortedVideoStreams.take(5).forEach { videoStream ->
                        try {
                            val videoUrl = videoStream.content
                            val resolution = try {
                                videoStream.resolution
                            } catch (e: Exception) {
                                "${videoStream.width}x${videoStream.height}"
                            }
                            
                            val quality = if (!resolution.isNullOrEmpty()) {
                                val height = resolution.substringAfter("x").toIntOrNull() ?: 
                                            resolution.substringBefore("p").toIntOrNull() ?: 
                                            videoStream.height ?: 
                                            Qualities.Unknown.value
                                height
                            } else {
                                videoStream.height ?: Qualities.Unknown.value
                            }
                            
                            Log.d("YoutubeExtractor", "Adding video-only stream for ${quality}p")
                            
                            callback.invoke(
                                newExtractorLink(
                                    "NewPipe",
                                    "📹 $resolution Video Only (No Audio)",
                                    videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = quality
                                }
                            )
                            linksAdded++
                            
                        } catch (e: Exception) {
                            Log.d("YoutubeExtractor", "Failed to add video-only stream: ${e.message}")
                        }
                    }
                    
                    // Add audio streams
                    sortedAudioStreams.take(2).forEach { audioStream ->
                        try {
                            val audioUrl = audioStream.content
                            val bitrate = audioStream.averageBitrate ?: 128
                            
                            Log.d("YoutubeExtractor", "Adding audio stream: ${bitrate}kbps")
                            
                            callback.invoke(
                                newExtractorLink(
                                    "NewPipe",
                                    "🔊 Audio ${bitrate}kbps (Use with Video Above)",
                                    audioUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = bitrate
                                }
                            )
                            linksAdded++
                        } catch (e: Exception) {
                            Log.d("YoutubeExtractor", "Failed to add audio stream: ${e.message}")
                        }
                    }
                }
            }
            
            // Use HLS as additional option
            val hlsUrl = try {
                extractor.hlsUrl
            } catch (e: Exception) {
                null
            }
            
            if (!hlsUrl.isNullOrEmpty()) {
                Log.d("YoutubeExtractor", "Adding HLS stream")
                if (hls) {
                    callback.invoke(
                        newExtractorLink(
                            "NewPipe",
                            "NewPipe - HLS (Adaptive)",
                            hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksAdded++
                } else {
                    try {
                        val stream = M3u8Helper.generateM3u8("NewPipe", hlsUrl, "")
                        Log.d("YoutubeExtractor", "Generated ${stream.size} streams from M3u8")
                        stream.forEach {
                            callback.invoke(it)
                            linksAdded++
                        }
                    } catch (e: Exception) {
                        Log.d("YoutubeExtractor", "M3u8 parsing failed: ${e.message}, using direct HLS")
                        callback.invoke(
                            newExtractorLink(
                                "NewPipe",
                                "NewPipe - HLS (Adaptive)",
                                hlsUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer ?: ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        linksAdded++
                    }
                }
            }
            
            Log.d("YoutubeExtractor", "NewPipe extraction completed with $linksAdded total links")
            
            // Get subtitles
            try {
                val subtitles = extractor.subtitlesDefault.filterNotNull()
                subtitles.mapNotNull {
                    newSubtitleFile(
                        lang = it.languageTag ?: return@mapNotNull null,
                        url = it.content ?: return@mapNotNull null
                    )
                }.forEach(subtitleCallback)
            } catch (e: Exception) {
                Log.d("YoutubeExtractor", "Failed to get subtitles: ${e.message}")
            }
            
        } catch (e: Exception) {
            logError(e)
            Log.w("YoutubeExtractor", "NewPipe extraction failed: ${e.message}")
        }
        
        Log.d("YoutubeExtractor", "Total extraction completed with $linksAdded links")
    }
}