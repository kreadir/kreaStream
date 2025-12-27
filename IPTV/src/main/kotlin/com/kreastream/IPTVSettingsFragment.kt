package com.kreastream

import android.view.*
import android.widget.*
import android.os.Bundle
import android.net.Uri
import android.content.*
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

class IPTVSettingsFragment(private val plugin: IPTVPlugin) : BottomSheetDialogFragment() {
    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", "com.kreastream")
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", "com.kreastream")
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources!!.getIdentifier(name, "drawable", "com.kreastream")
        return ResourcesCompat.getDrawable(plugin.resources!!, id, null)
    }

    private fun getString(name: String): String? {
        val id = plugin.resources!!.getIdentifier(name, "string", "com.kreastream")
        return plugin.resources!!.getString(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = plugin.resources!!.getIdentifier("outline", "drawable", "com.kreastream")
        this.background = plugin.resources!!.getDrawable(outlineId, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val settings = getLayout("settings", inflater, container)
        settings.findView<TextView>("add_link_text").text = "Bağlantı Ekle"
        settings.findView<TextView>("list_link_text").text = "Bağlantıları Listele"

        val addLinkButton = settings.findView<ImageView>("button_add_link")
        addLinkButton.setImageDrawable(getDrawable("edit_icon"))
        addLinkButton.makeTvCompatible()

        addLinkButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val credsView = getLayout("add_link", inflater, container)
                val nameInput = credsView.findView<EditText>("name")
                val linkInput = credsView.findView<EditText>("link")
                
                // Add checkboxes for new options
                val sectionCheckbox = CheckBox(context).apply {
                    text = "Show as separate section on main page"
                    textSize = 14f
                    setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                }
                
                val activeCheckbox = CheckBox(context).apply {
                    text = "Active (show on main page)"
                    textSize = 14f
                    isChecked = true // Default to active
                    setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                }
                
                val episodesCheckbox = CheckBox(context).apply {
                    text = "Show channels as episodes"
                    textSize = 14f
                    isChecked = true // Default to episodes mode
                    setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                }
                
                // Add checkboxes to the layout
                if (credsView is LinearLayout) {
                    credsView.addView(activeCheckbox)
                    credsView.addView(episodesCheckbox)
                    credsView.addView(sectionCheckbox)
                }

                val clipboardManager = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipboardText = clipboardManager?.primaryClip?.getItemAt(0)?.text
                if (!clipboardText.isNullOrEmpty() && clipboardText.startsWith("http", ignoreCase = true)) {
                    linkInput.setText(clipboardText.toString())
                }

                AlertDialog.Builder(context ?: throw Exception("Unable to build alert dialog"))
                    .setTitle("Bağlantı Ekle")
                    .setView(credsView)
                    .setPositiveButton("Kaydet", object : DialogInterface.OnClickListener {
                        override fun onClick(p0: DialogInterface, p1: Int) {
                            var name = nameInput.text.trim().toString()
                            var link = linkInput.text.trim().toString().replace(Regex("^(HTTPS|HTTP)", RegexOption.IGNORE_CASE)) {
                                it.value.lowercase()
                            }
                            val showAsSection = sectionCheckbox.isChecked
                            val isActive = activeCheckbox.isChecked
                            val showAsEpisodes = episodesCheckbox.isChecked
                            
                            if (name.isNullOrEmpty() || !link.startsWith("http")) {
                                showToast("Lütfen Tüm Bilgileri doldurun")
                            } else {
                                try {
                                    val existingLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                                    if (existingLinks.any { it.name == name }) {
                                        showToast("Bu isim zaten Mavcut")
                                    } else {
                                        val existingLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                                        val nextOrder = (existingLinks.maxOfOrNull { it.order } ?: 0) + 1
                                        val updatedLinks = existingLinks.toMutableList().apply {
                                            add(Link(name, link, nextOrder, showAsSection, isActive, showAsEpisodes))
                                        }
                                        setKey("iptv_links", updatedLinks.toTypedArray())
                                        showToast("Bağlantı başarıyla kaydedildi")
                                        plugin.reload()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    showToast("Hata : Bağlantı eklenemedi")
                                }
                            }
                        }
                    })
                    .show()
            }
        })

        val listLinkButton = settings.findView<ImageView>("button_list_link")
        listLinkButton.setImageDrawable(getDrawable("settings_icon"))
        listLinkButton.makeTvCompatible()

        listLinkButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val credsView = getLayout("manager", inflater, container)
                val linkListLayout = credsView.findView<LinearLayout>("list_link")
                val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                if (savedLinks.isEmpty()) {
                    val noLinksTextView = TextView(context).apply {
                        text = "Henüz bağlantı yok"
                        textSize = 16f
                        setTextColor(Color.GRAY)
                        gravity = Gravity.START
                    }
                    val layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dpToPx(8)
                    }
                    noLinksTextView.layoutParams = layoutParams
                    linkListLayout.addView(noLinksTextView)
                } else {
                    val sortedLinks = savedLinks.sortedBy { it.order }
                    displayLinks(sortedLinks, linkListLayout, inflater, container)
                }

                AlertDialog.Builder(context ?: throw Exception("Unable to build alert dialog"))
                    .setTitle("KreaTV bağlantı Listesi")
                    .setView(credsView)
                    .show()
            }
        })


        return settings
    }

    fun dpToPx(dp: Int): Int {
        val scale = context!!.resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }
    
    private fun moveLink(link: Link, direction: Int) {
        try {
            val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
            val sortedLinks = savedLinks.sortedBy { it.order }.toMutableList()
            val currentIndex = sortedLinks.indexOfFirst { it.name == link.name }
            
            if (currentIndex != -1) {
                val newIndex = currentIndex + direction
                if (newIndex >= 0 && newIndex < sortedLinks.size) {
                    // Swap the orders
                    val currentLink = sortedLinks[currentIndex]
                    val targetLink = sortedLinks[newIndex]
                    
                    val updatedLinks = savedLinks.map { savedLink ->
                        when (savedLink.name) {
                            currentLink.name -> savedLink.copy(order = targetLink.order)
                            targetLink.name -> savedLink.copy(order = currentLink.order)
                            else -> savedLink
                        }
                    }.toTypedArray()
                    
                    setKey("iptv_links", updatedLinks)
                    plugin.reload()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Hata: Sıralama değiştirilemedi")
        }
    }
    
    private fun displayLinks(
        links: List<Link>, 
        container: LinearLayout, 
        inflater: LayoutInflater, 
        viewContainer: ViewGroup?
    ) {
        links.forEachIndexed { index, link ->
            val linkItemView = getLayout("list_link", inflater, viewContainer)
            
            // Build status indicators
            val statusIndicators = mutableListOf<String>()
            if (!link.isActive) statusIndicators.add("Inactive")
            if (link.showAsSection) statusIndicators.add("Section")
            if (!link.showAsEpisodes) statusIndicators.add("Live")
            
            val statusText = if (statusIndicators.isNotEmpty()) " [${statusIndicators.joinToString(", ")}]" else ""
            linkItemView.findView<TextView>("name").text = "${index + 1}. ${link.name}$statusText"
            linkItemView.findView<TextView>("link").text = link.link
            
            val deleteButton = linkItemView.findView<ImageView>("delete_button")
            deleteButton.setImageDrawable(getDrawable("delete_icon"))
            deleteButton.setOnClickListener {
                try {
                    val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                    val updatedLinks = savedLinks.filter { it.name != link.name }.toTypedArray()
                    setKey("iptv_links", updatedLinks)
                    showToast("${link.name} başarıyla kaldırıldı")
                    container.removeView(linkItemView)
                    plugin.reload()
                } catch (e: Exception) {
                    e.printStackTrace()
                    showToast("Hata : Bağlantı kaldırılamadı")
                }
            }
            
            // Add controls container
            val controlsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            // Active/Inactive toggle button
            val activeButton = Button(context).apply {
                text = if (link.isActive) "A" else "I"
                textSize = 10f
                setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(30),
                    dpToPx(30)
                ).apply {
                    rightMargin = dpToPx(4)
                }
                setOnClickListener {
                    toggleActive(link)
                    // Refresh the display
                    container.removeAllViews()
                    val newSavedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                    displayLinks(newSavedLinks.sortedBy { it.order }, container, inflater, viewContainer)
                }
            }
            controlsContainer.addView(activeButton)
            
            // Episodes/Live toggle button
            val episodesButton = Button(context).apply {
                text = if (link.showAsEpisodes) "E" else "L"
                textSize = 10f
                setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(30),
                    dpToPx(30)
                ).apply {
                    rightMargin = dpToPx(4)
                }
                setOnClickListener {
                    toggleEpisodes(link)
                    // Refresh the display
                    container.removeAllViews()
                    val newSavedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                    displayLinks(newSavedLinks.sortedBy { it.order }, container, inflater, viewContainer)
                }
            }
            controlsContainer.addView(episodesButton)
            
            // Section toggle button
            val sectionButton = Button(context).apply {
                text = if (link.showAsSection) "S" else "G"
                textSize = 10f
                setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(30),
                    dpToPx(30)
                ).apply {
                    rightMargin = dpToPx(4)
                }
                setOnClickListener {
                    toggleSection(link)
                    // Refresh the display
                    container.removeAllViews()
                    val newSavedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                    displayLinks(newSavedLinks.sortedBy { it.order }, container, inflater, viewContainer)
                }
            }
            controlsContainer.addView(sectionButton)
            
            // Up button
            if (index > 0) {
                val upButton = Button(context).apply {
                    text = "↑"
                    textSize = 12f
                    setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(30),
                        dpToPx(30)
                    ).apply {
                        rightMargin = dpToPx(4)
                    }
                    setOnClickListener {
                        moveLink(link, -1)
                        // Refresh the display
                        container.removeAllViews()
                        val newSavedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                        displayLinks(newSavedLinks.sortedBy { it.order }, container, inflater, viewContainer)
                    }
                }
                controlsContainer.addView(upButton)
            }
            
            // Down button
            if (index < links.size - 1) {
                val downButton = Button(context).apply {
                    text = "↓"
                    textSize = 12f
                    setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(30),
                        dpToPx(30)
                    )
                    setOnClickListener {
                        moveLink(link, 1)
                        // Refresh the display
                        container.removeAllViews()
                        val newSavedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
                        displayLinks(newSavedLinks.sortedBy { it.order }, container, inflater, viewContainer)
                    }
                }
                controlsContainer.addView(downButton)
            }
            
            // Add controls to the link item
            if (linkItemView is LinearLayout) {
                linkItemView.addView(controlsContainer)
            }
            
            container.addView(linkItemView)
        }
    }
    
    private fun toggleSection(link: Link) {
        try {
            val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
            val updatedLinks = savedLinks.map { savedLink ->
                if (savedLink.name == link.name) {
                    savedLink.copy(showAsSection = !savedLink.showAsSection)
                } else {
                    savedLink
                }
            }.toTypedArray()
            
            setKey("iptv_links", updatedLinks)
            plugin.reload()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Hata: Section ayarı değiştirilemedi")
        }
    }
    
    private fun toggleActive(link: Link) {
        try {
            val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
            val updatedLinks = savedLinks.map { savedLink ->
                if (savedLink.name == link.name) {
                    savedLink.copy(isActive = !savedLink.isActive)
                } else {
                    savedLink
                }
            }.toTypedArray()
            
            setKey("iptv_links", updatedLinks)
            plugin.reload()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Hata: Active ayarı değiştirilemedi")
        }
    }
    
    private fun toggleEpisodes(link: Link) {
        try {
            val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
            val updatedLinks = savedLinks.map { savedLink ->
                if (savedLink.name == link.name) {
                    savedLink.copy(showAsEpisodes = !savedLink.showAsEpisodes)
                } else {
                    savedLink
                }
            }.toTypedArray()
            
            setKey("iptv_links", updatedLinks)
            plugin.reload()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Hata: Episodes ayarı değiştirilemedi")
        }
    }
}