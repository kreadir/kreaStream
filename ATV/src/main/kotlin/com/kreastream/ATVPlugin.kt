package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ATVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ATV())
    }
}