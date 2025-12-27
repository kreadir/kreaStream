package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import okhttp3.Interceptor

class IPTVProvider(override var mainUrl: String, override var name: String) : MainAPI() {
    override val hasMainPage = true
    override var lang = "un"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Live
    )

    private val allPlaylists = mutableMapOf<String, Playlist?>()
    private val headers = mapOf("User-Agent" to "Player (Linux; Android 14)")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val savedLinks = getKey<Array<Link>>("iptv_links")?.sortedBy { it.order }?.filter { it.isActive } ?: emptyList()
        
        if (savedLinks.isEmpty()) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "No Active IPTV Links", 
                        listOf(
                            newTvSeriesSearchResponse(
                                name = "Add IPTV links in settings or activate existing ones",
                                url = "empty",
                                type = TvType.TvSeries
                            )
                        ), 
                        isHorizontalImages = true
                    )
                ),
                hasNext = false
            )
        }

        val homePageSections = mutableListOf<HomePageList>()
        val generalSeriesList = mutableListOf<SearchResponse>()
        val generalLiveList = mutableListOf<SearchResponse>()
        
        savedLinks.forEach { link ->
            try {
                // Try to fetch and parse the playlist
                println("IPTV Debug - Fetching playlist from: ${link.link}")
                val playlistContent = app.get(link.link, headers = headers, timeout = 30).text
                val playlist = IptvPlaylistParser().parseM3U(playlistContent)
                allPlaylists[link.name] = playlist
                
                if (link.showAsEpisodes) {
                    // Create a series for this IPTV link
                    val seriesResponse = newTvSeriesSearchResponse(
                        name = link.name,
                        url = "series:${link.name}",
                        type = TvType.TvSeries
                    ) {
                        this.posterUrl = playlist.items.firstOrNull()?.attributes?.get("tvg-logo")
                    }
                    
                    if (link.showAsSection) {
                        // Add as separate section
                        homePageSections.add(
                            HomePageList(link.name, listOf(seriesResponse), isHorizontalImages = true)
                        )
                    } else {
                        // Add to general IPTV series list
                        generalSeriesList.add(seriesResponse)
                    }
                } else {
                    // Handle as normal live streams grouped by categories
                    val liveChannels = playlist.items.groupBy { it.attributes["group-title"] }.map { group ->
                        val title = group.key ?: "Unknown"
                        val channels = group.value.map { item ->
                            val streamurl = item.url.toString()
                            val channelname = item.title.toString()
                            val posterurl = item.attributes["tvg-logo"].toString()
                            val chGroup = item.attributes["group-title"].toString()
                            val key = item.attributes["key"].toString()
                            val keyid = item.attributes["keyid"].toString()

                            newLiveSearchResponse(
                                name = channelname,
                                url = LoadData(
                                    streamurl,
                                    channelname,
                                    posterurl,
                                    chGroup,
                                    key,
                                    keyid
                                ).toJson(),
                                type = TvType.Live
                            ) { this.posterUrl = posterurl }
                        }
                        
                        if (link.showAsSection) {
                            // Add each group as separate section
                            HomePageList("${link.name} - $title", channels, isHorizontalImages = true)
                        } else {
                            // Add to general live list
                            generalLiveList.addAll(channels)
                            null
                        }
                    }.filterNotNull()
                    
                    if (link.showAsSection) {
                        homePageSections.addAll(liveChannels)
                    }
                }
                
            } catch (e: Exception) {
                // If failed to load, still add an entry but mark as error
                val errorResponse = newTvSeriesSearchResponse(
                    name = "${link.name} (Error)",
                    url = "error:${link.name}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = null
                }
                
                if (link.showAsSection) {
                    // Add as separate section even if error
                    homePageSections.add(
                        HomePageList("${link.name} (Error)", listOf(errorResponse), isHorizontalImages = true)
                    )
                } else {
                    // Add to general series list
                    generalSeriesList.add(errorResponse)
                }
            }
        }

        // Add general sections if there are any items
        if (generalSeriesList.isNotEmpty()) {
            homePageSections.add(0, HomePageList("IPTV Series", generalSeriesList, isHorizontalImages = true))
        }
        if (generalLiveList.isNotEmpty()) {
            homePageSections.add(HomePageList("IPTV Live Channels", generalLiveList, isHorizontalImages = true))
        }

        return newHomePageResponse(homePageSections, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val savedLinks = getKey<Array<Link>>("iptv_links")?.sortedBy { it.order } ?: emptyList()
        val results = mutableListOf<SearchResponse>()
        
        savedLinks.forEach { link ->
            if (link.name.lowercase().contains(query.lowercase())) {
                results.add(
                    newTvSeriesSearchResponse(
                        name = link.name,
                        url = "series:${link.name}",
                        type = TvType.TvSeries
                    ) {
                        this.posterUrl = allPlaylists[link.name]?.items?.firstOrNull()?.attributes?.get("tvg-logo")
                    }
                )
            }
        }

        // Also search within channel names
        allPlaylists.forEach { (linkName, playlist) ->
            playlist?.items?.filter { 
                it.title.toString().lowercase().contains(query.lowercase()) 
            }?.forEach { item ->
                results.add(
                    newTvSeriesSearchResponse(
                        name = "${item.title} (${linkName})",
                        url = "channel:${linkName}:${item.title}",
                        type = TvType.TvSeries
                    ) {
                        this.posterUrl = item.attributes["tvg-logo"]
                    }
                )
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        // Remove leading slash if present
        val cleanUrl = url.removePrefix("/")
        
        // Debug logging
        println("IPTV Debug - Original URL: $url")
        println("IPTV Debug - Clean URL: $cleanUrl")
        
        // Check if this is a LoadData JSON string (for live channels)
        if (cleanUrl.startsWith("{") && cleanUrl.contains("\"url\"")) {
            try {
                val loadData = parseJson<LoadData>(cleanUrl)
                println("IPTV Debug - Handling live channel: ${loadData.title}")
                
                return newLiveStreamLoadResponse(
                    name = loadData.title,
                    url = url,
                    dataUrl = url
                ) {
                    posterUrl = loadData.poster
                    plot = "Live channel: ${loadData.title} from group: ${loadData.group}"
                }
            } catch (e: Exception) {
                println("IPTV Debug - Error parsing LoadData: ${e.message}")
                // Fall through to other URL handlers
            }
        }
        
        when {
            cleanUrl.startsWith("series:") -> {
                val linkName = cleanUrl.removePrefix("series:")
                println("IPTV Debug - Loading series for: $linkName")
                
                val savedLinks = getKey<Array<Link>>("iptv_links")?.sortedBy { it.order } ?: emptyList()
                val link = savedLinks.find { it.name == linkName }
                
                if (link == null) {
                    throw Exception("IPTV link not found: $linkName")
                }

                // Load playlist if not already loaded
                if (allPlaylists[linkName] == null) {
                    try {
                        println("IPTV Debug - Fetching playlist from: ${link.link}")
                        val playlistContent = app.get(link.link, headers = headers, timeout = 30).text
                        allPlaylists[linkName] = IptvPlaylistParser().parseM3U(playlistContent)
                        println("IPTV Debug - Playlist loaded with ${allPlaylists[linkName]?.items?.size} items")
                    } catch (e: Exception) {
                        println("IPTV Debug - Error loading playlist: ${e.message}")
                        throw Exception("Failed to load IPTV playlist: ${e.message}")
                    }
                }

                val playlist = allPlaylists[linkName]!!
                
                if (link.showAsEpisodes) {
                    // Handle as episodes
                    val episodes = playlist.items.mapIndexed { index, item ->
                        val streamurl = item.url.toString()
                        val channelname = item.title.toString()
                        val posterurl = item.attributes["tvg-logo"].toString()
                        val chGroup = item.attributes["group-title"].toString()
                        val key = item.attributes["key"].toString()
                        val keyid = item.attributes["keyid"].toString()

                        newEpisode(
                            data = LoadData(
                                streamurl,
                                channelname,
                                posterurl,
                                chGroup,
                                key,
                                keyid
                            ).toJson()
                        ) {
                            this.name = channelname
                            this.season = 1
                            this.episode = index + 1
                            this.posterUrl = posterurl
                            this.description = "Group: $chGroup"
                        }
                    }

                    println("IPTV Debug - Created ${episodes.size} episodes")
                    return newTvSeriesLoadResponse(
                        name = linkName,
                        url = url,
                        type = TvType.TvSeries,
                        episodes = episodes
                    ) {
                        this.posterUrl = playlist.items.firstOrNull()?.attributes?.get("tvg-logo")
                        this.plot = "IPTV channels from $linkName (${playlist.items.size} channels) - Episodes Mode"
                    }
                } else {
                    // Handle as normal live streams - create a single episode that redirects to live mode
                    val episode = newEpisode(
                        data = "live:$linkName"
                    ) {
                        this.name = "Watch Live Channels"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = playlist.items.firstOrNull()?.attributes?.get("tvg-logo")
                        this.description = "Access live channels from $linkName"
                    }

                    return newTvSeriesLoadResponse(
                        name = linkName,
                        url = url,
                        type = TvType.TvSeries,
                        episodes = listOf(episode)
                    ) {
                        this.posterUrl = playlist.items.firstOrNull()?.attributes?.get("tvg-logo")
                        this.plot = "IPTV channels from $linkName (${playlist.items.size} channels) - Live Mode"
                    }
                }
            }
            
            cleanUrl.startsWith("live:") -> {
                val linkName = cleanUrl.removePrefix("live:")
                return newTvSeriesLoadResponse(
                    name = "$linkName Live Channels",
                    url = url,
                    type = TvType.TvSeries,
                    episodes = emptyList()
                ) {
                    this.plot = "This link is configured for live channel browsing. Check the main page for live channels."
                }
            }
            
            cleanUrl.startsWith("error:") -> {
                val linkName = cleanUrl.removePrefix("error:")
                return newTvSeriesLoadResponse(
                    name = "$linkName (Error)",
                    url = url,
                    type = TvType.TvSeries,
                    episodes = emptyList()
                ) {
                    this.plot = "Failed to load IPTV playlist. Check the link or try again later."
                }
            }
            
            cleanUrl.startsWith("channel:") -> {
                val parts = cleanUrl.removePrefix("channel:").split(":", limit = 2)
                val linkName = parts[0]
                val channelName = parts[1]
                
                val playlist = allPlaylists[linkName]
                val channel = playlist?.items?.find { it.title == channelName }
                
                if (channel != null) {
                    val episode = newEpisode(
                        data = LoadData(
                            channel.url.toString(),
                            channel.title.toString(),
                            channel.attributes["tvg-logo"].toString(),
                            channel.attributes["group-title"].toString(),
                            channel.attributes["key"].toString(),
                            channel.attributes["keyid"].toString()
                        ).toJson()
                    ) {
                        this.name = channel.title.toString()
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = channel.attributes["tvg-logo"]
                        this.description = "Group: ${channel.attributes["group-title"]}"
                    }
                    
                    return newTvSeriesLoadResponse(
                        name = channelName,
                        url = url,
                        type = TvType.TvSeries,
                        episodes = listOf(episode)
                    ) {
                        this.posterUrl = channel.attributes["tvg-logo"]
                        this.plot = "Single channel from $linkName"
                    }
                }
            }
            
            cleanUrl == "empty" -> {
                return newTvSeriesLoadResponse(
                    name = "No IPTV Links",
                    url = url,
                    type = TvType.TvSeries,
                    episodes = emptyList()
                ) {
                    this.plot = "Add IPTV links in the plugin settings to see content."
                }
            }
        }

        throw Exception("Invalid URL format: $url (cleaned: $cleanUrl)")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("live:")) {
            // This shouldn't be called for live mode, but handle gracefully
            return false
        }
        
        try {
            val loadData = parseJson<LoadData>(data)
            
            if (loadData.url.contains(".mpd")) {
                callback.invoke(
                    newDrmExtractorLink(
                        name = this.name,
                        source = loadData.title,
                        url = loadData.url,
                        uuid = CLEARKEY_UUID
                    ) {
                        this.kid = loadData.keyid?.trim() ?: ""
                        this.key = loadData.key?.trim() ?: ""
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = loadData.title,
                        url = loadData.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        } catch (e: Exception) {
            println("IPTV Debug - Error in loadLinks: ${e.message}")
            return false
        }
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                return chain.proceed(request)
            }
        }
    }
}
