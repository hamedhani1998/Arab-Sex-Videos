package sharedplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

/**
 * حلّال عائلة **KVS** (Kernel Video Sharing) — وهي عائلة تشغيل موحّدة.
 *
 * ## لماذا ملف واحد لكل هذه المواقع
 * خمسة عشر من إضافات هذا المستودع تعمل على نفس المحرك، والدليل البنيوي
 * واحد في كل صفحة: `kt_player`، ومفاتيح `video_url` / `video_alt_url` /
 * `video_alt_url2` داخل كتلة إعداد، وروابط `/get_file/<n>/<hash>/…`.
 * فبدل تكرار منطقٍ ناقصٍ في خمس عشرة إضافة، يوجد هنا نسخة واحدة مُقاسة.
 *
 * ## الأنماط الأربعة للمفاهيم نفسها (مقيسة 2026-10-12)
 * 1. **وسوم `<video><source>` مطلقة** على CDN — عربدة. لا `flashvars` إطلاقاً.
 * 2. **كتلة إعداد عارية بلا اسم** — Sex6x: `video_url: '…'` داخل سكربت بلا
 *    كلمة `flashvars`، وروابطها تحمل توكن `v-acctoken` فتعمل. من يشترط كلمة
 *    `flashvars` لا يجد شيئاً ويُعلن «لا روابط» — وهذا كان عطل Sex6x بالحرف.
 * 3. **`function/0/<رابط>`** — 3rabxn وSexArabFun وSexAllArab وFreeSexArab:
 *    البادئة علامة توليد، وما بعدها رابط `get_file` يعمل **بشرط** بقاء
 *    التوكن `v-acctoken` في الاستعلام.
 * 4. **لا `get_file` إطلاقاً** — Zebawy وArabNok: الوسائط معلنة في بيانات
 *    Schema.org (`itemprop="contentUrl"`) أو `og:video` وتشير إلى CDN خام.
 *
 * ## تصحيح قياس سابق: مسار القرص المباشر لا يعمل
 * كان مكتوباً هنا أن `/contents/videos/<bucket>/<id>/<id>.mp4` هو الرابط
 * الحيّ على SexArabFun وFreeSexArab. **هذا خطأ**، وأُعيد قياسه على كامل
 * العائلة: المسار يردّ 404 على كل موقع فُحص، والحيّ فعلاً هو روابط
 * `get_file` المحصودة من الصفحة بتوكنها الكامل. ولذلك لا يُصدَّر المسار
 * المباشر إلا كملاذ أخير بعد فشل كل شيء ([Probe.resolveKvsDirect])، ولا
 * يُعوَّل عليه في المسار العادي.
 *
 * ## قاعدتان بنيويتان
 * - **وسوم `<video>` قبل الكتلة**: أوسع تعبير يلتقط الوسم العاري، فلا تنجو
 *   نصوص السكربتات من التفسير الخاطئ.
 * - **لا رابط بلا فحص**: كل مرشّح يمرّ على [Probe] قبل `callback`، فالجودة
 *   الميتة (عربدة 720p) لا تصل المشغّل ولا تنتج خطأ 2004.
 */
object Family {

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * أصل voe.sx — يُرسل مع روابطه بدل الإحالة إلى موقع الفيلم.
     *
     * لاعب voe يخدم من مركز وسائط على نطاقٍ آخر (`cloudwindow-route.com`)،
     * وإرسال مُحيلٍ من موقعٍ ثالث إليه لا يفيده. وقد قيس أن الرابط يخدم
     * متطابقاً بخمس صيغ ترويسات (بلا شيء، Referer، Range، ومعهما Origin)،
     * فالإرسال هنا **صحيح-بلا-ضرر**: لا يتلف شيئاً، ويطابق ما يرسله اللاعب
     * الأصلي حين يعرض الفيديو.
     */
    private const val VoeOrigin = "https://voe.sx"

    /** خيارات المستخدم من الإعدادات، تمرّرها الإضافة إن أرادت. */
    data class Options(
        val probe: Boolean = true,
        val formatTag: Boolean = true
    )

