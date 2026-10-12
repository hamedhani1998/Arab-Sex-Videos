package com.sexarab69.plugin

import android.content.SharedPreferences

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import sharedplay.LocalPlay
import sharedplay.Family
import sharedplay.PluginSettings

internal const val PREFS_NAME = "SexArab69"

class SexArab69Provider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "سكس عرب 69"
    override var mainUrl = "https://sexarab69.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "احدث الافلام",
        "category/سكس-عربي/" to "سكس عربي",
        "category/سكس-مترجم/" to "سكس مترجم",
        "category/سكس-مصري/" to "سكس مصري",
        "category/سكس-امهات/" to "سكس امهات",
    )

    /**
     * بطاقة الفيلم في هذا الموقع.
     *
     * ## المحدّد السابق كان يعيد صفراً
     * كان `div.video-block`، ولا وجود لعنصر بهذا الاسم في الصفحة الحيّة:
     * البطاقات `article.loop-video` (28 بطاقة في الرئيسية بالعدد) وما عداه
     * أثر قالب قديم. فالمحدّد الخاطئ لا يرمي استثناء ولا يكتب سطراً في
     * السجل — يعيد قائمة فارغة فحسب، فتبدو الإضافة فارغة في الرئيسية
     * والبحث معاً بلا أي سبب ظاهر.
     */
    private val cardSelector = "article.loop-video"

    /**
     * يبني بطاقة من عنصر البطاقة الحيّ.
     *
     * الصورة في `data-src` لا `src` (تحويل متأخر)، والعنوان في `a[title]`
     * ثم في `header.entry-header > span`. والأخذ من `a[title]` أولاً مقصود:
     * الوسم `span` يقتطع العنوان الطويل في بعض القوالب.
     */
    private fun org.jsoup.nodes.Element.toCard(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        val title = a.attr("title").trim()
            .ifBlank { this.selectFirst("header.entry-header span")?.text()?.trim().orEmpty() }
            .ifBlank { this.selectFirst("span.title, h2, h3")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = this.selectFirst("img.video-main-thumb, img")?.let {
            it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (request.data.isEmpty()) {
                if (page > 1) "$mainUrl/page/$page/" else mainUrl
            } else {
                "$mainUrl/${request.data.trimEnd('/')}/${if (page > 1) "page/$page/" else ""}"
            }
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select(cardSelector).mapNotNull { it.toCard() }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/?s=$query", referer = mainUrl).document
            doc.select(cardSelector).mapNotNull { it.toCard() }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst(".title, .video-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("a[rel=tag]").mapNotNull { it.text().trim() }.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(data, referer = mainUrl).document
            Family.resolve(
                doc = doc,
                referer = data,
                origin = mainUrl,
                subtitleCallback = subtitleCallback,
                callback = callback,
                options = Family.Options(
                    probe = prefs?.getBoolean(PluginSettings.probeKey(PREFS_NAME), true) ?: true,
                    formatTag = prefs?.getBoolean(PluginSettings.tagKey(PREFS_NAME), true) ?: true
                )
            ) > 0
        } catch (e: Exception) {
            false
        }
    }
}
