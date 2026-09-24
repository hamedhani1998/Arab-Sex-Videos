package com.arabxnsex.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * arabxn.sex — سكس مترجم عربي
 *
 * القائمة:  https://arabxn.sex/   (بطاقات div.item، روابط /video/<id>/<slug>/،
 *           الصور في img.thumb.lazy-load[data-original])
 * الأقسام:  /categories/<قسم>/
 * المشغل:   flashvars video_url: 'function/0/get_file/...mp4/' (+ _text للجودة).
 *           روابط get_file تُطلق بدون بادئة function/0 (بعيدة 302 إلى cdn عند عملها).
 */
class ArabXNSexProvider : MainAPI() {
    override var mainUrl = "https://arabxn.sex"
    override var name = "arabxn.sex"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    private val mainSections = listOf(
        // كلها أقسام محتواها أجنبي/إنجليزي (مترجم للعربية): الموقع متخصص في المحتوى الأجنبي المترجم
        "سكس اجنبي" to "/categories/سكس-اجنبي/",
        "سكس مترجم" to "/categories/سكس-مترجم/",
        "xnxx مترجم" to "/categories/xnxx-مترجم/",
        "سكس سحاق" to "/categories/سكس-سحاق/",
        "سكس محارم" to "/categories/سكس-محارم/"
    )

    private suspend fun fetchItems(url: String): List<SearchResponse> {
        return try {
            app.get(url, headers = defaultHeaders).document
                .select(".item, article, .post, .video-item")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = if (page <= 1) buildList {
            // نبدأ بالمحتوى الأجنبي (مطلوب المستخدم) ثم نكمل بالأقسام الأجنبية، وأخيراً الأحدث الأجنبية كذلك
            for ((label, path) in mainSections) {
                add(HomePageList(label, fetchItems("$mainUrl$path")))
            }
        } else buildList {
            add(HomePageList("أحدث مقاطع arabxn.sex — صفحة $page", fetchItems("$mainUrl/?page=$page")))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = try {
            app.get("$mainUrl/search/$query/", headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return document.select(".item, article, .post, .video-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        // عنوان الفيديو الحقيقي: og:title ثم <title>. (وسم h1 الوحيد في الصفحة هو شعار الموقع "arabxn.sex")
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { null }
            ?.let { cleanTitle(it) }
            ?: cleanTitle(document.title().substringBefore("|").trim()).ifBlank { return null }

        val poster = document.selectFirst("img.thumb.lazy-load, .img img, video[poster]")?.let { el ->
            el.attr("data-original").ifBlank { el.attr("data-webp").ifBlank { el.attr("src").ifBlank { el.attr("poster") } } }
        } ?: document.selectFirst("img.poster, .single-poster img")?.attr("src")
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

        suspend fun emit(url: String, quality: Int = Qualities.Unknown.value) {
            callback.invoke(
                newExtractorLink("arabxnsex", serverHost(url), url, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            found = true
        }

        // المصدر الحي الوحيد: JSON-LD "contentUrl" (get_file بهاش حي يوجّه 302 إلى cdn.arabxn.sex)
        // استخراج مباشر من نص الصفحة دون حلقات أو طلبات إضافية — أسرع منطق ممكن
        val raw = app.get(data, headers = defaultHeaders).text
        val src = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
            .find(raw)?.groupValues?.get(1)
            ?.let {
                if (it.contains(".mp4") || it.contains(".m3u8")) it else null
            } ?: return found

        emit(src)
        return found
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val rawTitle = link.attr("title").ifBlank { link.text().ifBlank { return null } }
        val title = cleanTitle(rawTitle)
        val poster = this.selectFirst("img")?.let { img ->
            img.attr("data-original").ifBlank { img.attr("data-webp").ifBlank { img.attr("src") } }
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /** لقب البطاقة/التفاصيل: "الاسم الكامل - سكس" — نأخذ قبل آخر " - " ليتحول من "الاسم - تصنيف" */
    private fun cleanTitle(raw: String): String {
        val unescaped = try {
            org.jsoup.parser.Parser.unescapeEntities(raw, false)
        } catch (_: Exception) {
            raw
        }
        val t = unescaped.trim().substringBefore(" | ").trim()
        val parts = t.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        val cleaned = if (parts.size >= 2) parts.dropLast(1).joinToString(" - ") else t
        return cleaned.trim().replace(Regex("""\s+"""), " ").ifBlank { unescaped.trim() }
    }
}