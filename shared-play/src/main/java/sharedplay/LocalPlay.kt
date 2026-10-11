package sharedplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Document

/**
 * محلّل روابط التشغيل المحلي — بديل `loadExtractor`.
 *
 * ## لماذا هذا الملف موجود
 * إضافات المستودع كانت تُسلّم رابط الـ iframe إلى `loadExtractor`، أي إلى
 * **المشغل الخارجي** (مشغلات CloudStream المدمجة + إضافة «مشغلات عربية»).
 * النتيجة أن التشغيل يعتمد على مشغل خارجي: إن غاب أو تغيّر كسره الموقع.
 *
 * هذا الملف يُخرج تلك الإضافة من المعادلة: يستقبل رابط الـ iframe بنفس
 * التوقيع، يجلب الصفحة بنفسه، ويستخرج رابط الوسائط منها مباشرةً ثم يستدعي
 * `callback` — أي أن كل سطر كان يذهب إلى مشغل خارجي يعود الآن رابطاً جاهزاً
 * للمشغّل الداخلي.
 *
 * ## ما يغطّيه
 * 1. `flashvars` بنمط KVS (video_url / video_alt_url / video_alt_url2 /
 *    video_hd_url) — أشهر بنية في هذه المواقع، ويُفكّ ترميز `function/0/`.
 * 2. الكود المضغوط (Dean Edwards packer) — يُفكّ ثم يُقرأ منه رابط m3u8.
 * 3. وسوم `<video>`/`<source>`.
 * 4. روابط mp4/m3u8 الخام داخل نصوص السكربتات.
 *
 * ## قاعدتان اتُّبعتا عن قصد
 * - **لا مشغّل خارجي**: لا استدعاء لـ `loadExtractor` ولا اعتماد على
 *   ExtractorApi مسجَّل في أي مكان.
 * - **لا إرث**: يبني `ExtractorLink` من الصفر بـ `newExtractorLink`، ولا
 *   يتعامل مع نوعٍ خاص بالمشغّل.
 */
object LocalPlay {

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val HEADERS = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    /** مرجع آمن للتغليف الأجنبي: بروتوكول+نطاق فقط بلا مسار. */
    fun origin(url: String): String =
        Regex("""https?://[^/]+""").find(url)?.value ?: url

    /**
     * رابط مطلق — بلا اعتماد على امتداد `fixUrl` (المرتبط بـ MainAPI).
     * المسارات النسبية تُبنى على أصل الصفحة المُحيلة، وهذا مهم لأن بعض المواقع
     * تعطي الإطار هكذا: `<iframe src="/embed/123">`.
     */
    private fun absolutize(u: String, referer: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> origin(referer) + u
        else -> u
    }

    /**
     * الوسائط المباشرة داخل صفحة الـ embed. النجاح = استُدعي `callback` مرة
     * على الأقل. أي فشل شبكي أو استثناء يُحسب فشلاً هادئاً (false) ليكمل
     * المُنادي إلى بديله.
     *
     * @param iframeUrl رابط الإطار (يُقبل نسبياً ويُصلَّح)
     * @param referer   الصفحة المُحيلة التي يطلبها الخادم عادةً
     */
    suspend fun resolve(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = iframeUrl.trim()
        if (url.isEmpty()) return false

        // لا نُطلق اسم «مشغل خارجي» على موارد مرمّزة أصلاً: kmplay / playerjs
        // بالماضي؛ نتركها تمرّ فقط إن كانت صفحة HTML حقيقية.
        val target = absolutize(url, referer)

        // الرابط المضغوط (function/0/<base64>) قد يصل مكان رابط الإطار نفسه
        // في بعض المواقع — نفكّه أولاً قبل أي جلب.
        val decoded = decodeBlob(target)
        val html = try {
            withTimeout(12000) {
                app.get(decoded, headers = HEADERS + ("Referer" to origin(referer))).text
            }
        } catch (_: Exception) {
            return false
        }
        if (html.isBlank()) return false

        val ref = origin(decoded)
        val seen = mutableSetOf<String>()
        var found = false

        // `newExtractorLink` دالة `suspend` (توقيعها في الجرة يحمل Continuation)،
        // فلا تصلح داخل دالة محليّة عادية — نُعلن المُصدِر `suspend`.
        suspend fun emit(raw: String, declaredQuality: Int = Qualities.Unknown.value) {
            val u = decodeBlob(raw)
            if (u.isBlank() || !u.startsWith("http")) return
            if (u in seen) return
            if (!looksLikeMedia(u)) return
            seen += u
            callback(
                newExtractorLink(
                    source = "مباشر",
                    name = "مباشر",
                    url = u,
                    type = if (isHls(u)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = ref
                    this.quality = declaredQuality
                }
            )
            found = true
        }

        // 1) flashvars — بأي من مسمّياتها الثلاثة (url / url: / "url":)
        val doc = try { org.jsoup.Jsoup.parse(html) } catch (_: Exception) { null }
        for (vars in flashvars(html)) for (v in vars) emit(v.first, v.second)
        if (found) return true

        // 2) الكود المضغوط (Dean Edwards packer) → أول m3u8/mp4 داخل الناتج
        unpackPacked(html)?.let { plain -> firstMediaUrl(plain)?.let { emit(it) } }
        if (found) return true

        // 3) وسوم الوسائط في الصفحة
        if (doc != null) {
            for (el in doc.select("video source[src], video[src], source[src]")) {
                val src = el.attr("src").ifBlank { el.attr("data-src") }
                if (src.isNotBlank()) emit(src)
            }
        }
        if (found) return true

        // 4) روابط خام في نصوص السكربتات
        if (doc != null) {
            for (s in doc.select("script")) {
                firstMediaUrl(s.data())?.let { emit(it) }
            }
        }
        if (found) return true

        // 5) رابط واحد خام في كل الصفحة (يُقصى أخيراً لأن ضجيجه أعلى)
        firstMediaUrl(html)?.let { emit(it) }

        return found
    }

    // ---------- أدوات ----------

    private fun isHls(u: String) = u.contains(".m3u8", true)

    private fun looksLikeMedia(u: String): Boolean {
        val l = u.lowercase()
        if (l.contains("get_file")) return false          // KVS: لا يُبَثّ مباشرة
        if (l.endsWith(".jpg") || l.endsWith(".png") || l.endsWith(".webp") ||
            l.endsWith(".jpeg") || l.endsWith(".gif")
        ) return false
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".m4v") ||
            l.contains(".webm") || l.contains(".mkv") || l.contains(".mpd")
    }

