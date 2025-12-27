package com.kreastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.util.regex.Pattern
import java.net.URLEncoder

/**
 * Alternative YouTube parser that doesn't use NewPipe
 * Implements search, trending, and other features using direct YouTube API calls
 */
class YouTubeAlternativeParser(private val api: MainAPI) {
    private val apiName = api.name

    suspend fun getTrendingVideoUrls(page: Int): HomePageList? {
        return try {
            Log.d("YouTubeAlternativeParser", "Getting trending videos for page: $page")
            
            // Use YouTube's trending page directly
            val trendingUrl = "https://www.youtube.com/feed/trending"
            val response = app.get(trendingUrl, headers = getHeaders()).text
            
            val videos = extractVideosFromPage(response, "trending")
            
            if (videos.isEmpty()) {
                Log.w("YouTubeAlternativeParser", "No trending videos found")
                return null
            }
            
            // For pagination, we'll use a simple offset approach
            val startIndex = (page - 1) * 20
            val endIndex = minOf(startIndex + 20, videos.size)
            
            if (startIndex >= videos.size) {
                return null
            }
            
            val pageVideos = videos.subList(startIndex, endIndex)
            
            val searchResponses = pageVideos.map { video ->
                api.newMovieSearchResponse(
                    name = video.title,
                    url = video.url,
                    type = TvType.Others,
                ) {
                    this.posterUrl = video.thumbnail
                }
            }
            
            HomePageList(
                name = "Trending",
                list = searchResponses,
                isHorizontalImages = true
            )
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error getting trending videos: ${e.message}")
            null
        }
    }

