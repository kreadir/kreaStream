package com.kreastream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

class YouTubeChannelProvider(language: String) : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "YouTube Channels"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override var lang = language

    private val ytParser = YouTubeParser(this)

    companion object{
        const val MAIN_URL = "https://www.youtube.com"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val videoUrls = ytParser.search(query, contentFilter = "channels")
        return videoUrls
    }

    override suspend fun load(url: String): LoadResponse {
        val video = ytParser.channelToLoadResponse(url)
        return video
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        YouTubeExtractor().getUrl(data, "", subtitleCallback, callback)
        return true
    }
}