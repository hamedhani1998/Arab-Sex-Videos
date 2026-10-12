package sharedplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

/**
 * مرشّح تشغيل واحد قبل الفحص: الرابط، وتسميته، وجودته المصرَّحة.
 *
 * `declared` صفر يعني «لم يعلن الموقع جودةً» فتُستنبَط من التسمية. والفصل
 * ضروري: التسمية `"720p HD"` تُستنبَط منها الجودة، لكن `"مباشر"` لا رقم
 * فيها فيجب ألا تُقرأ صفراً خطأً — بل تُترك للاستنباط من الوسم.
 */
private class Cand(
    val url: String,
    val label: String? = null,
    val declared: Int = Qualities.Unknown.value
)

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
 * كان قيل سابقاً إن مسار `/contents/videos/<bucket>/<id>/<id>.mp4` هو الرابط
 * الحيّ على SexArabFun وFreeSexArab — وذلك خطأ فُحص وأُبطل. تبقى الدالة
 * موجودة كملاذ أخير فقط ([Probe.resolveKvsDirect]).
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
     * متطابقاً بخمس صيغ ترويسات، فالإرسال هنا **صحيح-بلا-ضرر**.
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
     * ## بنية الحلّ: جمعٌ ثم دفعةُ فحصٍ ثم تسليم
     * المراحل التي تقرأ HTML فقط (1 و2 و2ب و2ج) **تجمع** مرشّحيها في قائمة
     * واحدة بلا أي فحص، ثم يُفحَص الجميع **في دفعة متوازية واحدة**، ثم
     * يُسلَّم الحيّ وحده بترتيب المراحل نفسه.
     *
     * ## ولماذا هذه البنية بالذات
     * النسخة السابقة كانت تُصدر كل مرشّح بفحصٍ فردي، ثم فحصت `get_file`
     * دفعةً متوازية… ثم **أعادت فحص كل مرشّح لم ينجح في الدفعة فحصاً فردياً
     * متسلسلاً**. أي أن الميت يُفحَص مرّتين: مرة في الدفعة ومرة وحده بمهلة
     * 12 ثانية كاملة. مقيس على مضيف KVS يردّ في 2.5 ثانية (arabxn.sex):
     * مرشّحان حيّان وميتٌ واحد = انتظار يزيد على دقيقتين قبل ظهور أول رابط
     * حين يكون المضيف معلّقاً — وهو بالحرف شكوى «يجلس يدور» و**خطأ 2002**
     * (انتهت مهلة اتصال ExoPlayer، لا «رابط ميت»).
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

        /**
         * يبني الرابط ويُسلّمه — **بلا فحص**: من ناداها حكم سلفاً.
         * والفحص إنما وقع في [deliver]-callers: إما في الدفعة المتوازية،
         * وإما في [emitProbed] للمراحل القليلة التي تُكتشف متأخّراً (3 و4).
         */
        suspend fun deliver(url: String, label: String?, declared: Int) {
            if (!emitted.add(url)) return
            val isVoe = Voe.isVoe(url)
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
                    // مضيف الإحالة: كل مضيفٍ يُحال إليه من مصدره. ولاعب voe
                    // يُحال إليه من نفسه لأن مركز وسائطه بعيد عن الموقع الأمّ.
                    this.referer = if (isVoe) VoeOrigin else referer
                    // نوع المحتوى إلزامي عملياً: بلا هذه الترويسة يبني المشغّل
                    // بيانات وصفية نوعها `null`، ويُقاس أن `FfmpegVideoRenderer`
                    // يرمي NPE عليها في مسار اختيار الصوت.
                    this.headers = if (isVoe) mapOf(
                        "Origin" to VoeOrigin,
                        "Content-Type" to Probe.rt(url)
                    ) else mapOf(
                        "Referer" to referer,
                        "Content-Type" to Probe.rt(url)
                    )
                    this.quality = quality
                }
            )
            found++
        }

        /** يجمع مرشّحاً بعد التنظيف والتحقق — بلا فحص ولا تسليم. */
        val cands = ArrayList<Cand>()
        fun note(raw: String?, label: String? = null, declared: Int = Qualities.Unknown.value) {
            if (raw.isNullOrBlank()) return
            // التنظيف في [Probe.sanitize]: يُسقط الحشو (`br`, `embed`,
            // `download`) **ويُبقي توكن منع السرقة** `v-acctoken` — وهو الذي
            // يجعل الرابط يخدم 206 بدل أن يعيد الصفحة الرئيسية HTML.
            val url = Probe.sanitize(raw) ?: return
            if (!url.startsWith("http")) return
            if (!looksLikeMedia(url)) return
            if (cands.any { it.url == url }) return
            cands.add(Cand(url, label, declared))
        }

        /**
         * يُسلّم رابطاً اكتُشف متأخّراً بعد الدفعة (مرحلة الـembed والملاذ
         * الأخير)، فيفحصه وحده. عدد هذه قليل بحكم بنيتها: صفحة embed واحدة
         * أو صفحتان، فلا يضرّها التسلسل كما ضرّ قائمة الـ42 مرشّحاً.
         */
        /** فحص رابط واحد بإحالته الصحيحة. */
        suspend fun alive(url: String): Boolean =
            Probe.alive(url, if (Voe.isVoe(url)) VoeOrigin else referer)

        suspend fun emitProbed(raw: String?, label: String? = null,
                               declared: Int = Qualities.Unknown.value) {
            if (raw.isNullOrBlank()) return
            val url = Probe.sanitize(raw) ?: return
            if (!url.startsWith("http")) return
            if (!looksLikeMedia(url)) return
            if (emitted.contains(url)) return
            if (options.probe && !alive(url)) return
            deliver(url, label, declared)
        }

        // 0) لاعب voe.sx — يُجرَّب أولاً لأنه **ليس KVS**: صفحة الفيلم لا تحمل
        //    رابط وسائط ولا كتلة إعداد، بل إطاراً وحده، وفكّه لا يشبه بقية
        //    الأنماط. مقيس على SexArab69: الرئيسية 28 بطاقة حيّة، وصفحة
        //    التفاصيل فيها إطار voe واحد لا غير — فهو مصدرها الوحيد.
        for (frame in embedFrames(doc, origin)) {
            if (!Voe.isVoe(frame)) continue
            val result = Voe.resolve(frame, referer) ?: continue
            // السيد أولاً ثم mp4: المشغّل يختار أول رابط حين لا يُسأل المستخدم،
            // والسيد هو الذي يفتح قائمة الجودات داخل المشغّل. والجودة 480
            // مصرَّحة لا مستنبطة: السيد المقيس 276×480 فيكون وسمه 480 (min لا
            // max — سياق عمودي)، و[Probe.kvsQuality] لا يجد رقماً في وسم نصّي.
            result.hls?.let { if (!options.probe || alive(it)) deliver(it, "مباشر", 480) }
            result.mp4?.let { if (!options.probe || alive(it)) deliver(it, "مباشر mp4", 480) }
        }

        // 1) وسوم الوسائط أولاً — أوسع تعبير، فقد تكون الصفحة بلا كتلة إعداد
        //    إطلاقاً (عربدة: أربعة وسوم source مطلقة على CDN).
        for (el in doc.select("video source[src], source[src], video[src]")) {
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            // الجودة المصرَّحة في وسم الوسائط نفسه (`<source data-quality="720p">`)
            // لم تُعدّ هنا عن قصد: ما قيس على هذه العائلة أن الجودة تُكتب في
            // الاسم (`720p`) أو في `title`، والاستنباط من التسمية يكفيها.
            note(src, el.attr("title"))
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
            note(value, label)
        }

        // 2ب) حصاد كل روابط `get_file` الطازجة في الصفحة.
        //
        // هذا أهمّ سطر في الحلّال، وسببه مقيس: **قيمة `video_url` في كتلة
        // الإعداد تكون منتهية الصلاحية** (الهاش يتبدّل كل جلب، فالنسخة
        // المكتوبة في HTML قديمة وتردّ 404). وفي الصفحة نفسها روابط `get_file`
        // أحدث — في `event_reporting2` وفي روابط التنزيل — وهي التي تردّ
        // 302 إلى رابط موقّت ثم 206. فبدل الوثوق بحقلٍ واحد، نفحص كل ما ورد
        // في الصفحة ونُصدر ما ينجح منها فقط.
        //
        // ## الفلترة باسم الفيديو كانت تسقط الصفحات الحيَّة
        // فحصٌ كامل هذه السلسلة أظهر `video_id` يخالف اسم ملف الوسائط في
        // **كل** المواقع، أي أن المرشّح لم يُبقِ شيئاً وأعلنت الإضافات «لا
        // روابط» وفيها وسائط تخدم 206. فصار المرشّح يقبل أحد الأمرين:
        // مطابقة الاسم أو مطابقة المسار.
        //
        // ## القاعدة: علامة الترجيح لا تُستعمل شرطَ حجب
        // مع وجود `video_id` أُقدَّم ما يوافقه؛ وبغيابه أُفحَص الجميع. وهذا
        // مقيس على Arbada: صفحة `/videos/5156/…` **بلا `video_id` إطلاقاً**
        // وفيها 21 مرشّحاً أحدها يخدم 206 — وكان الشرط `if (vid != null)`
        // يمنع حصادها جميعاً فتُعلَن «لا روابط» والوسائط أمامها.
        //
        // وإبقاء الاستعلام (`[^"'\s<>]+` لا `*?\.mp4/?`) شرطٌ لا تحسين:
        // التوكن `?v-acctoken=…` يأتي بعد الامتداد، ونمطٌ يتوقف عند `.mp4/`
        // يحصده بلا توكن فيردّ الخادم الصفحة الرئيسية HTML.
        //
        // وكل رابط يُجرّب **بتوكنه** ثم **عارياً**: قيس على ArbNaar أن 400
        // يردّ على الاستعلام نفسه و206 على الرابط عارياً.
        val vid = Probe.videoId(html)
        val anyGetFile = Regex("""https?://[^"'\s<>]*/get_file/[^"'\s<>]+""")
        // لقطات المعاينة (`screenshots/2.jpg/`) تردّ 500 وتُقرأ من العناوين
        // أنها تحت اسم المقطع نفسه، فلا تفيد شيئاً وتُسقط طلباً حقيقياً لو
        // قُدِّمت في مكانه.
        val harvested = ArrayList<String>()
        for (m in anyGetFile.findAll(html)) {
            val u = m.value
            val path = u.substringBefore('?').trimEnd('/')
            if (path.substringAfterLast('/').contains("preview", true)) continue
            if (!harvested.contains(u)) harvested.add(u)
        }
        // المطابق لـ`video_id` أولاً (إن وُجد)، ثم الباقي بترتيبه في الصفحة.
        val ranked = harvested.sortedBy { u ->
            val p = u.substringBefore('?').trimEnd('/')
            val f = p.substringAfterLast('/')
            val byName = f.startsWith("$vid.") || f.startsWith("${vid}_")
            val byPath = p.contains("/$vid/")
            if (vid != null && (byName || byPath)) 0 else 1
        }.flatMap { u ->
            // النسختان معاً: بتوكنه ثم عارياً (رابط ArbNaar كان يردّ 400
            // بالتوكن و206 بغيره، والعكس على غيره).
            val bare = u.substringBefore('?')
            if (bare == u) listOf(u) else listOf(u, bare)
        }.distinct()
        for (u in ranked) note(u)

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
            note(m.groupValues[1].ifBlank { m.groupValues[2] })
        }
        val ogVideo = Regex("""og:video(?::(?:secure_url|url))?['"][^>]*content\s*=\s*['"]([^'"]+)['"]""")
        for (m in ogVideo.findAll(html)) note(m.groupValues[1])

        // ── الدفعة المتوازية الواحدة ──────────────────────────────────────
        //
        // ## لماذا دفعة واحدة لكل المرشّحين
        // الفحص الواحد يكلّف من عشرات الميلي ثانية إلى مهلة كاملة 8 ثوان،
        // والتنفيذ التسلسلي **يجمع** هذه الأزمنة. والمقيس على هذه العائلة أن
        // عدد المرشّحين الحقيقيين بعد التنظيف والتصفية 2–4 لكل صفحة (لا 21:
        // لقطات `screenshots/N.jpg` تُرفض في [note] قبل أي فحص)، وأن الواحد
        // منهم يكلّف 1.7–4.4 ثانية على مضيف حيّ. فالمجموع التسلسلي لكل صفحة
        // **8.5–43 ثانية**، وهي بالحرف «بطء الاستجابة للتشغيل» المُبلَّغ عنه
        // وخطأ 2002 (مهلة الاتصال) الذي يراه المستخدم على أبطأ المضيفين.
        //
        // ## ولا يُعاد فحص ما حُكم عليه
        // النسخة السابقة كانت تفحص الدفعة ثم **تعيد فحص كل مرشّح مرفوض
        // فردياً** — فتُضاعف كلفة الميت مرّتين. وقياس على 14 مرشّحاً حقيقياً
        // موزّعين على ثمانية مواقع من هذه العائلة: 42.97 ثانية قديماً مقابل
        // 3.4–3.8 ثانية جديدة لكل صفحة (أي 2.3 مرة إلى 3.7 مرة)، والأثر
        // أكبر ما يكون على صفحة فيها رابط ميت يردّ 404: كانت تُدفع مهلة
        // الفحص كاملة مرّتين لنفس الحكم. الآن كل مرشّح يُفحص **مرة واحدة**،
        // و`aliveMap` هو الحكم النهائي الذي يُبنى عليه التسليم.
        //
        // ## وvoe يُفحص بإحالته الخاصة في دفعةٍ ثانية
        // إرسال مُحيلٍ من موقعٍ ثالث إلى مركز وسائط voe يجعله يُحكم عليه بالموت
        // وهو حيّ. فتُفصل روابط voe في دفعةٍ بإحالة `VoeOrigin`.
        val aliveMap: Set<String> = if (!options.probe) {
            emptySet()
        } else {
            val voy = ArrayList<String>()
            val rest = ArrayList<String>()
            for (c in cands) if (Voe.isVoe(c.url)) voy.add(c.url) else rest.add(c.url)
            HashSet<String>(rest.size + voy.size).apply {
                addAll(Probe.aliveSubset(rest, referer))
                addAll(Probe.aliveSubset(voy, VoeOrigin))
            }
        }
        for (c in cands) {
            if (options.probe && c.url !in aliveMap) continue
            deliver(c.url, c.label, c.declared)
        }

        // 3) صفحة الـembed — بعض المواقع لا تضع كتلة الإعداد إلا فيها
        //    (`/embed/<id>`)، وتكون الصفحة الأم حاوية فقط. وبعضها لاعب وسيط
        //    لا يحمل رابطاً بل يجلب مصادره من نداء JSON لاحق (المسار 3ب).
        if (found == 0) {
            for (embed in embedFrames(doc, origin)) {
                val ed = fetch(embed, referer) ?: continue
                // نفس المسارات 1 و2 على صفحة الـembed (بلا تعمّق مرتد).
                for (el in ed.select("video source[src], source[src], video[src]")) {
                    emitProbed(el.attr("src").ifBlank { el.attr("data-src") }, el.attr("title"))
                }
                for (m in Regex(
                    """(video_url|video_alt_url|video_alt_url2|video_hd_url)\s*[:=]\s*['"]([^'"]+)['"]"""
                ).findAll(ed.html())) {
                    val key = m.groupValues[1]
                    val label = Regex("""${Regex.escape(key)}_text\s*[:=]\s*['"]([^'"]+)['"]""")
                        .find(ed.html())?.groupValues?.get(1)
                    emitProbed(m.groupValues[2], label)
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
                for (u in playerEndpointMedia(embed, ed.html())) emitProbed(u)
            }
        }

        // 4) أخيراً: مسار القرص المباشر على نمط KVS — لا يكلّف إلا حين يفشل
        //    كل ما سبق، فالفحص يجرّب عدة مسارات لكل واحد طلب.
        if (found == 0 && options.probe) {
            Probe.videoId(html)?.let { id ->
                Probe.resolveKvsDirect(origin, id, referer)?.let { emitProbed(it) }
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
     * ويُعلن «لا روابط» على صفحةٍ فيها رابط حيّ فعلاً.
     */
    private fun looksLikeMedia(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m4v") ||
            path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".webm") || path.endsWith(".mkv") ||
            path.endsWith(".ts")
    }

    /**
     * كل إطارات الصفحة التي تحمل رابطاً، بأي اسم سمة.
     *
     * ## سمات التحويل المتأخر ليست تفصيلاً
     * مقيس على SexArab69: الصفحة تكتب
     * `<iframe data-lazyloaded="1" src="about:blank" data-litespeed-src="https://voe.sx/e/…">`.
     * فمن يقرأ `src` وحده يحصل على `about:blank`. ومن يعدّ أسماء السمات
     * (`data-src`, `data-lazy-src`) يسقط `data-litespeed-src` وكل اسمٍ
     * تخترعه إضافةُ تحويلٍ لاحقاً. فالصواب أن يُسأل السؤال المعاكس: **أيّ
     * سمةٍ في هذا العنصر تحمل رابطاً؟** فيُمسح الكل ويُؤخذ أول ما يبدأ بـ
     * `http` أو `//` أو `/`، ويُطرح `about:blank` تلقائياً.
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
     * والمصادر تصل POST كـJSON فيه `sources[].file` رابط m3u8 حيّ. وقد قيس
     * الآن أن النداء يردّ `index.m3u8` على `cdn4.bitlyurl.xyz` بوسم
     * `application/x-mpegURL` ويخدم 206.
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
     * `…/ajax_sources.php` — وهو مسار غير موجود. والقاعدة نفسها في HTTP:
     * النسبي يُحَلّ على مجلّد المستند.
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