    /**
     * يحلّ رابط التشغيل من صفحة تفاصيل KVS.
     *
     * @param doc      صفحة التفاصيل (أو الـembed؛ البنية نفسها)
     * @param referer  الصفحة المُحيلة
     * @return عدد الروابط التي نجحت فعلاً (0 = لا شيء)
     */
    suspend fun resolve(
        doc: Document,
        referer: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        options: Options = Options()
    ): Int {
        val html = doc.html()
        val emitted = LinkedHashSet<String>()
        var found = 0

        suspend fun emit(raw: String?, label: String? = null, declared: Int = Qualities.Unknown.value) {
            if (raw.isNullOrBlank()) return
            // التنظيف في [Probe.sanitize]: يُسقط الحشو (`br`, `embed`,
            // `download`) **ويُبقي توكن منع السرقة** `v-acctoken` — وهو الذي
            // يجعل الرابط يخدم 206 بدل أن يعيد الصفحة الرئيسية HTML.
            var url = Probe.sanitize(raw) ?: return
            if (!url.startsWith("http")) return
            if (!looksLikeMedia(url)) return
            if (!emitted.add(url)) return
            val isVoe = Voe.isVoe(url)
            // فحص voe بالإحالة الصحيحة وإلا حكم الفحص بالموت على رابط حيّ.
            val alive = if (options.probe) {
                Probe.alive(url, if (isVoe) VoeOrigin else referer)
            } else {
                true
            }
            if (!alive) return
            val quality = if (declared != Qualities.Unknown.value) declared
            else Probe.kvsQuality(label)
            val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
            val shown = if (options.formatTag) FormatTag.tagged("مباشر", url, type) else "مباشر"
            callback(
                newExtractorLink(
                    source = shown,
                    name = shown,
                    url = url,
                    type = type
                ) {
                    // مضيف الإحالة: كل مضيفٍ يُحال إليه من مصدره، لا من موقع
                    // الفيلم. ولاعب voe يُحال إليه من نفسه، لأن مركز الوسائط
                    // عنده بعيد عن الموقع الأمّ.
                    this.referer = if (isVoe) VoeOrigin else referer
                    // نوع المحتوى إلزامي عملياً: الرابط الذي لا تصل معه هذه
                    // الترويسة يبني المشغّل له بيانات وصفية نوعها `null` —
                    // وقد قيس أن `FfmpegVideoRenderer` يرمي NPE عليها عند
                    // اختيار مسار الصوت. فتُشتقّ من الامتداد هنا.
                    this.headers = if (isVoe) mapOf(
                        "Origin" to VoeOrigin,
                        "Content-Type" to Probe.rt(url)
                    ) else {
                        mapOf("Referer" to referer, "Content-Type" to Probe.rt(url))
                    }
                    this.quality = quality
                }
            )
            found++
        }

        // 0) لاعب voe.sx — يُجرَّب أولاً لأنه **ليس KVS**: صفحة الفيلم لا تحمل
        //    رابط وسائط ولا كتلة إعداد، بل إطاراً وحده، وفكّه لا يشبه بقية
        //    الأنماط. مقيس على SexArab69: الرئيسية 28 بطاقة حيّة، وصفحة
        //    التفاصيل فيها إطار voe واحد لا غير — فهو مصدرها الوحيد، وبلا هذا
        //    المسار تُعلن «لا روابط» والوسائط أمامها.
        for (frame in embedFrames(doc, origin)) {
            if (!Voe.isVoe(frame)) continue
            val result = Voe.resolve(frame, referer) ?: continue
            // السيد أولاً ثم mp4: المشغّل يختار أول رابط حين لا يُسأل المستخدم،
            // والسيد هو الذي يفتح قائمة الجودات داخل المشغّل. والجودة 480
            // مصرَّحة لا مستنبطة: [Probe.kvsQuality] لا يجد رقماً في وسم نصّي،
            // ومقاس السيد المقيس 276×480 فيكون وسمه 480 (min لا max — سياق
            // عمودي). فراكتابة `0` هنا كانت ستُظهر «غير معروفة» بلا سبب.
            result.hls?.let { emit(it, "مباشر", 480) }
            result.mp4?.let { emit(it, "مباشر mp4", 480) }
        }

        // 1) وسوم الوسائط أولاً — أوسع تعبير، فقد تكون الصفحة بلا كتلة إعداد
        //    إطلاقاً (عربدة: أربعة وسوم source مطلقة على CDN).
        for (el in doc.select("video source[src], source[src], video[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank()) emit(src, el.attr("title"))
        }

        // 2) كتلة الإعداد — بأي شكل: `video_url: '…'` أو `"video_url": "…"`.
        for (m in Regex(
            """(video_url|video_alt_url|video_alt_url2|video_hd_url|video_url_hd)\s*[:=]\s*['"]([^'"]+)['"]"""
        ).findAll(html)) {
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            // نرفض «قيم المسار»: بعض المواقع تضع رابط الصفحة نفسها مكان الرابط.
            if (value.startsWith("/videos/") || value.startsWith("/video/")) continue
            val label = Regex("""${Regex.escape(key)}_text\s*[:=]\s*['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
            emit(value, label)
        }

        // 2ب) حصاد كل روابط `get_file` الطازجة في الصفحة.
        //
        // هذا أهمّ سطر في الحلّال، وسببه مقيس: **قيمة `video_url` في كتلة
        // الإعداد تكون منتهية الصلاحية** (الهاش يتبدّل كل جلب، فالنسخة
        // المكتوبة في HTML قديمة وتردّ 404/410). وفي الصفحة نفسها روابط
        // `get_file` أحدث — في `event_reporting2` وفي روابط التنزيل — وهي
        // التي تردّ 302 إلى رابط موقّت ثم 206. فبدل الوثوق بحقلٍ واحد،
        // نفحص كل ما ورد في الصفحة ونُصدر ما ينجح منها فقط.
        //
        // ## الفلترة باسم الفيديو كانت تسقط الصفحات الحيَّة
        // المسار القانوني لـ KVS هو `/get_file/<n>/<هاش>/<bucket>/<id>/<id>.mp4`
        // فاسم الملف يبدأ برقم الفيديو، ولهذا رُشّح به. لكن المواقع المقيسة
        // تكتب قيمتين لمعرّف الفيديو في الصفحة نفسها: `video_id` لمعرّف
        // داخلي، والرقم الذي في مسار الصفحة لمعرّف آخر — فحصٌ كامل هذه
        // السلسلة (1–3rabxn، ArabSexFun، FreeSexArab، SexAllArab، ArbNaar)
        // أظهر `video_id` يخالف اسم ملف الوسائط في **كل** المواقع، أي أن
        // المرشّح لم يُبقِ شيئاً وأعلنت الإضافات «لا روابط» وفيها وسائط
        // تخدم 206. فصار المرشّح يقبل أحد الأمرين: اسم ملف يبدأ بمعرّف
        // الفيديو، **أو** رابطاً يقع داخل مسار ذاك المعرّف (`/3000/3329/`).
        val vid = Probe.videoId(html)
        if (vid != null) {
            // إبقاء الاستعلام (`[^"'\s<>]+` لا `*?\.mp4/?`) شرطٌ لا تحسين:
            // التوكن `?v-acctoken=…` يأتي بعد الامتداد، ونمطٌ يتوقف عند
            // `.mp4/` يحصده بلا توكن فيردّ الخادم الصفحة الرئيسية HTML.
            //
            // وكل رابط يُجرّب **بتوكنه** ثم **عارياً**: قيس على ArbNaar أن
            // 400 يردّ على الاستعلام نفسه و206 على الرابط عارياً (نسخة
            // الكوكي/التوكن قديمة، والعارية هي التي تخدم).
            val anyGetFile = Regex("""https?://[^"'\s<>]*/get_file/[^"'\s<>]+""")
            for (m in anyGetFile.findAll(html)) {
                val u = m.value
                val path = u.substringBefore('?').trimEnd('/')
                val fname = path.substringAfterLast('/')
                if (fname.contains("preview", true)) continue
                val byName = fname.startsWith("$vid.") || fname.startsWith("${vid}_")
                val byPath = path.contains("/$vid/")
                if (!byName && !byPath) continue
                emit(u)
                val bare = u.substringBefore('?')
                if (bare != u) emit(bare)
            }
        }

        // 2ج) الوسائط المعلنة في بيانات Schema.org — `itemprop="contentUrl"`
        //     و`og:video`.
        //
        // لماذا مسار مستقل: مواقع هذه العائلة هجرت KVS في الصفحة إلى
        // `<video>` الذي يحيل إلى CDN عارٍ. مقيس على Zebawy
        // (`meta itemprop="contentUrl"` → `cdn.zabewy.com/18/….mp4`) وعلى
        // ArabNok (`media.arabnok.com/videos/….mp4`) — والاثنان يخدمان
        // 206 video/mp4. ولا `get_file` في الصفحة إطلاقاً، فبلا هذا المسار
        // تظل الإضافتان تقولان «لا روابط» والوسائط أمامهما.
        for (m in Regex(
            """itemprop\s*=\s*['"]contentUrl['"][^>]*content\s*=\s*['"]([^'"]+)['"]""" +
                """|content\s*=\s*['"]([^'"]+)['"][^>]*itemprop\s*=\s*['"]contentUrl['"]"""
        ).findAll(html)) {
            emit(m.groupValues[1].ifBlank { m.groupValues[2] })
        }
        val ogVideo = Regex("""og:video(?::(?:secure_url|url))?['"][^>]*content\s*=\s*['"]([^'"]+)['"]""")
        for (m in ogVideo.findAll(html)) emit(m.groupValues[1])

        // 3) صفحة الـembed — بعض المواقع لا تضع كتلة الإعداد إلا فيها
        //    (`/embed/<id>`)، وتكون الصفحة الأم حاوية فقط. وبعضها لاعب وسيط
        //    لا يحمل رابطاً بل يجلب مصادره من نداء JSON لاحق (المسار 3ب).
        if (found == 0) {
            for (embed in embedFrames(doc, origin)) {
                val ed = fetch(embed, referer) ?: continue
                // نفس المسارات 1 و2 على صفحة الـembed (بلا تعمّق مرتد).
                for (el in ed.select("video source[src], source[src], video[src]")) {
                    emit(el.attr("src").ifBlank { el.attr("data-src") }, el.attr("title"))
                }
                for (m in Regex(
                    """(video_url|video_alt_url|video_alt_url2|video_hd_url)\s*[:=]\s*['"]([^'"]+)['"]"""
                ).findAll(ed.html())) {
                    val key = m.groupValues[1]
                    val label = Regex("""${Regex.escape(key)}_text\s*[:=]\s*['"]([^'"]+)['"]""")
                        .find(ed.html())?.groupValues?.get(1)
                    emit(m.groupValues[2], label)
                }
                // 3ب) اللاعب الوسيط الذي يجلب مصادره من نداء JSON لاحق.
                //
                // مقيس على Sexalarab11: صفحة التفاصيل حاوية واحدة فيها
                // `<iframe src="https://www.player01.cfd/hdplay/<base64>.html">`،
                // وصفحة اللاعب نفسها لا تحمل رابط وسائط واحداً — تحمل:
                //   `$.ajax({ url: baseURL + 'ajax_sources.php', data: {'vid': hash} })`
                // والمصادر تصل POST كـJSON فيه `sources[].file` رابط m3u8 حيّ
                // (وأجزاؤه MPEG-TS موسومة `image/jpeg` في الترويسة — انظر
                // [playerEndpointMedia] و[Probe.alive]). فمن يقرأ HTML فقط لا
                // يرى شيئاً ويُعلن «لا روابط» — وهذا كان حالها بالحرف.
                for (u in playerEndpointMedia(embed, ed.html())) emit(u)
            }
        }

        // 4) أخيراً: مسار القرص المباشر على نمط KVS — لا يكلّف إلا حين يفشل
        //    كل ما سبق، فالفحص يجرّب عدة مسارات لكل واحد طلب.
        if (found == 0 && options.probe) {
            Probe.videoId(html)?.let { id ->
                Probe.resolveKvsDirect(origin, id, referer)?.let { emit(it) }
            }
        }

        // لا إعادة إصدار لما رفضه الفحص: قاعدة «لا رابط بلا فحص» تمنع ذلك،
        // وهو بالضبط ما كان يجعل الخطأ 2004 يظهر.
        return found
    }

    /**
     * هل يبدو الرابط وسائط فعلية؟
     *
     * ## الشرطة المائلة النهائية ليست تفصيلاً
     * الشكل القانوني لروابط KVS هو `…/2106.mp4/` **بشرطة أخيرة**، وبها يخدم
     * الخادم 206 بلاها 404. فاختبارٌ يقول `endsWith(".mp4")` يرفضها جميعاً،
     * ويُعلن «لا روابط» على صفحةٍ فيها رابط حيّ فعلاً — وهذا بالضبط ما كان
     * يحدث في SexArabFun وSexAllArab وFreeSexArab. فنُجرّد الشرطة قبل الفحص.
     *
     * والاستعلام يُقصّ للسبب نفسه: المواقع تُلحق توكناتها به
     * (`?v-acctoken=…`) والنهاية الحقيقية قبله.
     */
    private fun looksLikeMedia(u: String): Boolean {
        val l = u.substringBefore('?').trimEnd('/').lowercase()
        if (l.endsWith(".jpg") || l.endsWith(".png") || l.endsWith(".webp") ||
            l.endsWith(".jpeg") || l.endsWith(".gif")
        ) return false
        return l.endsWith(".mp4") || l.endsWith(".m4v") || l.endsWith(".m3u8") ||
            l.endsWith(".mpd") || l.endsWith(".webm") || l.endsWith(".mkv") ||
            l.endsWith(".ts")
    }

    /**
     * كل أطر الـembed في الصفحة، بعناوينها المطلقة.
     *
     * ## سمات التحويل المتأخر ليست تفصيلاً — ولا تُعدّ ولا تُسمّى
     * مقيس على SexArab69: الصفحة تكتب
     * `<iframe data-lazyloaded="1" src="about:blank" data-litespeed-src="https://voe.sx/e/…">`.
     * فمن يقرأ `src` وحده يحصل على `about:blank` — ليست رابطاً، فأول جلب يفشل
     * وتبدو الصفحة بلا وسائط مع أن الإطار أمامه.
     *
     * ## الدرس: لا تُعدّ أسماء السمات
     * النسخة الأولى عدّت `data-src` و`data-lazy-src` وحدهما — وهما من إضافات
     * تحويل شهيرة — فسقطت `data-litespeed-src` (إضافة LiteSpeed Cache) وبقيت
     * SexArab69 بلا إطار. وكل إضافةٍ تحويلٍ جديدة تخترع اسماً جديداً، فلا
     * ينتهي العدّ. الصواب أن يُسأل السؤال المعاكس: **أيّ سمةٍ في هذا العنصر
     * تحمل رابطاً؟** فنمسح سمات الإطار كلها ونأخذ أول ما يبدأ بـ`http` أو `//`
     * أو `/`، ويُطرح `about:blank` و`data-lazyloaded="1"` تلقائياً لأنهما لا
     * يبدآن كذلك. ويعمل هذا مع أي اسم سمة لم يُخترع بعد.
     */
    private fun embedFrames(doc: Document, origin: String): List<String> {
        val out = LinkedHashSet<String>()
        for (el in doc.select("iframe, frame, embed, meta[itemprop=embedURL]")) {
            val candidates: List<String> = if (el.tagName().equals("meta", true)) {
                listOf(el.attr("content"))
            } else {
                el.attributes().map { it.value }
            }
            val raw = candidates.map { it.trim() }.firstOrNull {
                it.startsWith("http") || it.startsWith("//") ||
                    (it.startsWith("/") && it.length > 1 && it[1] != '/')
            } ?: continue
            val abs = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> origin + raw
                else -> raw
            }
            if (abs.startsWith("http")) out.add(abs)
        }
        return out.toList()
    }

    /**
     * يستخرج الوسائط من **لاعب وسيط** يجلب مصادره بنداء لاحق.
     *
     * ## النمط المقيس (Sexalarab11، 2026-10-12)
     * صفحة اللاعب لا تحمل رابط وسائط واحداً، بل نداءً هكذا:
     * ```
     * var hash = 'c2V4YWxhcmFifEpBbDlIbHN0aWdBWkZLbQ==';
     * $.ajax({ url: baseURL + 'ajax_sources.php', data: {'vid': hash} })
     * ```
     * ويُجاب عنه POST بجسم JSON فيه `sources[].file` رابط m3u8 حيّ. فمن يقرأ
     * HTML فقط لا يرى شيئاً ويُعلن «لا روابط» — وهذا كان حالها بالحرف.
     *
     * ## ثلاث دقائق كلّها مقيسة
     * - **الترويسة `Referer` شرط**: النداء بلا مُحيل يردّ **صفر بايت** بنفس
     *   رمز 200، فيبدو كأن اللاعب أعلن لا شيء.
     * - **الجسم `application/x-www-form-urlencoded`** لا JSON: الإرسال بجسم
     *   JSON يردّ صفراً أيضاً، والصيغة النموذجية هي التي تردّ المصادر.
     * - **قطاعات الـHLS موسومة `image/jpeg`** والبايتات حقيقية (توقيع
     *   MPEG-TS `0x47`)، فالفحص وحده بنوع المحتوى كان يُسقط بثاً سليماً.
     *
     * @param playerUrl عنوان صفحة اللاعب (أساس الروابط النسبية وحقل Referer)
     * @param html      نصّها
     */
    private suspend fun playerEndpointMedia(playerUrl: String, html: String): List<String> {
        // لا معنى للمحاولة إن لم تكن الصفحة لاعباً ينادي مصادره.
        if (!Regex("""ajax|fetch\(|getJSON""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            return emptyList()
        }
        // 1) عنوان النداء — من نصّ النداء نفسه لا من تخمين.
        val endpoint = Regex(
            """["']([^"']*(?:ajax|sources?|get_?source|playlist)[^"']*\.php[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return emptyList()

        // 2) معرّف النداء: اسم الحقل من `data:{'vid': hash}`، وقيمته من
        //    متغيّر JS يحمل نصاً — وهو الشكل المرصود.
        val postKey = Regex("""data\s*:\s*\{\s*['"]?(\w+)['"]?\s*:""")
            .find(html)?.groupValues?.get(1) ?: "vid"
        // قيمة النداء: إمّا اسم متغيّر (`{'vid': hash}`) وإمّا نصّ صريح.
        val valueRef = Regex("""data\s*:\s*\{\s*['"]?\w+['"]?\s*:\s*([\w'"]+)""")
            .find(html)?.groupValues?.get(1)?.trim('\'', '"') ?: return emptyList()
        val value = Regex("""(?:var\s+|let\s+|const\s+)?${Regex.escape(valueRef)}\s*=\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: valueRef

        val json = try {
            app.post(
                absoluteUrl(playerUrl, endpoint),
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to playerUrl,
                    "Accept" to "application/json,*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf(postKey to value)
            ).text
        } catch (_: Exception) { "" }
        if (json.isBlank()) return emptyList()
        return Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(json).map { it.groupValues[1] }.toList()
    }

    /**
     * يبني عنوان النداء المطلق بالنسبة إلى **مجلّد** صفحة اللاعب لا جذر النطاق.
     *
     * ## لماذا المجلّد وليس النطاق
     * مقيس على Sexalarab11: الصفحة تُعرِّف `baseURL = 'https://www.player01.cfd/hdplay/'`
     * ثم `url: baseURL + 'ajax_sources.php'`. فمن ألحق الاسم بجذر النطاق طلب
     * `…/ajax_sources.php` — وهو مسار غير موجود — ولم يفهم لماذا لا تصل
     * المصادر. والقاعدة نفسها في HTTP: النسبي يُحَلّ على مجلّد المستند.
     */
    private fun absoluteUrl(playerUrl: String, endpoint: String): String = when {
        endpoint.startsWith("http") -> endpoint
        endpoint.startsWith("//") -> "https:$endpoint"
        else -> {
            val scheme = playerUrl.substringBefore("://") + "://"
            val host = playerUrl.substringAfter("://").substringBefore('/')
            if (endpoint.startsWith("/")) {
                "$scheme$host$endpoint"
            } else {
                val dir = playerUrl.substringBefore('?').substringBefore('#')
                    .trimEnd('/').substringBeforeLast('/', playerUrl)
                "$dir/${endpoint.removePrefix("./")}"
            }
        }
    }

    /** جلب صفحة بنفس ترويسات المشغّل — تُستعمل لجلب الـembed عند الحاجة. */
    suspend fun fetch(url: String, referer: String): Document? = try {
        app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to UA, "Accept-Language" to "ar,en;q=0.8")
        ).document
    } catch (_: Exception) {
        null
    }
}
