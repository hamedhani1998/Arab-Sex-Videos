package com.sexalarab.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * سكس العرب — sexalarab.com
 *
 * البنية المؤكدة بالفحص المباشر:
 *   القائمة:  https://sexalarab.com/?page=N   (عناصر <article> بروابط رقمية /2257/)
 *   التفاصيل: https://sexalarab.com/2257/      (ه1 يحوي العنوان، mp4 عبر api.sexalarab.net)
 *   المشغل:   api.sexalarab.net — بنية HLS/السبرايت المشتركة في كل عائلة sexalarab،
 *             مع mp4 مباشر في كثير من التفاصيل.
 */
class SexAlArabProvider : MainAPI() {
    override var mainUrl = "https://sexalarab.com"
    override var name = "سكس العرب"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8",
        "Referer" to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        val document = app.get(url, headers = defaultHeaders).document
        val items = document.select("article, .post, .item, .video-item, .sw-item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(listOf(HomePageList("أحدث أفلام سكس العرب", items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            app.get("$mainUrl/?s=$query", headers = defaultHeaders).document
        }
        return document.select("article, .post, .item, .video-item, .sw-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.htitle, h1.entry-title, h1.title, h1")?.text()
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: document.title().substringBefore("|").trim()
            ?: return null

        val poster = document.selectFirst("img.poster, .single-poster img, img[itemprop=image]")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = document.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data, headers = defaultHeaders).document

        fun emit(url: String, quality: Int = Qualities.Unknown.value) {
            callback.invoke(
                newExtractorLink("sexalarab", "سيرفر مباشر", url, ExtractorLinkType.MP4) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            found = true
        }

        // 1) روابط mp4/m3u8 داخل صفحة التفاصيل
        document.select("video source[src], video[src], source[src]").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                emit(fixUrl(src))
            }
        }
        document.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if ((href.contains(".mp4") || href.contains(".m3u8")) && !href.endsWith(".jpg") && !href.endsWith(".png") && !href.endsWith(".webp")) {
                emit(fixUrl(href))
            }
        }

        // 2) المشغل عبر api.sexalarab.net (بنية HLS بأرقام)
        if (!found) {
            val text = document.text()
            Regex("""api\.sexalarab\.net/videos/\d+[^\s"']*""", RegexOption.IGNORE_CASE).findAll(text)
                .map { fixUrl(it.value) }.distinct().forEach { emit(it, Qualities.Unknown.value) }
        }

        // 3) إن لم يجد، اتبع iframe
        if (!found) {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { return@forEach }
                val iframeDoc = try {
                    app.get(fixUrl(src), headers = defaultHeaders + ("Referer" to data)).document
                } catch (_: Exception) { return@forEach }

                iframeDoc.select("video source[src], video[src], source[src]").forEach { el ->
                    val s = el.attr("src").ifBlank { el.attr("data-src") }
                    if (s.isNotBlank() && (s.contains(".mp4") || s.contains(".m3u8"))) emit(fixUrl(s))
                }
                iframeDoc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if ((href.contains(".mp4") || href.contains(".m3u8")) && !href.endsWith(".jpg") && !href.endsWith(".png")) {
                        emit(fixUrl(href))
                    }
                }
                val iframeText = iframeDoc.text()
                Regex("""https?://[^\s"']+\.(?:mp4|m3u8)[^\s"']*""", RegexOption.IGNORE_CASE)
                    .findAll(iframeText).map { it.value }.distinct()
                    .filterNot { it.endsWith(".jpg") || it.endsWith(".png") }
                    .forEach { emit(it) }
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
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
