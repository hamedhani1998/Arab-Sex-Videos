package com.sexalarabnet.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * سكس العرب نت — sexalarab.net
 *
 * القائمة/الأقسام/التفاصيل: صفحات Next.js SSR كاملة (ليست فارغة).
 *   - القائمة:      https://sexalarab.net/  — بطاقات <a class="group block" href="/video/<slug>">
 *                    العنوان في <h3> داخل البطاقة والصورة في img alt.
 *   - الأقسام:      /category/video/<slug>/  (48 بطاقة بكل قسم)
 *   - التفاصيل:     /video/<slug>/  — يعرض meta og:video = api.sexalarab.net/hls/<id>/master.m3u8
 *   - البحث:        لا يوفّر الموقع بحثاً فعلياً.
 * المشغل:          HLS بعيدة 360/480/720 عبر api.sexalarab.net/hls/<id>/master.m3u8
 */
class SexAlArabNetProvider : MainAPI() {
    override var mainUrl = "https://sexalarab.net"
    override var name = "سكس العرب نت"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    private val mainSections = listOf(
        // بدون trailing slash: الموقع يعيد 308 من "/فئة/شريحة/" إلى بدون slash
        "سكس مترجم" to "/category/video/سكس-مترجم",
        "سكس عربي" to "/category/video/سكس-عربي",
        "سكس مصري" to "/category/video/سكس-مصري",
        "سكس اخوات" to "/category/video/سكس-اخوات",
        "سكس امهات" to "/category/video/سكس-امهات",
        "سكس محارم" to "/category/video/سكس-محارم"
    )

    private suspend fun fetchItems(url: String): List<SearchResponse> {
        return try {
            app.get(url, headers = defaultHeaders).document
                .select("a.group.block[href^=/video/]")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = if (page <= 1) buildList {
            add(HomePageList("أحدث أفلام سكس العرب نت", fetchItems(mainUrl)))
            for ((label, path) in mainSections) {
                add(HomePageList(label, fetchItems("$mainUrl$path")))
            }
        } else buildList {
            add(HomePageList("سكس العرب نت — صفحة $page", fetchItems("$mainUrl/?page=$page")))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // الموقع لا يوفر بحثاً فعلياً؛ نرد بأحدث النتائج كبديل
        return fetchItems(mainUrl)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = (document.selectFirst("h1, h2.entry-title")?.text()
            ?: document.title().substringBefore("|").trim())
            ?.let { cleanTitle(it) }
            ?: return null
        if (title.isBlank()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.poster, .poster img, img[itemprop=image]")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = document.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    /** تنظيف العنوان: إزالة النقطة اللاحقة " ." والمسافات الزائدة */
    private fun cleanTitle(raw: String): String {
        return raw.trim()
            .removeSuffix(" .")
            .trim()
            .replace(Regex("""\s+"""), " ")
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
            val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink("sexalarabnet", serverHost(url), url, type) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            found = true
        }

        val document = app.get(data, headers = defaultHeaders).document

        // 1) HLS مثل <meta property="og:video" content=".../hls/<id>/master.m3u8">
        val og = document.selectFirst("meta[property=og:video]")?.attr("content")
        if (og != null && og.contains(".m3u8")) {
            emit(fixUrl(og))
        }
        if (found) return true

        // 2) HLS في JSON-LD / next data
        val raw = document.text()
        val m3u8s = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE)
            .findAll(raw).map { it.value.trimEnd('\\', '"', '\'') }.distinct()
        for (m3u8 in m3u8s) emit(m3u8)

        // 3) mp4/m3u8 مباشرة في التفاصيل إن وجدت
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

        return found
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href").ifBlank { return null }
        // العنوان في h3 داخل البطاقة؛ alt يأتي قبل h3 في DOM ويتيح "" لـ img,
        // لذلك نخصص h3 أولاً ثم img[alt] ثم any img
        val title = this.selectFirst("h3")?.text()
            ?.ifBlank { null }
            ?: this.selectFirst("[alt]")?.attr("alt")
                ?.ifBlank { null }
                ?: this.selectFirst("img")?.attr("alt")
        val rawTitle = title?.let { cleanTitle(it) }?.ifBlank { null } ?: return null
        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
        return newMovieSearchResponse(rawTitle, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }
}