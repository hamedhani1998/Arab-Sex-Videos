package com.arabxn.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * arabxn.com — عرب xn
 *
 * القائمة:  https://arabxn.com/   (روابط /watch-<id>/<slug>/)
 * المشغل:   mp4 مباشرة في التفاصيل (arabxn.xyz/done/...mp4)
 */
class ArabXNProvider : MainAPI() {
    override var mainUrl = "https://arabxn.com"
    override var name = "عرب xn"
    override val supportedTypes = setOf(TvType.Movie, TvType.NSFW)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        val document = app.get(url, headers = defaultHeaders).document
        val items = document.select("article, .post, .item, .video-item, .c-video").mapNotNull { it.toSearchResponse() }
        val moreUrl = if (page <= 1) "$mainUrl/?page=2" else "$mainUrl/?page=${page + 1}"
        val moreDoc = app.get(moreUrl, headers = defaultHeaders).document
        val moreItems = moreDoc.select("article, .post, .item, .video-item, .c-video").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            listOf(
                HomePageList("أحدث مقاطع عرب xn", items),
                HomePageList("المزيد من أحدث مقاطع عرب xn", moreItems)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/", headers = defaultHeaders).document
        return document.select("article, .post, .item, .video-item, .c-video").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.htitle, h1.entry-title, h1.title, h1")?.text()
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: document.title().substringBefore("|").trim()
            ?: return null

        val poster = document.selectFirst("img.poster, .single-poster img, video[poster]")?.let { el ->
            if (el.hasAttr("src")) el.attr("src") else el.attr("poster")
        } ?: document.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = document.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    
    private fun serverHost(url: String): String {
        val host = Regex("""https?://([^/:]+)""").find(url)?.groupValues?.get(1)
        return host?.removePrefix("www.") ?: "سيرفر"
    }
override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data, headers = defaultHeaders).document

        suspend fun emit(url: String, quality: Int = Qualities.Unknown.value) {
            callback.invoke(
                newExtractorLink("arabxn", serverHost(url), url, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            found = true
        }

        for (el in document.select("video source[src], video[src], source[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) emit(fixUrl(src))
        }
        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if ((href.contains(".mp4") || href.contains(".m3u8")) && !href.endsWith(".jpg") && !href.endsWith(".png") && !href.endsWith(".webp")) {
                emit(fixUrl(href))
            }
        }

        if (!found) {
            for (iframe in document.select("iframe[src]")) {
                val src = iframe.attr("src").ifBlank { continue }
                val html = try {
                    app.get(fixUrl(src), headers = defaultHeaders + ("Referer" to data)).text
                } catch (_: Exception) { continue }
                val candidates = Regex("""https?://[^\s"']+\.(?:mp4|m3u8)[^\s"']*""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.value }.distinct()
                    .filterNot { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }
                for (c in candidates) emit(c)
            }
        }

        return found
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val title = link.attr("title").ifBlank { link.text().ifBlank { return null } }
        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("data-poster")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}