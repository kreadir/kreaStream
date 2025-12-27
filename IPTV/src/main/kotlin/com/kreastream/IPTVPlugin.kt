package com.kreastream

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey

@CloudstreamPlugin
class IPTVPlugin : Plugin() {
    override fun load(context: Context) {
        reload()
    }

    init {
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            try {
                val frag = IPTVSettingsFragment(this)
                frag.show(activity.supportFragmentManager, "IPTV")
            } catch (e: Exception) {
            }
        }
    }

    fun reload() {
        try {
            // Register a single IPTV provider that handles all links
            registerMainAPI(IPTVProvider("", "IPTV"))
            MainActivity.afterPluginsLoadedEvent.invoke(true)
        } catch (e: Exception) {
        }
    }
}