    private fun firstMediaUrl(text: String): String? =
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4|m4v|webm|mkv|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .find(text)?.value

    private fun decodeBlob(url: String): String {
        val body = when {
            url.startsWith("function/0/") ->
                url.removePrefix("function/0/")
            url.startsWith("function/") ->
                url.substringAfter("function/").substringAfter("/")
            else -> url
        }
        val decoded = if (body != url) {
            try {
                android.util.Base64.decode(body, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                body
            }
        } else url
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https/") && !decoded.startsWith("https://") ->
                "https://" + decoded.removePrefix("https/")
            else -> decoded
        }
    }

    /**
     * كل مجموعات flashvars في نص الصفحة: أزواج (رابط، جودة).
     * تُقرأ من كل `<script>` على حِدة لأن المفاتيح قد تتوزّع على كتل.
     */
    private fun flashvars(html: String): List<List<Pair<String, Int>>> {
        val out = ArrayList<List<Pair<String, Int>>>()
        for (script in Regex("""<script[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { it.groupValues[1] }) {
            val pairs = ArrayList<Pair<String, Int>>()
            for (key in listOf("video_url", "video_alt_url", "video_alt_url2", "video_hd_url")) {
                val value = rgx(script, key) ?: continue
                val q = rgx(script, "${key}_text")?.let { qualityOf(it) } ?: defaultQuality(key)
                pairs += value to q
            }
            if (pairs.isNotEmpty()) out += pairs
        }
        // الصفحة قد لا تُغلق الوسوم بشكل سليم — آخر محاولة على النص كله
        if (out.isEmpty()) {
            val pairs = ArrayList<Pair<String, Int>>()
            for (key in listOf("video_url", "video_alt_url", "video_alt_url2", "video_hd_url")) {
                val value = rgx(html, key) ?: continue
                pairs += value to (rgx(html, "${key}_text")?.let { qualityOf(it) } ?: defaultQuality(key))
            }
            if (pairs.isNotEmpty()) out += pairs
        }
        return out
    }

    private fun defaultQuality(key: String) = when (key) {
        "video_url" -> Qualities.Unknown.value
        "video_alt_url" -> 480
        "video_alt_url2" -> 720
        else -> 1080
    }

    private fun rgx(text: String, key: String): String? =
        Regex("""$key\s*[:=]\s*['"]([^'"]+)['"]""").find(text)
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun qualityOf(label: String): Int =
        Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

    // ---------- فكّ الكود المضغوط (Dean Edwards packer) ----------

    private fun unpackPacked(js: String): String? {
        val marker = Regex("""eval\(function\s*\(p,a,c,k,e,[dr]\)""").find(js) ?: return null

        // موازنة أقواس من '(' بعد eval حتى ')' المطابق، مع تجاهل النصوص
        val open = marker.range.last
        var depth = 0
        var inStr: Char? = null
        var close = -1
        var i = js.indexOf('(', marker.range.first)
        if (i < 0) return null
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
                ')' -> {
                    depth--
                    if (depth == 0) { close = i; break }
                }
            }
            i++
        }
        if (close <= 0) return null

        val argsOpen = js.lastIndexOf("}(", close)
        if (argsOpen <= marker.range.first) return null

        val args = splitTopLevel(js.substring(argsOpen + 2, close))
        if (args.size < 4) return null

        val radix = args[1].trim().toIntOrNull() ?: return null
        if (radix !in 2..36) return null
        val count = args[2].trim().toIntOrNull() ?: return null
        val packed = unquote(args[0].trim())
        val dict = unquote(args[3].trim()).split('|')

        // استبدال كلمة-بكلمة بخريطة واحدة: `\b` يُفشل الاستبدال حين تبدأ الكلمة
        // برقم ملتصق بغيره، والـ regex أعلاه يمرّ على رموز الكلمات فقط.
        val map = HashMap<String, String>()
        for (c in (count - 1) downTo 0) {
            val key = if (radix == 16) Integer.toHexString(c) else c.toString(radix)
            val word = dict.getOrNull(c)
            if (!word.isNullOrEmpty() && word != "\\0") map[key] = word
        }
        return packed.replace(Regex("[A-Za-z0-9_]+")) { m -> map[m.value] ?: m.value }
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
                '(', '[', '{' -> { depth++; cur.append(c) }
                ')', ']', '}' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { parts.add(cur.toString()); cur.setLength(0) } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        parts.add(cur.toString())
        return parts
    }

    private fun unquote(s: String): String {
        var t = s
        if (t.length >= 2 && (t.first() == '\'' || t.first() == '"') && t.last() == t.first())
            t = t.substring(1, t.length - 1)
        return t.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** راحة: القائمة كلها روابط وسائط في مستند — للأقسام التي تُعيد doc. */
    fun mediaIn(doc: Document): List<String> =
        doc.select("video source[src], video[src], source[src]")
            .mapNotNull { el -> el.attr("src").ifBlank { el.attr("data-src") }.takeIf { it.isNotBlank() } }
}
