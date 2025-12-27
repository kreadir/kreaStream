package com.kreastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class YouTubeTestExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube-Test"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("YouTubeTestExtractor", "TEST: Starting extraction for URL: $url")
        
        try {
            // Test 1: Basic link without NewPipe
            Log.d("YouTubeTestExtractor", "TEST: Adding basic test link")
            callback.invoke(
                newExtractorLink(
                    "Test",
                    "🧪 Test Link (No NewPipe)",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P720.value
                }
            )
            
            // Test 2: Try NewPipe initialization
            Log.d("YouTubeTestExtractor", "TEST: Checking NewPipe...")
            try {
                val serviceList = org.schabi.newpipe.extractor.ServiceList.YouTube
                Log.d("YouTubeTestExtractor", "TEST: ServiceList accessible: ${serviceList.serviceInfo.name}")
                
                // Test 3: Try creating link handler
                val linkHandler = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory.getInstance()
                Log.d("YouTubeTestExtractor", "TEST: LinkHandler created")
                
                val link = linkHandler.fromUrl(url)
                Log.d("YouTubeTestExtractor", "TEST: Link parsed: ${link.url}")
                
                callback.invoke(
                    newExtractorLink(
                        "Test-NewPipe",
                        "✅ NewPipe Link Handler Works",
                        link.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P480.value
                    }
                )
                
            } catch (e: Exception) {
                Log.w("YouTubeTestExtractor", "TEST: NewPipe failed: ${e.message}")
                Log.w("YouTubeTestExtractor", "TEST: Exception type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                
                callback.invoke(
                    newExtractorLink(
                        "Test-Error",
                        "❌ NewPipe Error: ${e.message}",
                        url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P360.value
                    }
                )
            }
            
            Log.d("YouTubeTestExtractor", "TEST: Extraction completed")
            
        } catch (e: Exception) {
            Log.w("YouTubeTestExtractor", "TEST: Overall extraction failed: ${e.message}")
            e.printStackTrace()
        }
    }
}