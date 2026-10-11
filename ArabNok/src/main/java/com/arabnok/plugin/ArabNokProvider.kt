package com.arabnok.plugin

import android.content.SharedPreferences

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import sharedplay.LocalPlay
import sharedplay.Family
import sharedplay.PluginSettings

internal const val PREFS_NAME = "ArabNok"

class ArabNokProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "عرب نوك"
    override var mainUrl = "https://arabnok.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "احدث الافلام",
        "category/سكس-مصري/" to "سكس مصري",
        "category/سكس-خليجي/" to "سكس خليجي",
        "category/سكس-مترجم/" to "سكس مترجم",
        "category/سكس-امهات/" to "سكس امهات",
        "category/سكس-محارم/" to "سكس محارم",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (request.data.isEmpty()) {
                if (page > 1) "$mainUrl/page/$page/" else mainUrl
            } else {
                "$mainUrl/${request.data.trimEnd('/')}/${if (page > 1) "page/$page/" else ""}"
            }
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.video-block").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a.thumb") ?: item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = item.selectFirst("a.infos span.title")?.text()?.trim()
                        ?: item.selectFirst("span.title")?.text()?.trim()
                        ?: a.attr("title") ?: ""
                    val poster = item.selectFirst("img.video-img, img.video-img-src")?.let {
                        it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
                    }
                    val duration = item.selectFirst("span.duration")?.text()?.trim()
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                    }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/?s=$query", referer = mainUrl).document
            doc.select("div.video-block").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a.thumb") ?: item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = item.selectFirst("a.infos span.title")?.text()?.trim()
                        ?: item.selectFirst("span.title")?.text()?.trim()
                        ?: a.attr("title") ?: ""
                    val poster = item.selectFirst("img.video-img, img.video-img-src")?.let {
                        it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
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
