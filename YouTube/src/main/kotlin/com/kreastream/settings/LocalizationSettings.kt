package com.kreastream.settings

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.kreastream.BuildConfig
import com.kreastream.YouTubePlugin
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * A simple [Fragment] subclass.
 * Use the [LocalizationSettings] factory method to
 * create an instance of this fragment.
 */
class LocalizationSettings(private val plugin: YouTubePlugin, val sharedPref: SharedPreferences?) :
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
            "localization_settings",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        )
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val headerTw = view.findView<TextView>("header_tw")
        headerTw.text = getString("changeLocalizationHeader_tw")

        val language_tw = view.findView<TextView>("lang_tw")
        val country_tw = view.findView<TextView>("country_tw")

        language_tw.text = getString("language_tw")
        country_tw.text = getString("country_tw")

        val languageEditText = view.findView<EditText>("editText_language")
        val countryEditText = view.findView<EditText>("editText_country")

        languageEditText.hint = getString("language_hint")
        countryEditText.hint = getString("country_hint")


        val saveButton = view.findView<ImageButton>("save_button")
        saveButton.setImageDrawable(getDrawable("save_icon"))
        saveButton.makeTvCompatible()

        saveButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val language = languageEditText.text?.trim()?.toString()
                val country = countryEditText.text?.trim()?.toString()
                if (!language.isNullOrEmpty() && !country.isNullOrEmpty() && language.length == 2 && country.length == 2) {
                    NewPipe.setupLocalization(
                        Localization(language.lowercase()),
                        ContentCountry(country.uppercase())
                    )

                    with(sharedPref?.edit()) {
                        this?.putString("language", language.lowercase())
                        this?.putString("country", country.uppercase())
                        this?.apply()
                    }

                    showToast("Saved!\n Restart the app for the changes to take effect")
                    dismiss()
                } else {
                    showToast("Be sure to fill both fields with the 2 ISO characters")
                }

            }
        })

    }
}
