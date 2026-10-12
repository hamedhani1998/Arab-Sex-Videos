package sharedplay

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * وسم صيغة الوسائط في اسم سيرفر التشغيل.
 *
 * ## لماذا اسم السيرفر وحده
 * المشغّل يعرض `ExtractorLink.name` فقط في قائمة «سيرفرات التشغيل»؛ أما
 * `type` و`source` فلا يُطبعان. فوسمٌ في الاسم هو الطريق الوحيد ليعرف
 * المستخدم **قبل الضغط** هل الرابط MP4 أم M3U8 أم DASH.
 *
 * ## الامتداد أولاً لا `type`
 * عشرات المواقع تضبط `ExtractorLinkType.M3U8` على رابط `.mp4` والعكس، لأن
 * `type` يعني «كيف يُفتح» لا «ما هو الملف». لذلك الامتداد هو الحاكم، و`type`
 * يُستخدم فقط حين يخلو الرابط من امتداد مفهوم.
 */
object FormatTag {

    /** وسم قصير بلا أقواس، مثل `MP4` أو `M3U8` — أو null إن استحال التمييز. */
    fun of(url: String, type: ExtractorLinkType? = null): String? {
        val u = url.substringBefore('?').substringBefore('#').lowercase()
        // نبحث من آخر المسار: امتداد الملف هو آخر ما قبل الفاصل.
        val ext = u.substringAfterLast('.', "")
        val byExt = when (ext) {
            "mp4", "m4v" -> "MP4"
            "m3u8" -> "M3U8"          // قائمة بثّ HLS
            "mpd" -> "DASH"
            "ts" -> "TS"
            "mkv" -> "MKV"
            "webm" -> "WEBM"
            "flv" -> "FLV"
            "avi" -> "AVI"
            "mov" -> "MOV"
            else -> null
        }
        if (byExt != null) return byExt

        // بلا امتداد: ننزل إلى نوع الرابط كما يعرفه المشغّل. الوسم هنا «HLS»
        // لا «M3U8» ليميّز الحالة: ملفٌ اسمه ينتهي بـ`.m3u8` يُعرض `[M3U8]`،
        // ورابطُ بثٍّ عرّفه المشغّل M3U8 بلا امتداد يُعرض `[HLS]` — فالكلمتان
        // اللتان يطلبهما المستخدم تظهران حيث تصدق كلٌّ منهما.
        return when (type) {
            ExtractorLinkType.M3U8 -> "HLS"
            ExtractorLinkType.DASH -> "DASH"
            ExtractorLinkType.TORRENT -> "TORRENT"
            ExtractorLinkType.MAGNET -> "MAGNET"
            ExtractorLinkType.VIDEO -> null   // «فيديو» لا تفيد المستخدم
            else -> null
        }
    }

    /**
     * اسم جاهز للعرض: `«جودة 480 · [MP4]»`، أو الاسم كما هو إن تعذّر الوسم.
     * تُستدعى قبل البناء لأن `newExtractorLink` تبني الاسم مرة واحدة.
     */
    fun tagged(name: String, url: String, type: ExtractorLinkType? = null): String {
        val tag = of(url, type) ?: return name
        // لا نكرّر الوسم إن كان الاسم يحمله أصلاً (يحدث عند إعادة بناء رابط).
        if (name.contains("[$tag]")) return name
        return "$name · [$tag]"
    }
}

/**
 * مُصدِر مشترك يمنح أي محلّل — بنى على `Family` أو على منطقه الخاص — الفحصَ
 * والوسم بلا تكرار.
 *
 * ## لماذا دالة لا كائن
 * `newExtractorLink` دالة `suspend`، فالمُصدِر لا بدّ أن يكون داخل `suspend`؛
 * وجعلُه دالةً عليا `suspend` أخفّ من كائن يحمل حالة.
 *
 * ## القاعدة الواحدة
 * **لا رابط بلا فحص.** إن كان الفحص مفعّلاً والرابط ميتاً لا يُستدعى `callback`
 * إطلاقاً — وهذا الذي يمنع خطأ 2004 من الوصول إلى المستخدم.
 *
 * @param name  اسم السيرفر كما بناه المزوّد (سيُوسَم بالصيغة)
 * @param probe فحص الحياة قبل الإصدار
 * @param tag   إضافة `[MP4]`/`[M3U8]`… إلى الاسم
 * @return true إن أُصدر الرابط فعلاً
 */
suspend fun emitChecked(
    url: String,
    name: String,
    referer: String?,
    quality: Int,
    callback: (ExtractorLink) -> Unit,
    probe: Boolean = true,
    tag: Boolean = true
): Boolean {
    if (url.isBlank() || !url.startsWith("http")) return false
    val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8
    else ExtractorLinkType.VIDEO
    if (probe && !Probe.alive(url, referer)) return false
    val shown = if (tag) FormatTag.tagged(name, url, type) else name
    callback(
        newExtractorLink(source = shown, name = shown, url = url, type = type) {
            this.quality = quality
            if (!referer.isNullOrBlank()) this.referer = referer
            // نوع المحتوى إلزامي عملياً: الرابط الذي لا تصل معه هذه الترويسة
            // يبني المشغّل له بيانات وصفية نوعها `null`، ويُقاس أن
            // `FfmpegVideoRenderer` يرمي NPE عليها في مسار اختيار الصوت —
            // وهو سقوطٌ يبدو عطلاً في الإضافة لا في الترويسة. فتُشتقّ من
            // الامتداد هنا كما تفعل [Family.deliver] بالحرف.
            this.headers = if (referer.isNullOrBlank()) {
                mapOf("Content-Type" to Probe.rt(url))
            } else {
                mapOf("Referer" to referer, "Content-Type" to Probe.rt(url))
            }
        }
    )
    return true
}
