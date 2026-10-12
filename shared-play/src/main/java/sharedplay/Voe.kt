package sharedplay

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

/**
 * فكّ تشفير لاعب **voe.sx** — المصدر الوحيد لبعض مواقع هذه العائلة.
 *
 * ## لماذا فكّ خاصّ بدل مناداة مستخرِج التطبيق
 * التطبيق نفسه يحمل `com.lagradost.cloudstream3.extractors.Voe`، فكان الأقرب
 * أن يُمرَّر عنوان الإطار إلى `loadExtractor`. وقد جُرّب الطريق وقرئت بايت
 * كوده: `loadExtractor` يستدعي `getExtractorApiFromName` على المضيف، ثم
 * `Voe.getUrl` — وكل ذلك سليم. لكن **`extractorApis` لا تحتوي `Voe`**،
 * فقائمة المستخرِجات المدمجة تُبنى من ضِمنيّات Kotlin في وحدة الإضافات
 * (`registerExtractorApi`)، لا من أصناف التطبيق. فالطريق مسدود عند الجدول لا
 * عند الدالة. ولهذا يُنقل الفكّ هنا بحرفه من `Voe.decryptF7` المقروءة من
 * البايت كود لا من التخمين.
 *
 * ## سلسلة الفكّ — كلها عمليات بايت، بلا JS ولا WASM
 * ```
 * rot13 → العلامات السبع → حذف الشرطات السفلية → base64
 *       → إزاحة الحروف −3 → عكس النصّ → base64 → JSON
 * ```
 * والعلامات السبع من `Voe.replacePatterns` حرفياً: `@$` `^^` `~@` `%?` `*~`
 * `!!` `#&`، وكلٌّ منها يُبدَّل بشرطة سفلية ثم تُحذف كل الشرطات.
 * وإزاحة الحروف **س**البة: البايت كود يقرأ `(c.code - 3).toChar()` لا زائداً.
 *
 * ## الطريق إلى الصفحة التي تحمل الحمولة
 * `/e/<id>` على voe.sx يردّ صفحة توجيه لا حمولة فيها، وسكربتها يكتب
 * `window.location.href = 'https://<مضيف>/e/<id>'`. والمضيف **يتبدّل**، فلا
 * يُكتب ثابتاً في الكود بل يُقرأ من السكربت، ويسقط الاتجاه إلى مضيفٍ معروف
 * إن لم يُقرأ.
 *
 * ## الحقول المقيسة في الناتج (2026-10-12)
 * - `source` — سيد HLS، نوعه `application/vnd.apple.mpegurl` صحيح.
 * - `direct_access_url` — ملف mp4 كامل، نسخة واحدة منخفضة الجودة (`_l`).
 * - `fallback` و`captions` و`audio_languages` — **مصفوفات فارغة** في كل ما
 *   قيس، فلا مسارات ترجمة يُمرَّر منها شيء أصلاً.
 * - `key` — مفتاح تشفير قطاعات عند بعض المضيفين، لا رابط.
 *
 * ## لا حساسية للترويسات، وهذا مقيس من أجل [Probe]
 * فُحص الرابط بخمس صيغ ترويسات (بلا شيء، Referer، Range، الاثنان معاً،
 * ومعهما `Origin`) على السيد وعلى الـmp4: **كلها 200/206 بالنتيجة نفسها**.
 * فلأن [Probe] لا يرسل `Origin` لا ضرر — وهذه الدقّة لازمة لأن موقعاً آخر في
 * العائلة كانت حساسيته للترويسة هي سبب بدوّره ميتاً.
 */
object Voe {

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** العلامات السبع من `Voe.replacePatterns` حرفياً. */
    private val PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    /** مضيفات voe.sx المعروفة — للتعرّف على الرابط وللاتجاه عند فشل القراءة. */
    private val HOSTS = listOf("voe.sx", "megankiddifference.com")

    /** الرابطان المفكوكان؛ أيّهما كان null لا يُمرَّر. */
    data class Result(val hls: String?, val mp4: String?)

