package com.arabx.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * عرب اكس — arabx.cam
 *
 * القائمة:  https://www.arabx.cam/   (بطاقات <div class="item">، روابط /<slug>/)
 * الأقسام:  /most-popular/  /top-rated/  /latest-updates/N/  و /categories/<قسم>/
 * البحث:    /search/?q=… (نتائج فعلية)
 * التفاصيل: iframe https://playeriz.com/embed-<id>.html
 * المشغل:   الكود المضغوط eval(function(p,a,c,k,e,d){…}) في embed يحوي
 *           master.m3u8 على s1.playiri.com — يُفكّ بمحلل أقواس متوازنة
 *           (لا regex DOTALL: يسبب StackOverflowError) ثم يُبثّ الرابط.
 */
class ArabxCamProvider : MainAPI() {
    override var mainUrl = "https://www.arabx.cam"
    override var name = "عرب اكس"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    /** المرجع يجب أن يكون ASCII فقط — مسار عربي في الـ referer يرمي IllegalArgumentException */
    private fun safeReferer(): String =
        Regex("""https?://[^/]+""").find(mainUrl)?.value ?: mainUrl

    private suspend fun fetchItems(url: String): List<SearchResponse> {
        return try {
            app.get(url, headers = defaultHeaders).document
                .select(".item, article, .post, .video-item")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = if (page <= 1) buildList {
            add(HomePageList("أحدث أفلام عرب اكس", fetchItems(mainUrl)))
            add(HomePageList("الأعلى مشاهدة", fetchItems("$mainUrl/most-popular/")))
            add(HomePageList("الأعلى تقييماً", fetchItems("$mainUrl/top-rated/")))
            add(HomePageList("سكس مترجم", fetchItems("$mainUrl/categories/سكس-مترجم/")))
            add(HomePageList("سكس امهات مترجم", fetchItems("$mainUrl/categories/سكس-امهات-مترجم/")))
            add(HomePageList("سكس محارم", fetchItems("$mainUrl/categories/سكس-محارم/")))
            add(HomePageList("سكس اخوات", fetchItems("$mainUrl/categories/سكس-اخوات/")))
        } else buildList {
            add(HomePageList("أحدث أفلام عرب اكس — صفحة $page", fetchItems("$mainUrl/latest-updates/$page/")))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = try {
            app.get("$mainUrl/search/?q=${query.trim()}", headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return document.select(".item, article, .post, .video-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.htitle, h1.entry-title, h1.title, h1")?.text()
            ?.substringBefore(" - ")
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" | ").substringBefore(" - ").trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.poster, .single-poster img, img[itemprop=image]")?.attr("src")
            ?: document.selectFirst("img.thumb")?.attr("data-original")
            ?: document.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster?.let { fixUrl(it) }
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
                newExtractorLink("arabx", serverHost(url), url, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = safeReferer()
                }
            )
            found = true
        }

        // 1) روابط مباشرة في صفحة التفاصيل
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

        // 2) embed playeriz — المشغل الحقيقي داخل eval مضغوط
        if (!found) {
            for (iframe in document.select("iframe[src]")) {
                val src = iframe.attr("src").ifBlank { continue }
                val html = try {
                    app.get(fixUrl(src), headers = defaultHeaders + ("Referer" to safeReferer())).text
                } catch (_: Exception) { continue }

                // مسارات حرة إن وجدت
                val plain = Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.value }.distinct()
                    .filterNot { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }
                for (c in plain) emit(c)

                if (!found) {
                    val unpacked = unpackPacked(html)
                    if (unpacked != null) {
                        findMasterM3U8(unpacked)?.let { emit(it) }
                    }
                }
            }
        }

        return found
    }

    // ---------- فكّ الكود المضغوط (Dean Edwards packer) ----------

    private fun unpackPacked(js: String): String? {
        val re = Regex("""eval\(function\s*\(p,a,c,k,e,d\)""")
        val m = re.find(js) ?: return null

        // مشي الأقواس المتوازنة حتى قوس إغلاق eval(...) — تجنّب regex DOTALL (StackOverflowError)
        // نبدأ من قوس "eval(" نفسه: عدّد كل '(' / ')' وأغلق عند عمق 0
        var depth = 0
        var inStr: Char? = null
        var i = m.range.first + 4 // عند '(' بعد eval(
        while (i < js.length) {
            val c = js[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
                i++; continue
            }
            when (c) {
                '\'', '"' -> inStr = c
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        if (depth != 0 || i >= js.length) return null
        val close = i

        // حدود وسائط الاستدعاء: آخر "}(" قبل الإغلاق
        val argsOpen = js.lastIndexOf("}(", close)
        if (argsOpen <= m.range.first) return null
        val args = splitTopLevel(js.substring(argsOpen + 2, close))
        if (args.size < 3) return null

        val radix = args.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (radix !in 2..36) return null
        val count = args.getOrNull(2)?.trim()?.toIntOrNull() ?: return null
        val packed = unescapeEval(args[0].trim())
        val dictStr = (args.getOrNull(3) ?: "")
            .substringAfter("'").substringBeforeLast("'")
        val dict = dictStr.split("|")

        // التبديل التنازلي تماماً مثل JS: while(c--) من count-1 إلى 0
        var out = packed
        for (c in (count - 1) downTo 0) {
            val key = if (radix == 16) Integer.toHexString(c) else c.toString(radix)
            val word = dict.getOrNull(c)
            if (!word.isNullOrEmpty() && word != "\\0") {
                out = out.replace(Regex("\\b" + Regex.escape(key) + "\\b")) { word }
            }
        }
        return out
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var inStr: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < s.length) { cur.append(s[i + 1]); i += 2; continue }
                if (c == inStr) inStr = null
                i++; continue
            }
            when (c) {
                '\'', '"' -> { inStr = c; cur.append(c) }
                '(' -> { depth++; cur.append(c) }
                ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { parts.add(cur.toString()); cur.setLength(0) } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        parts.add(cur.toString())
        return parts
    }

    private fun unescapeEval(s: String): String {
        var t = s.removePrefix("'").removeSuffix("'")
        t = t.replace("\\'", "'").replace("\\\\", "\\")
        return t
    }

    private fun findMasterM3U8(unpacked: String): String? {
        val re = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        return re.find(unpacked)?.value
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val rawTitle = link.attr("title").ifBlank { link.text().ifBlank { return null } }
        // العنوان النظيف = قبل " - " (الذيل "سكس مترجم | مراهقات,..." يُقص)
        val title = rawTitle.substringBefore(" - ").trim().ifBlank { rawTitle }
        val poster = this.selectFirst("img.thumb")?.attr("data-original")
            ?: this.selectFirst("img.thumb")?.attr("data-webp")
            ?: this.selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }
}