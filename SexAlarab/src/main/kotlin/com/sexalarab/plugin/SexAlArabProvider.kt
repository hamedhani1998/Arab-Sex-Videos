package com.sexalarab.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * سكس العرب — SexAlarab (مصدر واحد يخدم sexalarab.com + sexalarab.net)
 *
 * البنية المؤكدة بالفحص المباشر (2026):
 *   القائمة:  https://sexalarab.com/?page=N   — بطاقات <div class="item">،
 *             العنوان في attribute title لكل <a>، والروابط /<slug-عربي>/.
 *   التفاصيل: https://sexalarab.com/<slug>/   — flashvars كاملة:
 *             video_url / video_alt_url / video_alt_url2 (mp4 ثابت الجودة:
 *             360p/480p/720p) مع token عبر license_code + lrc + rnd،
 *             ومشغل swf قديم (بنية function/0/https://sexalarab.com/get_file/...).
 *   البحث:    https://sexalarab.com/search/<استعلام>/
 */
class SexAlArabProvider : MainAPI() {
    override var mainUrl = "https://sexalarab.com"
    override var name = "سكس العرب"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "ar"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8",
        "Referer" to mainUrl
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val title = link.attr("title").ifBlank { link.text().ifBlank { return null } }
        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /** جلب عنصر قائمة (div.item وغيرها) من أي صفحة فهرسة */
    private suspend fun fetchItems(url: String): List<SearchResponse> {
        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return document.select("div.item, article, .post, .video-item")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = if (page <= 1) buildList {
            add(HomePageList("أحدث أفلام سكس العرب", fetchItems(mainUrl)))
            add(HomePageList("الأعلى مشاهدة", fetchItems("$mainUrl/most-viewed/")))
            add(HomePageList("الأعلى تقييماً", fetchItems("$mainUrl/top-rated/")))
            add(HomePageList("سكس مترجم", fetchItems("$mainUrl/categories/سكس-مترجم/")))
            add(HomePageList("سكس امهات مترجم", fetchItems("$mainUrl/categories/سكس-امهات-مترجم/")))
            add(HomePageList("سكس محارم", fetchItems("$mainUrl/categories/سكس-محارم/")))
            add(HomePageList("سكس اخوات", fetchItems("$mainUrl/categories/سكس-اخوات/")))
        } else buildList {
            add(HomePageList("أحدث أفلام عرب سكس — صفحة $page", fetchItems("$mainUrl/?page=$page")))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.trim()}/"
        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            app.get("$mainUrl/search/?s=${query.trim()}", headers = defaultHeaders).document
        }
        return document.select("article, .post, .item, .video-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.htitle, h1.entry-title, h1.title, h1")?.text()
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: document.title().substringBefore("|").trim()
            ?: return null

        val poster = document.selectFirst("img.poster, .single-poster img, img[itemprop=image]")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = document.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    private suspend fun loadFlashvars(
        document: Document,
        data: String,
        defaultHeaders: Map<String, String>,
        emit: (url: String, quality: Int) -> Unit
    ): Boolean {
        var found = false
        val raw = document.select("script").joinToString("\n") { it.html() }

        // النمط الأصلي: flashvars في التفاصيل يحوي video_url + video_alt_url
        // (mp4 مع جودة: _360p,_480p,_720p) — أُرسل كما هي (قاعدة get_file).
        val native = Regex("""video_(alt_)?url\s*:\s*'([^']*)'""", RegexOption.IGNORE_CASE)
            .findAll(raw).map {
                val isAlt = it.groups[1] != null
                Triple(isAlt, it.groupValues[2], qualityFromText(isAlt, raw))
            }.distinctBy { it.second }
        for ((_, url, q) in native) {
            if (url.contains(".mp4") || url.contains(".m3u8")) {
                emit(url, q)
                found = true
            }
        }
        return found
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
                newExtractorLink("sexalarab", serverHost(url), url, type) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            found = true
        }

        val raw = app.get(data, headers = defaultHeaders).text

        // 1) flashvars مباشرة (video_(alt_)?url) — mp4 متدرج بأسماء جودة
        val native = Regex("""video_(alt_)?url\s*:\s*'([^']*)'""", RegexOption.IGNORE_CASE)
            .findAll(raw).map {
                val isAlt = it.groups[1] != null
                Triple(isAlt, it.groupValues[2], qualityFromText(isAlt, raw))
            }.distinctBy { it.second }
        for ((_, url, q) in native) {
            if (url.contains(".mp4") || url.contains(".m3u8")) emit(url, q)
        }
        if (found) return true

        // 2) vidios مباشرة من التفاصيل
        val document = app.get(data, headers = defaultHeaders).document
        for (el in document.select("video source[src], video[src], source[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) emit(fixUrl(src))
        }
        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if (href.contains(".mp4") || href.contains(".m3u8")) emit(fixUrl(href))
        }

        return found
    }

    private fun qualityFromText(isAlt: Boolean, raw: String): Int {
        val key = if (isAlt) "video_alt_url_text" else "video_url_text"
        val label = Regex(key + """\s*:\s*'([^']*)'""").find(raw)?.groupValues?.get(1)
        val num = label?.let { Regex("""(\d{3,4})p?""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return num ?: Qualities.Unknown.value
    }

    // ---------- فكّ الكود المضغوط (Dean Edwards packer) ----------

    private fun unpackPacked(js: String): String? {
        val re = Regex("""eval\(function\s*\(p,a,c,k,e,d\)""")
        val m = re.find(js) ?: return null

        var depth = 0
        var inStr: Char? = null
        var i = m.range.first + 4
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

        val argsOpen = js.lastIndexOf("}(", close)
        if (argsOpen <= m.range.first) return null
        val args = splitTopLevel(js.substring(argsOpen + 2, close))
        if (args.size < 3) return null

        val radix = args.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (radix !in 2..36) return null
        val count = args.getOrNull(2)?.trim()?.toIntOrNull() ?: return null
        val packed = unescapeEval(args[0].trim())
        val dictStr = (args.getOrNull(3) ?: "")
            .substringAfter("'").substringBefore("'")
        val dict = dictStr.split("|")

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

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
            .substringBefore(" | ")
            .trim()
        val parts = t.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        t = if (parts.size >= 3) parts[1] else parts.firstOrNull() ?: t
        t = t.replace("مترجم", "").replace("مدبلج", "").trim()
        return t.ifBlank { parts.firstOrNull() ?: raw }
    }

    private fun serverHost(url: String): String {
        val host = Regex("""https?://([^/:]+)""").find(url)?.groupValues?.get(1)
        return host?.removePrefix("www.") ?: "سيرفر"
    }
}
