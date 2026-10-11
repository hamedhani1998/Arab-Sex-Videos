package sharedplay

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeout

/**
 * فحص حياة روابط التشغيل **قبل** تسليمها للمشغّل.
 *
 * ## المشكلة التي يحلّها
 * كل إضافة كانت تُصدِر ما تجده في الصفحة بلا سؤال، فيصل المشغّل رابطٌ ميت
 * فينفجر بأحد أخطاء ExoPlayer:
 * - **2004** (`IO_BAD_HTTP_STATUS`): الخادم ردّ 404/403 — رابط منتهٍ أو محجوب.
 * - **3003** (`PARSING_CONTAINER_UNSUPPORTED`): وصلت بايتات ليست وسائط (صفحة HTML).
 * - **2001** (`IO_NETWORK_CONNECTION_FAILED`): فشل اتصال أو DNS أو TLS.
 *
 * عربدة مثلاً تنشر 240p و480p حيّين و**720p يردّ 404**؛ فمن يختار 720p يرى
 * «2004». الفحص هنا يُسقط الجودة الميتة فوراً ولا تظهر في القائمة أصلاً.
 *
 * ## قاعدتان من قياسات سابقة
 * 1. **نُقلّد طلب المشغّل لا طلباً قريباً منه.** المشغّل يرسل `Range` ويحمل
 *    `Referer`، وبعض الخوادم تختلف إجابتها بحسب الترويسة (رابط واحد كان
 *    يردّ 403 مع Range و410 بدونه). فالفحص يستعمل نفس الشكل.
 * 2. **رمز الحالة حكمٌ لا فشلُ فحص.** 404/403 تعني «الرابط ميت». أما
 *    الاستثناء (UnknownHost، SSLHandshake) فيعني «لم أستطع الحكم» — وهناك
 *    نفرّق: فشل TLS يُعدّ حكماً بالموت لأنه لن يُصلح نفسه لحظة التشغيل.
 */
object Probe {

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** يميّز «الرابط ميت» عن «لم أستطع الفحص» لأغراض السجل. */
    enum class Verdict { ALIVE, DEAD, UNKNOWN }

    /**
     * هل يخدم الخادم هذا الرابط وسائط فعلاً؟
     *
     * @param url     الرابط كما سيُسلَّم للمشغّل (بلا أي تعديل لاحق)
     * @param referer ما يرسله المشغّل مع الطلب
     * @return true فقط عند 2xx. أي رمز آخر أو استثناء = false.
     */
    suspend fun alive(url: String, referer: String? = null): Boolean =
        verdict(url, referer) == Verdict.ALIVE

    /**
     * الحكم مع سببه — يُستعمل في السجل حين نحتاج معرفة **لماذا** رُفض رابط.
     * الفصل مهم: «404» خبر عن الموقع، و«timeout» خبر عن الشبكة.
     */
    suspend fun verdict(url: String, referer: String? = null): Verdict {
        if (!url.startsWith("http")) return Verdict.DEAD
        val headers = HashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Range"] = "bytes=0-1"          // شكل طلب المشغّل
        headers["Accept"] = "*/*"
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        return try {
            val resp = withTimeout(12_000) {
                app.get(url, headers = headers, allowRedirects = true)
            }
            val code = resp.code
            when {
                code in 200..299 -> {
                    // رمز 200 لا يكفي: الموقع يحقن بكسل تتبّع على نفس نمط الرابط
                    // (`/get_file/…/3329.mp4/`) فيردّ 200 بمحتوى `image/gif` من
                    // 43 بايت. لو قُبل، ظهر سيرفر «شغّال» لا يبثّ شيئاً.
                    val ct = (resp.headers["Content-Type"]
                        ?: resp.headers["content-type"] ?: "").lowercase()
                    if (ct.startsWith("image/")) Verdict.DEAD else Verdict.ALIVE
                }
                // رموز الحكم بالموت: مورد غير موجود أو ممنوع أو منتهي.
                code == 401 || code == 403 || code == 404 || code == 410 ||
                    code == 451 -> Verdict.DEAD
                // 5xx عطل خادم لحظي — لا نحكم بالموت، لكن لا نسلّم رابطاً قد
                // يفشل أيضاً؛ نعدّه «غير معروف» فلا يُدرَج.
                else -> Verdict.UNKNOWN
            }
        } catch (e: Exception) {
            // فشل مصافحة TLS حكمٌ بالموت: لن يُصلح نفسه لحظة التشغيل.
            val name = e.javaClass.simpleName
            if (name.contains("SSL", true) || name.contains("Cert", true)) Verdict.DEAD
            else Verdict.UNKNOWN
        }
    }

