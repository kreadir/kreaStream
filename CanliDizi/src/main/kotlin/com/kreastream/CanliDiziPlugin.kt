package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CanliDiziPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CanliDizi())
    }
}