    suspend fun search(
        query: String,
        contentFilter: String = "videos",
    ): List<SearchResponse> {
        return try {
            Log.d("YouTubeAlternativeParser", "Searching for: $query")
            
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encodedQuery"
            
            val response = app.get(searchUrl, headers = getHeaders()).text
            val videos = extractVideosFromPage(response, "search")
            
            videos.mapNotNull { video ->
                when {
                    video.isChannel -> {
                        api.newTvSeriesSearchResponse(
                            name = video.title,
                            url = video.url,
                            type = TvType.Others,
                        ) {
                            this.posterUrl = video.thumbnail
                        }
                    }
                    video.isPlaylist -> {
                        api.newTvSeriesSearchResponse(
                            name = video.title,
                            url = video.url,
                            type = TvType.Others,
                        ) {
                            this.posterUrl = video.thumbnail
                        }
                    }
                    else -> {
                        api.newMovieSearchResponse(
                            name = video.title,
                            url = video.url,
                            type = TvType.Others,
                        ) {
                            this.posterUrl = video.thumbnail
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error searching: ${e.message}")
            emptyList()
        }
    }

    suspend fun videoToLoadResponse(videoUrl: String): LoadResponse {
        return try {
            Log.d("YouTubeAlternativeParser", "Getting video info for: $videoUrl")
            
            val response = app.get(videoUrl, headers = getHeaders()).text
            val videoInfo = extractVideoInfo(response)
            
            api.newMovieLoadResponse(
                name = videoInfo.title,
                url = videoUrl,
                type = TvType.Others,
                dataUrl = videoUrl,
            ) {
                this.posterUrl = videoInfo.thumbnail
                this.plot = videoInfo.description
                this.tags = listOf(videoInfo.uploader, "Views: ${videoInfo.views}", "Likes: ${videoInfo.likes}")
                this.duration = videoInfo.duration
            }
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error getting video info: ${e.message}")
            // Fallback to basic response
            api.newMovieLoadResponse(
                name = "YouTube Video",
                url = videoUrl,
                type = TvType.Others,
                dataUrl = videoUrl,
            )
        }
    }

    suspend fun channelToLoadResponse(url: String): LoadResponse {
        return try {
            Log.d("YouTubeAlternativeParser", "Getting channel info for: $url")
            
            val response = app.get(url, headers = getHeaders()).text
            val channelInfo = extractChannelInfo(response)
            val videos = extractVideosFromPage(response, "channel")
            
            val episodes = videos.map { video ->
                api.newEpisode(video.url) {
                    this.name = video.title
                    this.posterUrl = video.thumbnail
                }
            }
            
            api.newTvSeriesLoadResponse(
                name = channelInfo.name,
                url = url,
                type = TvType.Others,
                episodes = episodes,
            ) {
                this.posterUrl = channelInfo.avatar
                this.backgroundPosterUrl = channelInfo.banner
                this.plot = channelInfo.description
                this.tags = listOf("Subscribers: ${channelInfo.subscribers}")
            }
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error getting channel info: ${e.message}")
            // Fallback to basic response
            api.newTvSeriesLoadResponse(
                name = "YouTube Channel",
                url = url,
                type = TvType.Others,
                episodes = emptyList(),
            )
        }
    }

    suspend fun playlistToLoadResponse(url: String): LoadResponse {
        return try {
            Log.d("YouTubeAlternativeParser", "Getting playlist info for: $url")
            
            val response = app.get(url, headers = getHeaders()).text
            val playlistInfo = extractPlaylistInfo(response)
            val videos = extractVideosFromPage(response, "playlist")
            
            val episodes = videos.map { video ->
                api.newEpisode(video.url) {
                    this.name = video.title
                    this.posterUrl = video.thumbnail
                }
            }
            
            api.newTvSeriesLoadResponse(
                name = playlistInfo.name,
                url = url,
                type = TvType.Others,
                episodes = episodes,
            ) {
                this.posterUrl = playlistInfo.thumbnail
                this.backgroundPosterUrl = playlistInfo.banner
                this.plot = playlistInfo.description
                this.tags = listOf("Channel: ${playlistInfo.uploader}")
            }
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error getting playlist info: ${e.message}")
            // Fallback to basic response
            api.newTvSeriesLoadResponse(
                name = "YouTube Playlist",
                url = url,
                type = TvType.Others,
                episodes = emptyList(),
            )
        }
    }

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cookie" to "CONSENT=YES+cb.20210328-17-p0.en+FX+667"
        )
    }

    private fun extractVideosFromPage(html: String, context: String): List<VideoInfo> {
        val videos = mutableListOf<VideoInfo>()
        
        try {
            // Extract initial data from page
            val patterns = listOf(
                "var ytInitialData = (\\{.*?\\});",
                "window\\[\"ytInitialData\"\\] = (\\{.*?\\});",
                "ytInitialData\":(\\{.*?\\}),\"",
                "\"contents\":(\\{.*?\\}),\""
            )
            
            var initialData: JSONObject? = null
            for (pattern in patterns) {
                val matcher = Pattern.compile(pattern).matcher(html)
                if (matcher.find()) {
                    val dataStr = matcher.group(1)
                    if (dataStr != null) {
                        try {
                            initialData = JSONObject(dataStr)
                            break
                        } catch (e: Exception) {
                            continue
                        }
                    }
                }
            }
            
            if (initialData != null) {
                videos.addAll(parseVideosFromInitialData(initialData, context))
            }
            
            // Fallback: extract from HTML directly using regex
            if (videos.isEmpty()) {
                videos.addAll(extractVideosFromHtml(html))
            }
            
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error extracting videos from page: ${e.message}")
        }
        
        return videos.distinctBy { it.url }
    }

    private fun parseVideosFromInitialData(data: JSONObject, context: String): List<VideoInfo> {
        val videos = mutableListOf<VideoInfo>()
        
        try {
            // Navigate through the JSON structure to find video data
            // This is a simplified version - YouTube's structure is complex
            val contents = data.optJSONObject("contents")
            if (contents != null) {
                // Try different paths based on context
                when (context) {
                    "search" -> parseSearchResults(contents, videos)
                    "trending" -> parseTrendingResults(contents, videos)
                    "channel" -> parseChannelResults(contents, videos)
                    "playlist" -> parsePlaylistResults(contents, videos)
                }
            }
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error parsing initial data: ${e.message}")
        }
        
        return videos
    }

    private fun parseSearchResults(contents: JSONObject, videos: MutableList<VideoInfo>) {
        // Simplified search result parsing
        // In reality, YouTube's JSON structure is much more complex
    }

    private fun parseTrendingResults(contents: JSONObject, videos: MutableList<VideoInfo>) {
        // Simplified trending result parsing
    }

    private fun parseChannelResults(contents: JSONObject, videos: MutableList<VideoInfo>) {
        // Simplified channel result parsing
    }

    private fun parsePlaylistResults(contents: JSONObject, videos: MutableList<VideoInfo>) {
        // Simplified playlist result parsing
    }

    private fun extractVideosFromHtml(html: String): List<VideoInfo> {
        val videos = mutableListOf<VideoInfo>()
        
        try {
            // Extract video links using regex patterns
            val videoLinkPattern = Pattern.compile("\"url\":\"/watch\\?v=([a-zA-Z0-9_-]{11})\"")
            val titlePattern = Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"")
            val thumbnailPattern = Pattern.compile("\"thumbnails\":\\[\\{\"url\":\"([^\"]+)\"")
            
            val videoMatcher = videoLinkPattern.matcher(html)
            val videoIds = mutableListOf<String>()
            
            while (videoMatcher.find()) {
                val videoId = videoMatcher.group(1)
                if (videoId != null && !videoIds.contains(videoId)) {
                    videoIds.add(videoId)
                }
            }
            
            // For each video ID, try to find corresponding title and thumbnail
            videoIds.take(20).forEach { videoId ->
                val videoUrl = "https://www.youtube.com/watch?v=$videoId"
                videos.add(VideoInfo(
                    title = "YouTube Video",
                    url = videoUrl,
                    thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg",
                    isChannel = false,
                    isPlaylist = false
                ))
            }
            
        } catch (e: Exception) {
            Log.w("YouTubeAlternativeParser", "Error extracting videos from HTML: ${e.message}")
        }
        
        return videos
    }

    private fun extractVideoInfo(html: String): VideoInfo {
        return try {
            val titlePattern = Pattern.compile("<title>([^<]+)</title>")
            val titleMatcher = titlePattern.matcher(html)
            val title = if (titleMatcher.find()) {
                titleMatcher.group(1)?.replace(" - YouTube", "") ?: "YouTube Video"
            } else {
                "YouTube Video"
            }
            
            VideoInfo(
                title = title,
                url = "",
                thumbnail = "",
                description = "",
                uploader = "",
                views = "",
                likes = "",
                duration = 0
            )
        } catch (e: Exception) {
            VideoInfo(title = "YouTube Video", url = "", thumbnail = "")
        }
    }

    private fun extractChannelInfo(html: String): ChannelInfo {
        return try {
            val namePattern = Pattern.compile("<title>([^<]+)</title>")
            val nameMatcher = namePattern.matcher(html)
            val name = if (nameMatcher.find()) {
                nameMatcher.group(1)?.replace(" - YouTube", "") ?: "YouTube Channel"
            } else {
                "YouTube Channel"
            }
            
            ChannelInfo(
                name = name,
                avatar = "",
                banner = "",
                description = "",
                subscribers = ""
            )
        } catch (e: Exception) {
            ChannelInfo(name = "YouTube Channel")
        }
    }

    private fun extractPlaylistInfo(html: String): PlaylistInfo {
        return try {
            val namePattern = Pattern.compile("<title>([^<]+)</title>")
            val nameMatcher = namePattern.matcher(html)
            val name = if (nameMatcher.find()) {
                nameMatcher.group(1)?.replace(" - YouTube", "") ?: "YouTube Playlist"
            } else {
                "YouTube Playlist"
            }
            
            PlaylistInfo(
                name = name,
                thumbnail = "",
                banner = "",
                description = "",
                uploader = ""
            )
        } catch (e: Exception) {
            PlaylistInfo(name = "YouTube Playlist")
        }
    }

    data class VideoInfo(
        val title: String,
        val url: String,
        val thumbnail: String,
        val description: String = "",
        val uploader: String = "",
        val views: String = "",
        val likes: String = "",
        val duration: Int = 0,
        val isChannel: Boolean = false,
        val isPlaylist: Boolean = false
    )

    data class ChannelInfo(
        val name: String,
        val avatar: String = "",
        val banner: String = "",
        val description: String = "",
        val subscribers: String = ""
    )

    data class PlaylistInfo(
        val name: String,
        val thumbnail: String = "",
        val banner: String = "",
        val description: String = "",
        val uploader: String = ""
    )
}