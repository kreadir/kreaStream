package com.kreastream.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.View.generateViewId
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.kreastream.YouTubePlugin
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.kreastream.BuildConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * A simple [Fragment] subclass.
 * Use the [HomepageSettings] factory method to
 * create an instance of this fragment.
 */
class HomepageSettings(
    private val plugin: YouTubePlugin,
    val sharedPref: SharedPreferences?,
) :
    BottomSheetDialogFragment() {

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        this.setPadding(this.paddingLeft + 10,this.paddingTop + 10,this.paddingRight + 10,this.paddingBottom + 10)
        this.background = getDrawable("outline")
    }

    private fun getDrawable(name: String): Drawable? {
        val id =
            plugin.resources!!.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return ResourcesCompat.getDrawable(plugin.resources!!, id, null)
    }

    private fun getString(name: String): String? {
        val id =
            plugin.resources!!.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return plugin.resources!!.getString(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        val id = plugin.resources!!.getIdentifier(
            "homepage_settings",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        )
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val headerTw = view.findView<TextView>("header_tw")
        headerTw.text = getString("changeHomepageHeader_tw")

        val trendingSwitch = view.findView<Switch>("trending_switch")
        trendingSwitch.text = getString("trending_text")
        trendingSwitch.isChecked = sharedPref?.getBoolean("trending", true) ?: true

        val addPlaylistTw = view.findView<TextView>("addPlaylist_tw")
        addPlaylistTw.text = getString("addPlaylist_tw")

        val youtubeUrlEt = view.findView<TextView>("youtubeUrl_editText")
        youtubeUrlEt.hint = getString("add_playlist_hint")

        var playlistsSet = mutableSetOf<String>()
        sharedPref?.getStringSet("playlists", emptySet())?.let {
            playlistsSet.addAll(it)
            Log.d("YoutubeSettings", "Playlists: $playlistsSet")
        }
        val playlistsList = view.findView<LinearLayout>("playlists_list")

        val tripleList = playlistsSet.map {
            parseJson<Triple<String, String, Long>>(it)
        }.sortedBy { it.third }
        tripleList.forEach {
            playlistsList.addView(
                playlistsRow(it, sharedPref, playlistsSet, playlistsList)
            )
        }


        val addSectionButton = view.findView<ImageButton>("addSection_button")
        addSectionButton.setImageDrawable(getDrawable("add_icon"))
        addSectionButton.makeTvCompatible()

        addSectionButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                addSectionButton.isClickable = false
                GlobalScope.launch {
                    val now = System.currentTimeMillis()
                    val item = try {
                        withContext(Dispatchers.IO) {
                            Triple(
                                youtubeUrlEt.text.toString(),
                                getName(youtubeUrlEt.text.toString()),
                                now
                            )
                                .toJson()
                        }
                    } catch (e: NoSuchMethodError) {
                        addSectionButton.isClickable = true
                        showToast("Error")
                        return@launch
                    }

                    Log.d("YoutubeProvider", item)
                    sharedPref?.getStringSet("playlists", emptySet())?.let {
                        playlistsSet = mutableSetOf()
                        playlistsSet.addAll(it)
                        playlistsSet.add(item)
                    }
                    with(sharedPref?.edit()) {
                        this?.putStringSet("playlists", playlistsSet)
                        this?.apply()
                    }
                    withContext(Dispatchers.Main) {
                        youtubeUrlEt.text = ""
                        addSectionButton.isClickable = true
                        playlistsList.addView(
                            playlistsRow(
                                item,
                                sharedPref,
                                playlistsSet,
                                playlistsList
                            )
                        )
                    }
                }
            }
        })


        val saveButton = view.findView<ImageButton>("save_button")
        saveButton.setImageDrawable(getDrawable("save_icon"))
        saveButton.makeTvCompatible()

        saveButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                with(sharedPref?.edit()) {
                    this?.putBoolean("trending", trendingSwitch.isChecked)
                    this?.apply()
                }
                dismiss()
            }
        })

    }

    private fun playlistsRow(
        itemjson: String,
        sharedPref: SharedPreferences?,
        playlistsSet: MutableSet<String>,
        playlistList: LinearLayout,
    ): RelativeLayout {
        val item = parseJson<Triple<String, String, Long>>(itemjson)
        return playlistsRow(item, sharedPref, playlistsSet, playlistList)
    }

    private fun playlistsRow(
        item: Triple<String, String, Long>,
        sharedPref: SharedPreferences?,
        playlistsSet: MutableSet<String>,
        playlistList: LinearLayout,
    ): RelativeLayout {
        val title = item.second
        // Create the RelativeLayout
        val relativeLayout = RelativeLayout(this@HomepageSettings.requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setPadding(
                    0,
                    0,
                    0,
                    dpToPx(this@HomepageSettings.requireContext(), 8)
                ) // Convert dp to px
            }
        }

        // Create the TextView (Label)
        val label = TextView(this.context).apply {
            text = title
            textSize = 15f
        }

        val labelParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.CENTER_VERTICAL) // Vertically center in parent
            addRule(RelativeLayout.ALIGN_PARENT_START)
            marginEnd = dpToPx(this@HomepageSettings.requireContext(), 8)
        }


        // Create the ImageButton
        val deleteButton = ImageButton(this.context).apply {
            id = generateViewId()
            setImageDrawable(getDrawable("delete_icon"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val buttonParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL) // Vertically center in parent
            marginEnd = dpToPx(this@HomepageSettings.requireContext(), 8) // Convert dp to px
        }

        relativeLayout.addView(label, labelParams)
        relativeLayout.addView(deleteButton, buttonParams)

        val delete = relativeLayout.findViewById<ImageButton>(deleteButton.id)
        delete.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val deleteSuccessfull = playlistsSet.remove(item.toJson())
                if (deleteSuccessfull) {
                    with(sharedPref?.edit()) {
                        this?.putStringSet("playlists", playlistsSet)
                        this?.apply()
                    }
                    playlistList.removeView(relativeLayout)
                    showToast("$title removed")
                }
            }
        })
        return relativeLayout
    }

    private suspend fun getName(playlistUrl: String): String? {
        val urlPath = playlistUrl.substringAfter("youtu").substringAfter("/")
        val isPlaylist = urlPath.startsWith("playlist?list=")
        val isChannel = urlPath.startsWith("@") || urlPath.startsWith("channel")

        return withContext(Dispatchers.IO) {
            if (isPlaylist && !isChannel) {
                val playlistInfo = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
                "${playlistInfo.uploaderName}: ${playlistInfo.name}"
            } else if (!isPlaylist && isChannel) {
                ChannelInfo.getInfo(ServiceList.YouTube, playlistUrl).name
            } else {
                "Unknown"
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
