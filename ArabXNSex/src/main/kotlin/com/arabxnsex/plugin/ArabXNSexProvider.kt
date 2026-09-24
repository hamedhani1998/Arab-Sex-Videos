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

        // جلب صفحة التفاصيل مرة واحدة — تنشئ جلسة (PHPSESSID) وتمنحنا المحتوى.
        // (البحث أثبت أن اللاعب/MediaHTTPService لا ينقل Cookie إلينا، لذا نحل 302 بأنفسنا.)
        val resp = try {
            app.get(data, headers = defaultHeaders)
        } catch (_: Exception) {
            return found
        }
        val raw = resp.text

        // التقاط جلسة PHPSESSID: مطلوبة فقط لطلب HEAD داخل المزوّد (لا تصل للاعب).
        val session = resp.headers["Set-Cookie"]
            ?.substringAfter("PHPSESSID=", "")
            ?.substringBefore(";")
            ?.trim()
        val cookieHeader = if (session.isNullOrEmpty()) null else "PHPSESSID=$session"

        // جودة من flashvars (قائمة الموقع قد تتضمن أرقاماً؛ الملف الفعلي واحد)
        fun labelToQuality(expr: String): Int {
            val num = Regex("""(\d{3,4})p?""", RegexOption.IGNORE_CASE).find(expr)
                ?.groupValues?.get(1)?.toIntOrNull()
            return num ?: Qualities.Unknown.value
        }

        // نجمع أزواج (رابط get_file، جودة)
        val pairs = LinkedHashMap<String, Int>()
        Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(raw)?.also {
            pairs[it.groupValues[1]] = Qualities.Unknown.value
        }
        Regex("""video_(alt_)?url\s*:\s*'function/0/([^']*)'""", RegexOption.IGNORE_CASE)
            .findAll(raw).forEach {
                val isAlt = it.groupValues[1].isNotEmpty()
                val url = it.groupValues[2]
                val key = if (isAlt) "video_alt_url_text" else "video_url_text"
                val labelText = Regex(Regex.escape(key) + """\s*:\s*'([^']*)'""", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.get(1).orEmpty()
                pairs.putIfAbsent(url, labelToQuality(labelText))
            }
        Regex("""https://arabxn\.sex/get_file/[^\s"'<>]+""")
            .findAll(raw).forEach { pairs.putIfAbsent(it.value.trimEnd('\\', '"', '\''), Qualities.Unknown.value) }

        // حلّ 302 إلى رابط CDN النهائي (يحمل token بلا حاجة جلسة) عبر HEAD — بدون تحميل جسم الملف.
        // الشغّل لا يعرف كيف يمرر Cookie، لذلك نسلّمه CDN مباشرة.
        for ((candidate, q) in pairs) {
            val getFile = candidate.removePrefix("function/0/")
            if (!getFile.contains(".mp4") && !getFile.contains(".m3u8")) continue
            val reqHeaders = buildMap {
                put("Referer", mainUrl)
                cookieHeader?.let { put("Cookie", it) }
            }
            val cdn = try {
                val head = app.head(fixUrl(getFile), headers = reqHeaders)
                // المتابعة التلقائية للـ redirect: url النهائي؛ وإلا نقرأ Location يدوياً
                var finalUrl = head.url.toString()
                val loc = head.headers["Location"]
                if (!finalUrl.contains("cdn.arabxn.sex")) finalUrl = loc?.takeIf { it.contains(".mp4") }.orEmpty()
                finalUrl
            } catch (_: Exception) {
                fixUrl(getFile) // احتياط: لو فشل HEAD نرسل get_file (قد ينجح بالصدفة لكن غالباً 410)
            }
            if (!cdn.contains(".mp4") && !cdn.contains(".m3u8")) continue
            callback.invoke(
                newExtractorLink(
                    "arabxnsex",
                    "arabxn${if (q != Qualities.Unknown.value) " • ${q}p" else ""}",
                    cdn,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = q
                    this.referer = mainUrl
                }
            )
            found = true
        }

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