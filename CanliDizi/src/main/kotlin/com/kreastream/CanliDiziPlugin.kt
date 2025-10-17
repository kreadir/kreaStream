package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.kreastream.extractors.BetaplayerExtractor
import com.kreastream.extractors.CanliplayerExtractor

@CloudstreamPlugin
class CanliDiziPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main site plugin
        registerMainAPI(CanliDizi())

        // Register extractors
        registerExtractorAPI(BetaplayerExtractor())
        registerExtractorAPI(CanliplayerExtractor())
    }
}

