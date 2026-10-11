package com.sexarabfun.plugin

import android.content.SharedPreferences

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import sharedplay.LocalPlay
import sharedplay.Family
import sharedplay.PluginSettings

internal const val PREFS_NAME = "SexArabFun"

class SexArabFunProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "سكس عربي فان"
    override var mainUrl = "https://www.sexarab.fun"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest/" to "احدث الافلام",
        "top_rated/" to "افضل الافلام",
        "most_popular/" to "الاعلى مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = a.attr("title")?.trim() ?: item.selectFirst("strong.title")?.text()?.trim() ?: ""
                    val poster = item.selectFirst("img.thumb, img.lazy-load")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/$query/", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = a.attr("title")?.trim() ?: item.selectFirst("strong.title")?.text()?.trim() ?: ""
                    val poster = item.selectFirst("img.thumb, img.lazy-load")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
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
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster; this.plot = description; this.tags = tags
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
