package com.arabx.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * عرب اكس — arabx.cam
 *
 * القائمة/PAGE:  div.item  +  strong.title  +  img.thumb + div.rating
 * الأقسام:       /latest-updates/  /most-popular/  /top-rated/  /categories/<قسم>/
 * البحث:         /search/?q=…
 * التفاصيل:      iframe https://playeriz.com/embed-<id>.html
 * المشغل:        * KVS get_file (mp4) في سكربت التفاصيل — يعمل فقط لو get_file/1/<md5>/3000/
 *                * أو صفحة embed playeriz يجرّبها محلياً (unpackPacked) لإيجاد master.m3u8
 *                * أو روابط mp4/m3u8 مباشرة في سكربتات الصفحة
 * كلها تحل محلياً — لا حاجة لمشغل خارجي/إضافة extractor.
 */
class ArabxCamProvider : MainAPI() {
    private val TAG = "arabx"
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

    /** المرجع ASCII فقط — مسار عربي في الـ referer يرمي IllegalArgumentException قبل الطلب */
    private fun safeReferer(): String =
        Regex("""https?://[^/]+""").find(mainUrl)?.value ?: mainUrl

    // ---------- الواجهة الرئيسية ----------

    private suspend fun fetchItems(url: String): List<SearchResponse> {
        return try {
            kotlinx.coroutines.withTimeout(15000) {
                val doc = app.get(url, headers = defaultHeaders).document
                doc.select("div.item").mapNotNull { it.toSearchResponse() }
            }.distinctBy { it.name }.also {
                android.util.Log.i(TAG, "fetchItems url=$url items=${it.size}")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchItems fail ${e.javaClass.simpleName}: ${e.message} url=$url")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = if (page <= 1) {
            // جلب متوازٍ لكل الأقسام — شبكة متعثرة لا تُراكم التأخر التسلسلي
            coroutineScope {
                val sections = listOf(
                    "أحدث أفلام عرب اكس" to mainUrl,
                    "الأعلى مشاهدة" to "$mainUrl/most-popular/",
                    "الأعلى تقييماً" to "$mainUrl/top-rated/",
                    "سكس مترجم" to "$mainUrl/categories/سكس-مترجم/",
                    "سكس امهات مترجم" to "$mainUrl/categories/سكس-امهات-مترجم/",
                    "سكس محارم" to "$mainUrl/categories/سكس-محارم/",
                    "سكس اخوات" to "$mainUrl/categories/سكس-اخوات/"
                )
                sections.map { (label, url) ->
                    async { label to fetchItems(url) }
                }.awaitAll().map { (label, items) -> HomePageList(label, items) }
            }
        } else buildList {
            add(HomePageList("أحدث أفلام عرب اكس — صفحة $page", fetchItems("$mainUrl/latest-updates/page/$page/")))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            kotlinx.coroutines.withTimeout(8000) {
                val doc = app.get("$mainUrl/search/?q=${query.trim()}", headers = defaultHeaders).document
                doc.select("div.item").mapNotNull { it.toSearchResponse() }
            }.distinctBy { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- التفاصيل ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: doc.title().substringBefore(" - ").trim()
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.thumb")?.attr("data-original")
                ?: doc.selectFirst("img.poster, .single-poster img")?.attr("src")
                ?: doc.selectFirst("img")?.attr("src")
            newMovieLoadResponse(clean(title), url, TvType.NSFW, url) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = doc.selectFirst("meta[name=description]")?.attr("content")
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- روابط التشغيل (محلية فقط) ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        suspend fun emit(url: String, quality: Int = Qualities.Unknown.value) {
            // تحديد النوع صراحة — m3u8 أم mp4 (لا استدلال بالـ path فينكسر مع ?token)
            val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink("arabx", serverHost(url), url, type) {
                    this.quality = quality
                    this.referer = safeReferer()
                }
            )
            found = true
        }

        val doc = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }
        val raw = doc.html()

        // ═══ 1) روابط get_file في سكربت التفاصيل (KVS): video_url / video_alt_url / hd
        //        تعمل فقط لو get_file/1/<md532>/3000/ (تلك تعيد 302 إلى الملف الفعلي).
        val candScript = doc.select("script").map { it.html() }
            .firstOrNull { it.contains("video_url") && it.contains("get_file") }
        if (candScript != null) {
            listOf(
                rgx(candScript, "video_url") to rgx(candScript, "video_url_text"),
                rgx(candScript, "video_alt_url") to rgx(candScript, "video_alt_url_text"),
                rgx(candScript, "video_alt_url2") to rgx(candScript, "video_alt_url2_text"),
                rgx(candScript, "video_hd_url") to rgx(candScript, "video_hd_url_text")
            ).forEach { (url, q) ->
                if (url != null && isWorkingGetFile(url)) {
                    android.util.Log.i(TAG, "M1 emit url=${url.take(70)} q=$q")
                    emit(cln(url), qualityFromLabel(q))
                }
            }
            if (found) return true
        }

        // ═══ 2) مشغل embed (playeriz / محلي KVS) — نجرّبه محلياً فقط
        android.util.Log.i(TAG, "M2 مسح embeds / twitter:player…")
        val embeds = doc.select("iframe[src]").map { it.attr("src") }
            .filter { it.isNotBlank() && !it.contains("google") && !it.contains("doubleclick") && !it.contains("propaganda") }
        val tw = doc.selectFirst("meta[name='twitter:player']")?.attr("content")
        val order = buildList {
            embeds.forEach { add(it) }
            if (!tw.isNullOrBlank()) add(tw)
        }
        var tried = 0
        for (embedUrl in order) {
            if (found || tried++ >= 2) break
            val resolved = fixUrl(embedUrl)
            android.util.Log.i(TAG, "embed try #$tried: $resolved")
            val html = try {
                kotlinx.coroutines.withTimeout(10000) {
                    app.get(resolved, headers = defaultHeaders + ("Referer" to safeReferer())).text
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "embed fetch fail (${e.javaClass.simpleName}): ${e.message}")
                null
            }
            if (html != null) {
                android.util.Log.i(TAG, "embed fetched ${html.length}B")
                // 2a) كود مضغوط → master.m3u8
                unpackPacked(html)?.let { unpacked ->
                    findMasterM3U8(unpacked)?.let {
                        android.util.Log.i(TAG, "unpacked master: $it")
                        emit(it)
                    }
                }
                // 2b) روابط free داخل صفحة الـ embed (mp4/m3u8)
                if (!found) {
                    Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                        .findAll(html).map { it.value }.distinct()
                        .filterNot { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }
                        .forEach { emit(it) }
                }
            }
            if (found) break
        }
        if (found) return true

        // ═══ 3) HTML5 <video>/<source> في التفاصيل
        android.util.Log.i(TAG, "M3 video tags…")
        for (el in doc.select("video source[src], video[src], source[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) emit(fixUrl(src))
        }
        if (found) return true

        // ═══ 4) روابط mp4/m3u8 مباشرة في نصوص السكربتات (استبعاد get_file — يُعالج أعلاه أو 403)
        android.util.Log.i(TAG, "M4 direct urls في scripts…")
        val scriptTexts = doc.select("script").joinToString("\n") { it.data() }
        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(scriptTexts).map { it.value }.distinct()
            .filter { !it.contains("get_file") }
            .filterNot { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }
            .forEach { emit(it) }

        android.util.Log.i(TAG, "loadLinks done found=$found")
        return found
    }

    // ---------- أدوات ----------

    private fun serverHost(url: String): String {
        val host = Regex("""https?://([^/:]+)""").find(url)?.groupValues?.get(1)
        return host?.removePrefix("www.") ?: "سيرفر"
    }

    private fun qualityFromLabel(q: String?): Int {
        if (q.isNullOrBlank()) return Qualities.Unknown.value
        val num = Regex("""(\d{3,4})p?""").find(q)?.groupValues?.get(1)?.toIntOrNull()
        return num ?: Qualities.Unknown.value
    }

    private fun rgx(script: String, key: String): String? {
        val m = Regex("""$key\s*[:=]\s*['"]([^'"]+)['"]""").find(script) ?: return null
        return m.groupValues[1].ifBlank { null }
    }

    /** get_file يعمل فقط عندما يحمل md5 (get_file/1/<md532>/3000/…) — تلك تعيد 302 للملف. */
    private fun isWorkingGetFile(url: String): Boolean {
        return !url.contains("get_file") || Regex("""get_file/1/[a-f0-9]{32}/3000/""").containsMatchIn(url)
    }

    /** كشف ترميز KVS: function/0/<base64> — فك؛ و https/ و // و / */
    private fun cln(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> {
                try {
                    android.util.Base64.decode(url.removePrefix("function/0/"), android.util.Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                } catch (_: Exception) {
                    url.removePrefix("function/0/")
                }
            }
            else -> url
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https/") -> "https://${decoded.removePrefix("https/")}"
            else -> decoded
        }
    }

    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> url
    }

    private fun clean(s: String): String = s.trim().replace(Regex("""\s+"""), " ")

    // ---------- فكّ الكود المضغوط (Dean Edwards packer) ----------

    private fun unpackPacked(js: String): String? {
        val re = Regex("""eval\(function\s*\(p,a,c,k,e,d\)""")
        val m = re.find(js) ?: return null

        // جسم المزوّر {…} وواجهته }( : نتحرك من بعد e,d) بموازنة الأقواس المتعرجة
        // (خارج النصوص) حتى جسم-close '}' ثم نأخذ '(' الـ IIFE بعدها. هذا يصلح
        // packer مع متن `while(c--)…` — العداد القديم كان يقف عند أول ')' في while(c--).
        var brace = 0
        var inStr: Char? = null
        var i = m.range.last  // بعد e,d)
        var argsOpen = -1
        var close = -1
        while (i < js.length) {
            val c = js[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
                i++; continue
            }
            when (c) {
                '\'', '"' -> inStr = c
                '{' -> brace++
                '}' -> {
                    brace--
                    if (brace == 0) {
                        var k = i + 1
                        while (k < js.length && js[k] in charArrayOf(' ', '\t', '\r', '\n')) k++
                        if (k < js.length && js[k] == '(') {
                            argsOpen = k
                            // وازن الأقواس من '(' حتى ')' المطابق — يشمل النصوص المتداخلة
                            var depth = 0
                            var s2: Char? = null
                            var kk = k
                            while (kk < js.length) {
                                val cc = js[kk]
                                if (s2 != null) {
                                    if (cc == '\\') { kk += 2; continue }
                                    if (cc == s2) s2 = null
                                    kk++; continue
                                }
                                when (cc) {
                                    '\'', '"' -> s2 = cc
                                    '(' -> depth++
                                    ')' -> {
                                        depth--
                                        if (depth == 0) { close = kk; break }
                                    }
                                }
                                kk++
                            }
                        }
                        break
                    }
                }
            }
            i++
        }
        if (argsOpen < 0 || close <= argsOpen) return null

        val args = splitTopLevel(js.substring(argsOpen + 1, close))
        if (args.size < 3) return null

        val radix = args.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (radix !in 2..36) return null
        val count = args.getOrNull(2)?.trim()?.toIntOrNull() ?: return null
        val packed = unescapeEval(args[0].trim())
        val dictStr = (args.getOrNull(3) ?: "").substringAfter("'").substringBeforeLast("'")
        val dict = dictStr.split("|")

        // استبدال كل مفتاح base-N بخريطة واحدة (لا \b — تصادم "2" داخل "28" يفسد روابط s1.playiri)
        val map = HashMap<String, String>()
        for (c in (count - 1) downTo 0) {
            val key = if (radix == 16) Integer.toHexString(c) else c.toString(radix)
            val word = dict.getOrNull(c)
            if (!word.isNullOrEmpty() && word != "\\0") map[key] = word
        }
        return packed.replace(Regex("[0-9a-z]+")) { mm -> map[mm.value] ?: mm.value }
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

    // ---------- البطاقة ----------

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = this.selectFirst("strong.title")?.text()?.trim()
            ?: a.attr("title").ifBlank { a.text().ifBlank { return null } }
        val poster = extractPoster(this)
        val rating = this.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
        return newMovieSearchResponse(clean(title), href, TvType.NSFW) {
            this.posterUrl = poster?.let { fixUrl(it) }
            if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
        }
    }

    private fun extractPoster(item: Element): String? {
        val img = item.selectFirst("img.thumb")
            ?: item.selectFirst("img[data-original]")
            ?: item.selectFirst("img[data-src]")
            ?: item.selectFirst("img.lazy")
            ?: item.selectFirst("img")
            ?: return null
        return listOf("data-original", "data-src", "data-webp", "src")
            .firstNotNullOfOrNull { attr ->
                img.attr(attr).takeIf {
                    it.isNotBlank() && !it.contains("placeholder") && !it.contains("data:image")
                }
            }
    }
}