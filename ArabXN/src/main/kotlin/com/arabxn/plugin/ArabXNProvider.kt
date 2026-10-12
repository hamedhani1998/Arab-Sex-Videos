package com.arabxn.plugin

import android.content.SharedPreferences

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import sharedplay.PluginSettings
import sharedplay.Probe
import sharedplay.emitChecked

/**
 * arabxn.com — عرب xn
 *
 * القائمة:  https://arabxn.com/   (روابط /watch-<id>/<slug>/)
 * المشغل:   mp4 مباشرة في التفاصيل (arabxn.xyz/done/...mp4)
 */
internal const val PREFS_NAME = "ArabXN"

class ArabXNProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var mainUrl = "https://arabxn.com"
    override var name = "عرب xn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    private val mainSections = listOf(
        "سكس مترجم" to "/category/سكس-مترجم/",
        "سكس امهات" to "/category/سكس-امهات/",
        "سكس اخوات" to "/category/سكس-اخوات/",
        "السكس" to "/category/السكس/",
        "سكس مصري" to "/category/سكس-مصري/",
        "سكس عربي" to "/category/سكس-عربي/"
    )

    private suspend fun fetchItems(url: String): List<SearchResponse> {
        return try {
            app.get(url, headers = defaultHeaders).document
                .select("article.video-card, article, .post, .item, .video-item, .c-video")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = if (page <= 1) buildList {
            add(HomePageList("أحدث مقاطع عرب xn", fetchItems(mainUrl)))
            for ((label, path) in mainSections) {
                add(HomePageList(label, fetchItems("$mainUrl$path")))
            }
        } else buildList {
            add(HomePageList("أحدث مقاطع عرب xn — صفحة $page", fetchItems("$mainUrl/?page=$page")))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = try {
            app.get("$mainUrl/search/$query/", headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return document.select("article.video-card, article, .post, .item, .video-item, .c-video").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.htitle, h1.entry-title, h1.title, h1")?.text()
            ?.let { cleanTitle(it) }
            ?: cleanTitle(document.title().substringBefore("|").trim()).ifBlank { return null }

        val poster = document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img.poster, .single-poster img, img[itemprop=image]")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
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
            // فحص + وسم بخياري الإضافة: يُسقط الرابط الميت (منعاً لخطأ 2004)
            // ويُظهر الصيغة (MP4/M3U8/DASH…) في اسم السيرفر.
            if (emitChecked(
                    url = url,
                    name = serverHost(url),
                    referer = mainUrl,
                    quality = quality,
                    callback = callback,
                    probe = prefs?.getBoolean(PluginSettings.probeKey(PREFS_NAME), true) ?: true,
                    tag = prefs?.getBoolean(PluginSettings.tagKey(PREFS_NAME), true) ?: true
                )
            ) found = true
        }


        // مصدر HTML5: <source size="720|360|240"> — الجودة تقرأ من size= فيظهر "720p" بجوار الرابط
        for (el in document.select("video source[src], video[src], source[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                val q = (el.attr("size").ifBlank { el.attr("label") })
                    ?.let { Regex("""(\d{3,4})p?""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: Qualities.Unknown.value
                emit(fixUrl(src), q)
            }
        }

        // ترجمة عربية VTT إن وجدت في <track>
        for (tr in document.select("track[kind=captions][src]")) {
            val trackSrc = tr.attr("src")
            if (trackSrc.isNotBlank()) {
                subtitleCallback.invoke(SubtitleFile("العربية", fixUrl(trackSrc)))
            }
        }

        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if ((href.contains(".mp4") || href.contains(".m3u8")) && !href.endsWith(".jpg") && !href.endsWith(".png") && !href.endsWith(".webp")) {
                val q = (a.attr("size").ifBlank { a.attr("label") })
                    ?.let { Regex("""(\d{3,4})p?""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: Qualities.Unknown.value
                emit(fixUrl(href), q)
            }
        }

        if (!found) {
            // ## الإطارات بالتوازي — لا بالتتابع
            // كانت كل إطار يُجلب ثم يُفحص كل مرشّح فيه **على حدة**، فالزمن
            // مجموع لا أقصى. وقاعدة [Probe] أن الفحص الواحد يكلّف مهلة كاملة
            // إن علّق المضيف، فيصير الانتظار N × مهلة — وهي بالحرف «يجلس يدور
            // ولا يُظهر شيئاً» المُبلَّغ عنه على هذه الإضافة. فتُجلب الإطارات
            // معاً، ثم يُفحص المرشّحون في دفعة متوازية واحدة، ثم يُصدَر الحيّ
            // وحده بترتيب الصفحة فلا يتبدّل ترتيب السيرفرات.
            val groups = coroutineScope {
                document.select("iframe[src]").map { iframe ->
                    async(Dispatchers.IO) {
                        val src = iframe.attr("src")
                        if (src.isBlank()) return@async emptyList()
                        val html = try {
                            app.get(fixUrl(src), headers = defaultHeaders + ("Referer" to data)).text
                        } catch (_: Exception) {
                            return@async emptyList()
                        }
                        Regex("""https?://[^\s"']+\.(?:mp4|m3u8)[^\s"']*""", RegexOption.IGNORE_CASE)
                            .findAll(html).map { it.value }
                            .filterNot {
                                it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp")
                            }
                            .toList()
                    }
                }.awaitAll()
            }
            val candidates = groups.flatten().distinct()
            val live = Probe.aliveSubset(candidates, mainUrl)
            for (c in candidates) if (c in live) emit(c)
        }

        return found
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val rawTitle = link.attr("title").ifBlank { link.text().ifBlank { return null } }
        val title = cleanTitle(rawTitle)
        val poster = this.selectFirst("img.video-card-poster")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /** لقب البطاقة/التفاصيل صيغته "الاسم - <تصنيف>" في attr title؛ نأخذ الجزء قبل " - " فقط */
    private fun cleanTitle(raw: String): String {
        val t = raw.trim()
            .substringBefore(" - ")
            .substringBefore(" | ")
            .trim()
            .replace(Regex("""\s+"""), " ")
        return t.ifBlank { raw.trim() }
    }
}