    /**
     * يقبل أول رابط حيّ من قائمة مرشّحين — يوفّر طلبات حين يكون الأول سليماً.
     * يُعيد null إن مات الجميع.
     */
    suspend fun firstAlive(candidates: List<String>, referer: String? = null): String? {
        for (c in candidates) if (alive(c, referer)) return c
        return null
    }

    /** قائمة جودات KVS بصيغة «القيمة = اسم الجودة». */
    fun kvsQuality(label: String?): Int =
        Regex("""(\d{3,4})""").find(label ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** يستخرج معرّف الفيديو الرقمي من صفحة KVS (`video_id: '1971'`). */
    fun videoId(html: String): String? =
        Regex("""video_id\s*[:=]\s*['"]?(\d+)""").find(html)?.groupValues?.get(1)

    /**
     * رابط القرص المباشر على نمط KVS: `/contents/videos/<bucket>/<id>/<id>.mp4`
     * حيث `bucket = floor(id/1000)*1000`. هذا المسار يخدم الملف الخام بلا
     * توكن ولا وسيط، وهو الذي نجح على المواقع التي يردّ فيها `get_file` 404.
     */
    fun directKvsPaths(origin: String, videoId: String, withQualities: Boolean = true): List<String> {
        val id = videoId.toIntOrNull() ?: return emptyList()
        val bucket = (id / 1000) * 1000
        val base = "$origin/contents/videos/$bucket/$id/$id"
        val out = ArrayList<String>()
        out += "$base.mp4"
        if (withQualities) for (q in intArrayOf(1080, 720, 480, 360, 240)) out += "${base}_${q}p.mp4"
        return out
    }

    /** يجرّب أصل الموقع ثم أصل CDN على نمط KVS — يُعيد أول رابط حيّ. */
    suspend fun resolveKvsDirect(origin: String, videoId: String, referer: String): String? {
        val bare = origin.substringAfter("//").removePrefix("www.")
            .substringBefore('/')
        val bases = listOf(origin, "https://$bare.b-cdn.net", "https://cdn.$bare")
        for (b in bases) {
            for (p in directKvsPaths(b, videoId)) {
                if (alive(p, referer)) return p
            }
        }
        return null
    }

    /**
     * يزيل بادئة KVS `function/<n>/` ثم يعيد ما بعدها.
     *
     * في أغلب المواقع المقيسة هنا (`sex6x`, `3rabxn`, `sexallarab`) يكون ما بعد
     * البادئة **رابطاً صريحاً**، والبادئة مجرّد علامة على طريقة التوليد في
     * الخادم. وفي مواقع أخرى يكون المحتوى base64 — فنفكّه عند الحاجة فقط.
     */
    fun unwrapFunction(raw: String): String {
        if (!raw.startsWith("function/")) return raw
        val body = raw.removePrefix("function/").substringAfter("/")
        val text = if (body.startsWith("http") || body.startsWith("//")) {
            body
        } else {
            try {
                android.util.Base64.decode(body, android.util.Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
            } catch (_: Exception) {
                body
            }
        }
        return when {
            text.startsWith("//") -> "https:$text"
            text.startsWith("https/") && !text.startsWith("https://") ->
                "https://" + text.removePrefix("https/")
            else -> text
        }
    }
}
