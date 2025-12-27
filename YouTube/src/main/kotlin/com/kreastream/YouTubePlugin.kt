package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.kreastream.settings.SettingsFragment
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import okhttp3.OkHttpClient

@CloudstreamPlugin
class YouTubePlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("Youtube", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        android.util.Log.d("YouTubePlugin", "=== PLUGIN LOADING START ===")
        
        var language = sharedPref?.getString("language", "en")
        var country = sharedPref?.getString("country", "US")

        if (language.isNullOrEmpty()) {language = "en"}
        if (country.isNullOrEmpty()) {country = "US"}
        
        android.util.Log.d("YouTubePlugin", "Language: $language, Country: $country")

        // Initialize NewPipe with better error handling
        try {
            android.util.Log.d("YouTubePlugin", "Initializing NewPipe with language: $language, country: $country")
            
            // Use simple initialization to minimize errors
            NewPipe.init(NewPipeDownloader.getInstance())
            NewPipe.setupLocalization(Localization(language), ContentCountry(country))
            
            android.util.Log.d("YouTubePlugin", "NewPipe initialized successfully")
            
            // Start proxy server
            // val proxyPort = YouTubeProxyServer.start()
            // if (proxyPort > 0) {
            //     android.util.Log.d("YouTubePlugin", "Proxy server started on port $proxyPort")
            // } else {
            //     android.util.Log.w("YouTubePlugin", "Failed to start proxy server")
            // }
            
        } catch (e: Exception) {
            android.util.Log.w("YouTubePlugin", "NewPipe initialization failed: ${e.message}")
            
            // Try with default settings
            try {
                NewPipe.init(NewPipeDownloader.getInstance())
                NewPipe.setupLocalization(Localization("en"), ContentCountry("US"))
                android.util.Log.d("YouTubePlugin", "NewPipe initialized with defaults")
            } catch (e2: Exception) {
                android.util.Log.w("YouTubePlugin", "NewPipe initialization completely failed: ${e2.message}")
            }
        }

        // All providers should be added in this manner
        android.util.Log.d("YouTubePlugin", "Registering providers...")
        registerMainAPI(YouTubeProvider(language, sharedPref))
        android.util.Log.d("YouTubePlugin", "YouTubeProvider registered")
        registerMainAPI(YouTubePlaylistsProvider(language))
        android.util.Log.d("YouTubePlugin", "YouTubePlaylistsProvider registered")
        registerMainAPI(YouTubeChannelProvider(language))
        android.util.Log.d("YouTubePlugin", "YouTubeChannelProvider registered")
        registerExtractorAPI(YouTubeTestExtractor()) // Test extractor first
        android.util.Log.d("YouTubePlugin", "YouTubeTestExtractor registered")
        registerExtractorAPI(YouTubeExtractor())
        android.util.Log.d("YouTubePlugin", "YouTubeExtractor registered")
        // Alternative extractor disabled - only provides non-working 360p streams
        // registerExtractorAPI(YouTubeAlternativeExtractor())
        // DASH extractor disabled - CloudStream3 doesn't support custom DASH URLs

        openSettings = {ctx ->
            val activity = ctx as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
        
        android.util.Log.d("YouTubePlugin", "=== PLUGIN LOADING COMPLETE ===")
    }
}