    /**
     * هل هذا رابط voe؟
     *
     * يُفحص **بالمضيف** لا بالتضمين: كلمة `voe` تظهر في مسارات مواقع أخرى،
     * ففحص `contains` كان سيجرّ فكّاً على صفحة لا علاقة لها فيضيع زمن بلا فائدة.
     */
    fun isVoe(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * يفكّ صفحة voe ويُعيد روابطها.
     *
     * @param url     عنوان صفحة الـembed (`https://voe.sx/e/<id>`)
     * @param referer الصفحة المُحيلة (صفحة الفيلم)
     * @return الروابط، أو null إن لم يُفكّ
     */
    suspend fun resolve(url: String, referer: String?): Result? {
        val id = url.substringAfterLast('/').substringBefore('?').trim()
        if (id.isBlank()) return null

        // 1) الصفحة الأولى قد تكون توجيهاً لا محتوى — نقرأ مضيفها من سكربتها.
        val first = fetch(url, referer)
        val host = first?.let { redirectHost(it) }
            ?: url.substringBefore("/e/").ifBlank { "https://voe.sx" }
        val target = "$host/e/$id"

        // 2) صفحة الحمولة. والمضيفات المعروفة احتياطٌ لمن تغيّر اسمه.
        val candidates = (listOf(target) + HOSTS.map { "https://$it/e/$id" }).distinct()

        for (c in candidates) {
            // الصفحة الأولى تُعاد بلا طلبٍ ثانٍ **إن كان هذا هو عنوانها نفسه**.
            // ولا يُسأل: «هل تحتوي `/e/<id>`؟» — فصفحة التوجيه تحتويه في نصّ
            // سكربتها، فيُعاد استعمالها وهي لا حمولة فيها، ويُستنتج خطأً أن
            // voe لا يُفكّ. المطابقة تكون على العنوان كاملاً، لا على أثرٍ منه.
            val html = if (c == url) first else fetch(c, referer ?: url)
            val encrypted = html?.let { encryptedPayload(it) } ?: continue
            val json = decrypt(encrypted) ?: continue
            parse(json)?.let { if (it.hls != null || it.mp4 != null) return it }
        }
        return null
    }

    /** جلب بنفس ترويسات المشغّل. */
    private suspend fun fetch(url: String, referer: String?): String? = try {
        app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to UA, "Accept-Language" to "ar,en;q=0.8")
        ).text
    } catch (_: Exception) {
        null
    }

    /**
     * مضيف التوجيه من سكربت الصفحة الوسيطة.
     *
     * الشكل المقيس: `window.location.href = 'https://megankiddifference.com/e/<id>'`.
     */
    private fun redirectHost(page: String): String? =
        Regex("""location\s*\.\s*(?:href|replace)\s*[=(]\s*['"](https?://[^'"]+)['"]""")
            .find(page)?.groupValues?.get(1)
            ?.substringBefore("/e/")
            ?.takeIf { it.startsWith("http") }

    /**
     * الحمولة المشفّرة من `script[type=application/json]`.
     *
     * تُقرأ بصيغتين لأن الشكل يتغيّر: مصفوفة JSON صحيحة، أو نصّ مصفوفة
     * يُقطَع بين `["` و`"]` — وهي الصيغة التي يستعملها مستخرِج التطبيق بالحرف.
     * فمن قرأ بصيغة واحدة أعلن «لا حمولة» على صفحةٍ فيها حمولة.
     */
    private fun encryptedPayload(html: String): String? {
        val script = Regex(
            """<script[^>]*type\s*=\s*['"]application/json['"][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.get(1)?.trim() ?: return null

        runCatching {
            val arr = JSONArray(script)
            if (arr.length() > 0) {
                val first = arr.optString(0, "")
                if (first.isNotBlank()) return first
            }
        }
        return script.substringAfter("[\"", "").substringBeforeLast("\"]", "")
            .ifBlank { null }
    }

    /** سلسلة الفكّ، منقولة من `Voe.decryptF7`. */
    private fun decrypt(encrypted: String): String? = runCatching {
        val rolled = rot13(encrypted)
        var s = rolled
        for (p in PATTERNS) s = s.replace(p, "_")
        s = s.replace("_", "")
        s = b64(s)
        s = s.map { (it.code - 3).toChar() }.joinToString("")
        s = b64(s.reversed())
        s.ifBlank { null }
    }.getOrNull()

    private fun rot13(s: String): String = s.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")

    /**
     * base64 مع فكّ احتياطي بالأبجدية البديلة.
     *
     * الحمولة المقيسة تستعمل `+/` القياسيين، و`Base64.DEFAULT` يقرأهما. لكن
     * `-` و`_` تظهر في أبجدية URL، و`DEFAULT` **يرمي** عليها (`IllegalArgumentException`)
     * بدل أن يتجاهلها — فلا بدّ من محاولة ثانية بـ`URL_SAFE`.
     */
    private fun b64(s: String): String {
        val bytes = runCatching { Base64.decode(s, Base64.DEFAULT) }
            .getOrElse { Base64.decode(s, Base64.URL_SAFE) }
        return String(bytes, Charsets.UTF_8)
    }

    /** يستخرج الرابطين من الناتج المفكوك. */
    private fun parse(json: String): Result? = runCatching {
        val obj = JSONObject(json)
        Result(
            hls = obj.optString("source", "").takeIf { it.startsWith("http") },
            mp4 = obj.optString("direct_access_url", "").takeIf { it.startsWith("http") }
        )
    }.getOrNull()
}
