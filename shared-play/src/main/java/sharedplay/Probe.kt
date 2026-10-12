package sharedplay

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
     * ## لماذا إعادة واحدة عند «غير معروف» فقط
     * قياس على `api.sexalarab.net/hls/…/master.m3u8` (2026-10-12، 40 محاولة
     * متتالية بنفس شكل طلب المشغّل): 38 نجحت من أول محاولة، ومحاولتان
     * انتهت مهلتهما، **وكلتاهما نجحت في إعادة فورية** — فمعدّل الفشل
     * المتقطّع نحو 5%، والإعادة تُنقذ 100% منه.
     *
     * والأثر أكبر من 5%: `SexAlarabNet` يجد على صفحة الفيديو **مرشّحاً
     * واحداً**، فسقوطه مرّة يعني «لا روابط» في تلك الفتحة بلا سبب حقيقي.
     * وفي [aliveSubset] يسقط المرشّح من الدفعة فيضيع سيرفر كامل.
     *
     * والإعادة **لا** تُطبَّق على DEAD: 404/403 حكم صادر عن الخادم، وإعادته
     * تُضاعف زمن رابط ميت مؤكّد بلا أن تُغيّر الحكم — وهي التي رفعنا المهلة
     * لأجلها. الشرط `== UNKNOWN` هو الفرق بين إصلاحٍ وتكرار نفس العطل.
     */
    private const val RETRIES_ON_UNKNOWN = 1

    /**
     * الحكم مع سببه — يُستعمل في السجل حين نحتاج معرفة **لماذا** رُفض رابط.
     * الفصل مهم: «404» خبر عن الموقع، و«timeout» خبر عن الشبكة.
     *
     * ## المهلة 8 ثوان لا 12
     * المهلة هنا سقفٌ يُدفَع لكل مرشّح ميت، لا زمن انتظار فعلي لرابط حيّ.
     * ومقيس على هذه العائلة أن المضيف السليم يردّ أول بايت في 1.5–3 ثوان
     * (`arabxn.sex` عبر `cdn.arabxn.sex` كان 2.4–3.1 ث في عشر قياسات متتالية)
     * وأن المضيف المعلّق يستهلك السقف كاملاً. فسقف 12 ثانية كان يضاعف انتظار
     * الميت بلا فائدة: ما يرد في 12 سيرد في 8، وما لا يرد فيها لا يرد.
     * وخفضُه إلى 8 يقلّص أسوأ حالة في الدفعة المتوازية بنحو الثلث — وهو
     * الذي يُظهر للمستخدم خطأ 2002 (انتهت مهلة الاتصال).
     */
    suspend fun verdict(url: String, referer: String? = null): Verdict {
        var v = attempt(url, referer)
        // إعادة عند «لم أستطع الحكم» وحدها — لا عند الموت المؤكّد ولا عند الحياة.
        var tries = 0
        while (v == Verdict.UNKNOWN && tries++ < RETRIES_ON_UNKNOWN) {
            v = attempt(url, referer)
        }
        return v
    }

    /** محاولة واحدة — الفصل عن [verdict] ليمكن إعادتها بلا تكرار الشروط. */
    private suspend fun attempt(url: String, referer: String? = null): Verdict {
        if (!url.startsWith("http")) return Verdict.DEAD
        val headers = HashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Range"] = "bytes=0-1"          // شكل طلب المشغّل
        headers["Accept"] = "*/*"
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        return try {
            val resp = withTimeout(8_000) {
                app.get(url, headers = headers, allowRedirects = true)
            }
            val code = resp.code
            when {
                code in 200..299 -> {
                    // رمز 200 لا يكفي — ولا نوع المحتوى وحده. قياسان:
                    // 1) الموقع يحقن بكسل تتبّع على نمط الرابط نفسه، فيردّ 200
                    //    بمحتوى `image/gif` من 43 بايت.
                    // 2) رابط `get_file` بلا توكن لا يردّ 404 بل **يعيد صفحة
                    //    الموقع الرئيسية** بمحتوى `text/html` ورمز 200. لو
                    //    قُبل لَما ظهر «لا روابط» بل ظهر خطأ 3003 في المشغّل
                    //    (بايتات ليست وسائط) — وهو أسوأ لأنه يبدو عطلاً في
                    //    التطبيق لا في الرابط.
                    val ct = (resp.headers["Content-Type"]
                        ?: resp.headers["content-type"] ?: "").lowercase()
                    when {
                        ct.startsWith("image/") ||
                            ct.startsWith("text/html") ||
                            ct.startsWith("text/plain") ||
                            ct.startsWith("application/json") -> Verdict.DEAD
                        else -> Verdict.ALIVE
                    }
                    // ملاحظة مقيسة: موقع Sexalarab11 يسم `index.m3u8` بـ
                    // `application/vnd.apple.mpegurl` (صحيح) لكن قطاعات البث
                    // نفسها يسمّيها `image/jpeg` وبايتاتها MPEG-TS حقيقية.
                    // وهذا لا يضرّ هنا لأن الفحص يقع على `index.m3u8` لا على
                    // القطاعات، والمشغّل وحده هو من يقرأ القطاعات لاحقاً.
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
     * نوع محتوى الرابط مشتقّاً من امتداده.
     *
     * لهذا غرضٌ عمليّ لا تجميليّ: بناء بيانات وصفية بلا نوع محتوى يجعل
     * المشغّل يعمل على `null` في مسار اختيار الصوت (`FfmpegVideoRenderer`)
     * ويرمي NPE — وهو سقوطٌ يبدو للمستخدم كعطل في الإضافة لا في الترويسة.
     */
    fun rt(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
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

    /**
     * يفحص قائمة مرشّحين **بالتوازي** ويعيد الحيّ منها كمجموعة.
     *
     * ## لماذا التوازي هنا ضرورة لا تحسين
     * الفحص الواحد يكلّف ما بين عشرات الميلي ثانية (ردّ سريع) إلى مهلة
     * كاملة 12 ثانية (مضيف معلّق). والتنفيذ التسلسلي كان يجمع هذه الأزمنة:
     * فصفحة فيها 21 مرشّحاً `get_file` × نسختين (بالتوكن وعارياً) = **42
     * فحصاً متتالياً**. مقيس على مضيف يردّ في نحو 1.7 ثانية (arabxn.sex)
     * يكون الانتظار قبل ظهور أي رابط ≈ **70 ثانية**، وعلى مضيف معلّق أسوأ.
     * وهذا بالحرف «بطء الاستجابة للتشغيل» المُبلَّغ عنه.
     *
     * ## حدّ التزامن ليس تفصيلاً
     * إطلاق 42 طلباً معاً على مضيف واحد يُشبه هجوماً فيفرض حدّاً ويبدأ
     * يردّ 403 — فينقلب التحسين إلى عطل. فالتزامن محدود بـ[parallelism]
     * (ستة افتراضاً: كافٍ لإخفاء زمن الشبكة، وأقلّ بكثير من عتبة أي مضيف
     * فُحص في هذا المستودع).
     *
     * والنتيجة **مجموعة** لا قائمة: الترتيب يبقى للمستدعي فيُصدر بترتيب
     * الصفحة نفسه، فلا يتبدّل ترتيب السيرفرات أمام المستخدم.
     */
    suspend fun aliveSubset(
        urls: List<String>,
        referer: String? = null,
        parallelism: Int = 6
    ): Set<String> {
        if (urls.isEmpty()) return emptySet()
        if (urls.size == 1) return if (alive(urls[0], referer)) setOf(urls[0]) else emptySet()
        val live = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val gate = Semaphore(parallelism)
        coroutineScope {
            urls.map { candidate ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (alive(candidate, referer)) live.add(candidate)
                    }
                }
            }.awaitAll()
        }
        return live
    }

    /**
     * تنظيف رابط `get_file` قبل الفحص والإصدار.
     *
     * ## القاعدة التي كانت تقتل كل الأفلام
     * القياس على أفلامك1 قاطع: **الرابط نفسه بلا استعلام يعيد الصفحة الرئيسية
     * HTML، ومع `?v-acctoken=…` يعيد 206 video/mp4**. فقصُّ الاستعلام كاملاً —
     * كما كان الحلّال يفعل — هو الذي جعل كل إضافة من هذه العائلة تعلن «لا
     * روابط». دروسٌ سابقة كانت على مواقع يردّ فيها الاستعلام 404، والصواب
     * ليس «قصّ الكل» ولا «إبقاء الكل» بل **إسقاط الحشو وحده**.
     *
     * ## ما يُسقَط وما يبقى
     * يُسقَط ما يغيّر المعنى إلى «طلب تحميل» أو «مقاس معيّن» فيردّ الخادم
     * 404: `br`, `embed`, `download`, `download_filename`, `download_orig`,
     * `orig`, `token` (وقع مرتين بهذا الاسم في نفس النطاق بردّ 410).
     * ويبقى كل ما يشبه توكن منع السرقة (`v-acctoken`, `acctoken`, `hash`,
     * `st`, `sig`, `expire`) — وبقاؤه شرط العمل لا تحسين.
     *
     * @return الرابط منظّفاً، أو `null` إن لم يبقَ ما يصلح
     */
    fun sanitize(raw: String): String? {
        var url = unwrapFunction(raw.trim())
        if (url.isBlank()) return null
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) return null

        val drop = setOf(
            "br", "embed", "download", "download_filename", "download_orig",
            "orig", "token", "preview"
        )
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return url

        val kept = q.split('&').filter { part ->
            val key = part.substringBefore('=').lowercase()
            key.isNotBlank() && key !in drop
        }
        return if (kept.isEmpty()) url.substringBefore('?')
        else url.substringBefore('?') + "?" + kept.joinToString("&")
    }

    /** قائمة جودات KVS بصيغة «القيمة = اسم الجودة». */
    fun kvsQuality(label: String?): Int =
        Regex("""(\d{3,4})""").find(label ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** يستخرج معرّف الفيديو الرقمي من صفحة KVS (`video_id: '1971'`). */
    fun videoId(html: String): String? =
        Regex("""video_id\s*[:=]\s*['"]?(\d+)""").find(html)?.groupValues?.get(1)

    /**
     * رابط القرص المباشر على نمط KVS: `/contents/videos/<bucket>/<id>/<id>.mp4`
     * حيث `bucket = floor(id/1000)*1000`.
     *
     * ## تنبيه: هذا المسار لا يعمل على هذه العائلة
     * قياس 2026-10-12 على كل موقع من العائلة: المسار يردّ **404** دائماً،
     * بجودة وبلا جودة، على الأصل وعلى CDN. وقد ذُكر سابقاً أنه الرابط الحيّ
     * على SexArabFun وFreeSexArab — وذلك خطأ فُحص وأُبطل. فالحيّ هو روابط
     * `get_file` المحصودة بتوكنها. تبقى الدالة موجودة كملاذ أخير فقط
     * ([resolveKvsDirect]) لأنه لا ضرر من تجربتها بعد فشل كل شيء.
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
