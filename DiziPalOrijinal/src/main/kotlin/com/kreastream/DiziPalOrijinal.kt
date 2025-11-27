

package com.kreastream


import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors


class DiziPalOrijinal : MainAPI() {
    override var mainUrl = "https://dizipal1514.com"
    override var name = "DiziPal Orijinal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)
    //Movie, AnimeMovie, TvSeries, Cartoon, Anime, OVA, Torrent, Documentary, AsianDrama, Live, NSFW, Others, Music, AudioBook, CustomMedia, Audio, Podcast,

    override var sequentialMainPage =
        true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay = 250L // ? 0.25 saniye
    override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }


    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Yeni Eklenen Bölümler",
        "" to "Yeni Eklenenler",
        //"" to "Yüksek Imdb Puanlı Diziler",
        "" to "Yeni Filmler",
        "${mainUrl}/kanal/exxen" to "Exxen Dizileri",
        "${mainUrl}/kanal/disney" to "Disney+ Dizileri",
        "${mainUrl}/kanal/netflix" to "Netflix Dizileri",
        "${mainUrl}/kanal/amazon" to "Amazon Dizileri",
        "${mainUrl}/kanal/apple-tv" to "Apple TV+ Dizileri",
        "${mainUrl}/kanal/max" to "Max Dizileri",
        "${mainUrl}/kanal/hulu" to "Hulu Dizileri",
        "${mainUrl}/kanal/tod" to "TOD Dizileri",
        "${mainUrl}/kanal/tabii" to "Tabii Dizileri",
        //"19" to "Anime",
    )

    private var sessionCookies: Map<String, String>? = null
    private var cKey: String? = null
    private var cValue: String? = null
    private val initMutex = Mutex()

    private suspend fun initSession() {
        if (sessionCookies != null && cKey != null && cValue != null) return
        initMutex.withLock {
            if (sessionCookies != null && cKey != null && cValue != null) return@withLock

            val resp = app.get(mainUrl, interceptor = interceptor, timeout = 120, headers =  mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
                "Referer" to "${mainUrl}/",
            ))
            sessionCookies = resp.cookies.mapValues { (_, v) -> URLDecoder.decode(v, "UTF-8") }

            val document = resp.document
            cKey = document.selectFirst("input[name=cKey]")?.`val`()
            cValue = document.selectFirst("input[name=cValue]")?.`val`()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initSession()
        val kanallarliste = listOf(
            "Exxen Diziler",
            "Disney+ Dizileri",
            "Netflix Dizileri",
            "Amazon Dizileri",
            "Apple TV+ Dizileri",
            "Max Dizileri",
            "Hulu Dizileri",
            "TOD Dizileri",
            "Tabii Dizileri"
        )

        val response = if (kanallarliste.any { request.name.contains(it) }) {
            app.post(
                "${mainUrl}/bg/getserielistbychannel", data = mapOf(
                    "cKey" to "$cKey", "cValue" to "$cValue",
                    "curPage" to "$page",
                    "channelId" to request.data,
                    "languageId" to "2,3,4"
                )
            )

        } else if (request.name.contains("Yeni Eklenenler")) {
            app.post(
                "${mainUrl}/bg/findseries", data = mapOf(
                    "cKey" to "$cKey",
                    "cValue" to "$cValue",
                    "currentPage" to "$page",
                    "categoryIdsComma[]" to request.data,
                    "releaseYearStart" to "1923",
                    "releaseYearEnd" to "2025",
                    "orderType" to "date_asc"
                )
            )
        } else if (request.name.contains("Yeni Eklenen Bölümler")) {
            val yeniEklenen = app.get(request.data, interceptor = interceptor).document
            val home = yeniEklenen.select("div.overflow-auto a")
                .mapNotNull { it.toMainPageResult() }

            return newHomePageResponse(request.name, home)

        } else if (request.name.contains("Yeni Filmler")) {
            app.post(
                "${mainUrl}/bg/findmovies", interceptor = interceptor, data = mapOf(
                    "cKey" to "$cKey",
                    "cValue" to "$cValue",
                    "currentPage" to "$page",
                    "categoryIdsComma[]" to request.data,
                    "releaseYearStart" to "1923",
                    "releaseYearEnd" to "2025",
                    "orderType" to "date_desc"
                )
            )
        } else {
            app.post(
                "${mainUrl}/bg/findseries", interceptor = interceptor, data = mapOf(
                    "cKey" to "$cKey",
                    "cValue" to "$cValue",
                    "currentPage" to "$page",
                    "categoryIdsComma[]" to request.data,
                    "releaseYearStart" to "1923",
                    "releaseYearEnd" to "2025",
                    "orderType" to "imdb_desc"
                )
            )
        }

        val bodyText = response.text

        val htmlFragment = if (bodyText.trimStart().startsWith("{")) {
            // JSON içinde data.html var
            JSONObject(bodyText)
                .getJSONObject("data")
                .getString("html")
        } else {
            // Direkt HTML döndü, JSON yok
            bodyText
        }

        val doc = Jsoup.parseBodyFragment(htmlFragment)

        val home = doc.select("div.prm-borderb")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val textElement = this.selectFirst("div.text.block div.text-white.text-sm")
        val title = if (textElement != null && textElement.text().isNotBlank()) {
            // textElement var ve içinde boş olmayan bir metin var:
            val alt = this.selectFirst("img")?.attr("alt") ?: ""
            alt + " ${textElement.text()}"
        } else {
            this.selectFirst("img")?.attr("alt") ?: return null
        }
        val aEl = this.selectFirst("a") ?: return null

// 2. href değerini al:
        val rawHref = aEl.attr("href")

// 3. Dönüştürme:
        val href = if (rawHref.contains("/bolum/")) {
            // "/bolum/" varsa önce URL'i düzelt, sonra "bolum"ü "series" yap, sonundaki "-" sonrası kısmı at
            fixUrlNull(rawHref)
                ?.replace("/bolum/", "/series/")
                ?.replace(Regex("-[0-9]+x.*$"), "")
        } else {
            // yoksa sadece düzelt
            fixUrlNull(rawHref)
        } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        val imdbScore = this.selectFirst("h4")?.text()

        val puan = if (imdbScore.toString().contains("0.0")) {
            ""
        } else {
            imdbScore
        }

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(puan)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(puan)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        initSession()
        val responseBody = app.post(
            "$mainUrl/bg/searchcontent", interceptor = interceptor, data = mapOf(
                "cKey" to cKey!!,
                "cValue" to cValue!!,
                "searchterm" to query
            ), headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
                )
        ).text

        // 2) JSONObject ile parse
        val json = JSONObject(responseBody)
        val data = json.getJSONObject("data")
        val resultList = data.optJSONArray("result") ?: return emptyList()

        // 3) Her bir sonucu map edip SearchResponse’a çeviriyoruz
        return (0 until resultList.length()).mapNotNull { i ->
            val item = resultList.getJSONObject(i)

            // Sadece Series tipinde olanları almak istersen burayı açabilirsin:
            // if (item.optString("used_type") != "Series") return@mapNotNull null

            val title = item.optString("object_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = item.optString("used_slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = fixUrlNull("$mainUrl/$slug") ?: return@mapNotNull null
            val posterUrl = item.optString("object_poster_url").takeIf { it.isNotBlank() }

           if (href.contains("/movies/")) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val getUrl = app.get(url, interceptor = interceptor)
        val document = getUrl.document
        val text = getUrl.text
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("img.w-full.h-full.object-cover")?.attr("src"))
        val regex = Regex("""window\.iframeBeforeVideoImage = '([^']*)';""")

// Bul ve gruptaki değeri al, yoksa boş string döndür
        val moviePoster: String = regex.find(text)
            ?.groupValues
            ?.get(1)
            ?: ""
        val description = document.selectFirst("p.text-white.text-base")?.text()?.trim()
        val movieDesc = document.selectFirst("div.summary p")?.text()?.trim()
        val year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("ul.rigth-content > li:nth-child(5) a").map { it.text() }
        val movieTags = document.select("div.popup-content > ul:nth-child(2) > li:nth-child(3) > div:nth-child(2) a").map { it.text() }
        val rating = document.selectFirst("ul.rigth-content > li:nth-child(3) div.value, div.popup-content > ul:nth-child(2) > li:nth-child(2) > div:nth-child(2)")?.text()?.trim()
        val puanlar = if (rating!!.contains("Diğer")) {
            document.selectFirst("div.popup-content > ul:nth-child(2) > li:nth-child(3) > div:nth-child(2)")?.text()
                ?.trim() ?: ""
        }else{
            rating
        }
        val trailer = document.selectFirst("a[target=_blank][href*=youtube.com]")?.attr("href")
        val actors = document
            .select("div.movie-actors ul.hide-more-actors li")
            .mapNotNull { li ->
                val name = li.selectFirst("a span.name")?.text()?.trim()
                if (name.isNullOrBlank()) return@mapNotNull null
                val poster = li.selectFirst("a img")?.attr("src") ?: ""
                val role = li.selectFirst("a span.role")?.text()?.trim()
                Pair(Actor(name, poster), role?.takeIf { it.isNotBlank() })
            }

        val duration = document.selectFirst("ul.rigth-content > li:nth-child(8) > div.value")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val movieDuration = document.selectFirst("div.popup-content > ul:nth-child(2) > li:nth-child(4) > div:nth-child(2)")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val bolumler = document.select("a.text.block").map { bolumler ->
            val bolumHref = bolumler.attr("href")
            val bolumName = "Bölüm"
            val bolumEpisode =
                bolumler.selectFirst("div.text-white.text-sm.opacity-80.font-light")?.text()
                    ?.substringBeforeLast(".")
                    ?.substringAfterLast(" ")
                    ?.toIntOrNull()
            val bolumSeason = bolumler.selectFirst("div.text-white.text-sm.opacity-80.font-light")
                ?.text()
                ?.substringBefore(".")?.toIntOrNull()
            newEpisode(bolumHref, {
                this.name = bolumName
                this.season = bolumSeason
                this.episode = bolumEpisode
            })
        }

        if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = moviePoster
                this.plot = movieDesc
                this.year = year
                this.tags = movieTags
                this.score = Score.from10(puanlar)
                this.duration = movieDuration
                addActors(actors)
                 addTrailer(trailer)
            }
            }else{
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, bolumler) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(puanlar)
                    this.duration = duration
                    addActors(actors)
                     addTrailer(trailer)
                }
            }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        val hiddenJson = document.selectFirst("div[data-rm-k]")!!.text()
        val key = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"
        val obj = JSONObject(hiddenJson)
        val ciphertext = obj.getString("ciphertext")
        val iv         = obj.getString("iv")
        val salt       = obj.getString("salt")
        try {
            val decryptedContent = decrypt(key, salt, iv, ciphertext)
            val iframe           = fixUrlNull(decryptedContent).toString()

             loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)


        } catch (e: Exception) {
            return false
        }

        return true
    }
}

fun decrypt(
    passphrase: String,
    saltHex: String,
    ivHex: String,
    ciphertextBase64: String
): String {
    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
    val tmp = factory.generateSecret(spec)
    val secret = SecretKeySpec(tmp.encoded, "AES")

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))

    val decoded = base64DecodeArray(ciphertextBase64)
    val plaintextBytes = cipher.doFinal(decoded)
    return String(plaintextBytes, Charsets.UTF_8)
}
