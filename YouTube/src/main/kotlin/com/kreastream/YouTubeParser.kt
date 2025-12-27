@file:Suppress("DEPRECATION")

package com.kreastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Date

class YouTubeParser(private val api: MainAPI) {
    private val apiName = api.name

    suspend fun getTrendingVideoUrls(page: Int): HomePageList? {
        return try {
            val service = ServiceList.YouTube
            val kiosks = service.kioskList
            val trendingsUrl = kiosks.defaultKioskExtractor.url
            val infoItem = KioskInfo.getInfo(ServiceList.YouTube, trendingsUrl)

            val videos = if (page == 1) {
                infoItem.relatedItems.toMutableList()
            } else {
                mutableListOf<StreamInfoItem>()
            }
            if (page > 1) {
                var hasNext = infoItem.hasNextPage()
                if (!hasNext) {
                    return null
                }
                var count = 1
                var nextPage = infoItem.nextPage
                while (count < page && hasNext) {
                    val more = KioskInfo.getMoreItems(ServiceList.YouTube, trendingsUrl, nextPage)
                    if (count == page - 1) {
                        videos.addAll(more.items)
                    }
                    hasNext = more.hasNextPage()
                    nextPage = more.nextPage
                    count++
                }
            }
            val searchResponses = videos.filter { !it.isShortFormContent }.map {
                api.newMovieSearchResponse(
                    name = it.name,
                    url = it.url,
                    type = TvType.Others,
                ) {
                    this.posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
            HomePageList(
                name = "Trendler",
                list = searchResponses,
                isHorizontalImages = true
            )
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting trending videos: ${e.message}")
            null
        }
    }

    suspend fun playlistToSearchResponseList(url: String, page: Int): HomePageList? {
        return try {
            val playlistInfo = PlaylistInfo.getInfo(url)
            val videos = if (page == 1) {
                playlistInfo.relatedItems.toMutableList()
            } else {
                mutableListOf<StreamInfoItem>()
            }
            if (page > 1) {
                var hasNext = playlistInfo.hasNextPage()
                if (!hasNext) {
                    return null
                }
                var count = 1
                var nextPage = playlistInfo.nextPage
                while (count < page && hasNext) {
                    val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
                    if (count == page - 1) {
                        videos.addAll(more.items)
                    }
                    hasNext = more.hasNextPage()
                    nextPage = more.nextPage
                    count++
                }
            }
            val searchResponses = videos.map {
                api.newMovieSearchResponse(
                    name = it.name,
                    url = it.url,
                    type = TvType.Others,
                ) {
                    this.posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
            HomePageList(
                name = "${playlistInfo.uploaderName}: ${playlistInfo.name}",
                list = searchResponses,
                isHorizontalImages = true
            )
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting playlist: ${e.message}")
            null
        }
    }

    suspend fun channelToSearchResponseList(url: String, page: Int): HomePageList? {
        return try {
            val channelInfo = ChannelInfo.getInfo(url)
            val tabsLinkHandlers = channelInfo.tabs
            val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
            val videoTab = tabs.first { it.name == "videos" }

            val videos = if (page == 1) {
                videoTab.relatedItems.toMutableList()
            } else {
                mutableListOf<InfoItem>()
            }

            if (page > 1) {
                var hasNext = videoTab.hasNextPage()
                if (!hasNext) {
                    return null
                }
                var count = 1
                var nextPage = videoTab.nextPage
                while (count < page && hasNext) {

                    val videoTabHandler = tabsLinkHandlers.first{it.url.endsWith("/videos")}
                    val more = ChannelTabInfo.getMoreItems(ServiceList.YouTube, videoTabHandler, nextPage)
                    if (count == page - 1) {
                        videos.addAll(more.items)
                    }
                    hasNext = more.hasNextPage()
                    nextPage = more.nextPage
                    count++
                }
            }
            val searchResponses = videos.map {
                api.newMovieSearchResponse(
                    name = it.name,
                    url = it.url,
                    type = TvType.Others,
                ) {
                    this.posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
            HomePageList(
                name = channelInfo.name,
                list = searchResponses,
                isHorizontalImages = true
            )
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting channel: ${e.message}")
            null
        }
    }

    suspend fun search(
        query: String,
        contentFilter: String = "videos",
    ): List<SearchResponse> {
        return try {
            val handlerFactory = ServiceList.YouTube.searchQHFactory
            val searchHandler = handlerFactory.fromQuery(
                query,
                listOf(contentFilter),
                null
            )

            val searchInfo = SearchInfo.getInfo(ServiceList.YouTube, SearchQueryHandler(searchHandler))

            val resultSize = searchInfo.relatedItems.size
            if (resultSize <= 0) {
                return emptyList()
            }

            val pageResults = searchInfo.relatedItems.toMutableList()
            var nextPage = searchInfo.nextPage
            for (i in 1..3) {
                val more = SearchInfo.getMoreItems(ServiceList.YouTube, searchHandler, nextPage)
                pageResults.addAll(more.items)
                if (!more.hasNextPage()) break
                nextPage = more.nextPage
            }

            val finalResults = pageResults.mapNotNull {
                when (it.infoType) {
                    InfoType.PLAYLIST, InfoType.CHANNEL -> {
                        api.newTvSeriesSearchResponse(
                            name = it.name,
                            url = it.url,
                            type = TvType.Others,
                        ) {
                            this.posterUrl = it.thumbnails.lastOrNull()?.url
                        }
                    }

                    InfoType.STREAM -> {
                        api.newMovieSearchResponse(
                            name = it.name,
                            url = it.url,
                            type = TvType.Others,
                        ) {
                            this.posterUrl = it.thumbnails.lastOrNull()?.url
                        }
                    }

                    else -> {
                        null
                    }
                }
            }
            finalResults
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error searching: ${e.message}")
            emptyList()
        }
    }

    suspend fun videoToLoadResponse(videoUrl: String): LoadResponse {
        return try {
            val videoInfo = StreamInfo.getInfo(videoUrl)
            val views = "Views: ${videoInfo.viewCount}"
            val likes = "Likes: ${videoInfo.likeCount}"
            val length = videoInfo.duration / 60
            api.newMovieLoadResponse(
                name = videoInfo.name,
                url = videoUrl,
                type = TvType.Others,
                dataUrl = videoUrl,
            ) {
                this.posterUrl = videoInfo.thumbnails.lastOrNull()?.url
                this.plot = videoInfo.description?.content
                this.tags = listOf(videoInfo.uploaderName, views, likes)
                this.duration = length.toInt()
            }
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting video info: ${e.message}")
            // Fallback response
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
            val channelInfo = ChannelInfo.getInfo(url)
            val avatars = try {
                channelInfo.avatars.lastOrNull()?.url
            } catch (e: Exception){
                null
            }
            val banners = try {
                channelInfo.banners.lastOrNull()?.url
            } catch (e: Exception){
                null
            }
            val tags = mutableListOf("Subscribers: ${channelInfo.subscriberCount}")
            api.newTvSeriesLoadResponse(
                name = channelInfo.name,
                url = url,
                type = TvType.Others,
                episodes = getChannelVideos(channelInfo),
            ) {
                this.posterUrl = avatars
                this.backgroundPosterUrl = banners
                this.plot = channelInfo.description
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting channel info: ${e.message}")
            // Fallback response
            api.newTvSeriesLoadResponse(
                name = "YouTube Channel",
                url = url,
                type = TvType.Others,
                episodes = emptyList(),
            )
        }
    }

    private suspend fun getChannelVideos(channel: ChannelInfo): List<Episode> {
        return try {
            val tabsLinkHandlers = channel.tabs
            val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
            val videoTab = tabs.first { it.name == "videos" }
            val videos = videoTab.relatedItems.mapNotNull {
                api.newEpisode(it.url) {
                    this.name = it.name
                    this.posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
            videos.reversed()
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting channel videos: ${e.message}")
            emptyList()
        }
    }

    suspend fun playlistToLoadResponse(url: String): LoadResponse {
        return try {
            val playlistInfo = PlaylistInfo.getInfo(url)
            val tags = mutableListOf("Channel: ${playlistInfo.uploaderName}")
            val banner =
                if (playlistInfo.banners.isNotEmpty()) playlistInfo.banners.lastOrNull()?.url else playlistInfo.thumbnails.lastOrNull()?.url
            val eps = playlistInfo.relatedItems.toMutableList()
            var hasNext = playlistInfo.hasNextPage()
            var count = 1
            var nextPage = playlistInfo.nextPage
            while (hasNext) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
                eps.addAll(more.items)
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
                if (count >= 10) break
            }
            api.newTvSeriesLoadResponse(
                name = playlistInfo.name,
                url = url,
                type = TvType.Others,
                episodes = getPlaylistVideos(eps),
            ) {
                this.posterUrl = playlistInfo.thumbnails.lastOrNull()?.url
                this.backgroundPosterUrl = banner
                this.plot = playlistInfo.description?.content
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting playlist info: ${e.message}")
            // Fallback response
            api.newTvSeriesLoadResponse(
                name = "YouTube Playlist",
                url = url,
                type = TvType.Others,
                episodes = emptyList(),
            )
        }
    }

    private suspend fun getPlaylistVideos(videos: List<StreamInfoItem>): List<Episode> {
        return try {
            val episodes = videos.map { video ->
                api.newEpisode(video.url) {
                    this.name = video.name
                    this.posterUrl = video.thumbnails.lastOrNull()?.url
                    this.runTime = (video.duration / 60).toInt()
                }
            }
            episodes
        } catch (e: Exception) {
            Log.w("YouTubeParser", "Error getting playlist videos: ${e.message}")
            emptyList()
        }
    }
}