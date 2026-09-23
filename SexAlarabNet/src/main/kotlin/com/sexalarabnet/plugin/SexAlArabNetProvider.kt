package com.sexalarabnet.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * سكس العرب نت — sexalarab.net
 *
 * بنية مؤكدة بالفحص:
 *   القائمة:   https://sexalarab.net/?page=N
 *   البحث:     https://sexalarab.net/search/<query>/
 *   التفاصيل:  https://sexalarab.net/video/<slug>/   (روابط مثل /video/تجربة-مشاعر.../)
 *   المشغل:    mp4 مباشر (video.xhorno.com / api عبر iframe)
 *
 * ملاحظة: sexalarab.net و sexalarab.com شقيقتان — كلاهما يمر عبر api.sexalarab.net
 * للمشغل HLS، لكن القائمة في net تحوي mp4 مباشراً في كثير من الحالات.
 */
class SexAlArabNetProvider : MainAPI() {
    override var mainUrl = "https://sexalarab.net"
    override var name = "سكس العرب نت"
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
        val items = document.select("article, .post, .video-item, .item, .sw-item").mapNotNull { it.toSearchResponse() }
        val moreUrl = if (page <= 1) "$mainUrl/?page=2" else "$mainUrl/?page=${page + 1}"
        val moreDoc = app.get(moreUrl, headers = defaultHeaders).document
        val moreItems = moreDoc.select("article, .post, .video-item, .item, .sw-item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            listOf(
                HomePageList("أحدث أفلام سكس العرب نت", items),
                HomePageList("المزيد من أحدث أفلام سكس العرب نت", moreItems)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.trim()}%2F".replace("%2F", "/") // أشكال مختلفة
        val doc = try { app.get("$mainUrl/search/${query.trim()}/", headers = defaultHeaders).document }
            catch (_: Exception) { return emptyList() }
        val items = doc.select("article, .post, .video-item, .item, .sw-item").mapNotNull { it.toSearchResponse() }
        return items.ifEmpty {
            // بديل: بعض النسخ تستخدم /search/?s=
            val doc2 = try {
                app.get("$mainUrl/search/?s=${query.trim()}", headers = defaultHeaders).document
            } catch (_: Exception) { return emptyList() }
            doc2.select("article, .post, .video-item, .item, .sw-item").mapNotNull { it.toSearchResponse() }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.htitle, h1.entry-title, h1.title, h1")?.text()
            ?.replace("مترجم", "")
            ?.replace("مدبلج", "")
            ?.trim()
            ?: document.title().substringBefore("|").trim()
            ?: return null

        val poster = document.selectFirst("img.poster, .poster img, img[itemprop=image]")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")

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

        suspend fun emit(url: String, quality: Int = Qualities.Unknown.value) {
            callback.invoke(
                newExtractorLink("sexalarabnet", serverHost(url), url, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            found = true
        }

        val document = app.get(data, headers = defaultHeaders).document

        // 1) mp4/m3u8 ضمن عناصر المشغل (video/source) أو روابط مباشرة في التفاصيل
        for (el in document.select("video source, video[src], source[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                emit(fixUrl(src))
            }
        }

        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if ((href.contains(".mp4") || href.contains(".m3u8")) && !href.endsWith(".jpg") && !href.endsWith(".png")) {
                emit(fixUrl(href))
            }
        }

        // 2) مشغل مشترك مع sexalarab.com — api.sexalarab.net
        if (!found) {
            val iframes = document.select("iframe[src]").mapNotNull { it.attr("src").ifBlank { null } }
            for (iframe in iframes) {
                val iframeUrl = fixUrl(iframe)
                val iframeDoc = try {
                    app.get(iframeUrl, headers = defaultHeaders + ("Referer" to data)).document
                } catch (_: Exception) { continue }

                // mp4/m3u8 داخل iframe
                for (el in iframeDoc.select("video source, video[src], source[src]")) {
                    val src = el.attr("src").ifBlank { el.attr("data-src") }
                    if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) emit(fixUrl(src))
                }
                for (a in iframeDoc.select("a[href]")) {
                    val href = a.attr("href")
                    if ((href.contains(".mp4") || href.contains(".m3u8")) && !href.endsWith(".jpg") && !href.endsWith(".png")) {
                        emit(fixUrl(href))
                    }
                }

                // روابط داخل أكواد JS مضغوطة في iframe (نمط ukrcdn/next)
                val text = iframeDoc.text()
                val candidates = Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""", RegexOption.IGNORE_CASE)
                    .findAll(text).map { it.value }.distinct()
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
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
