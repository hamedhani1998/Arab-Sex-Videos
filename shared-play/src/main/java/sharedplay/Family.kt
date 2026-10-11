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
 * ثلاثة عشر من إضافات هذا المستودع تعمل على نفس المحرك، والدليل البنيوي
 * واحد في كل صفحة: `kt_player`، ومفاتيح `video_url` / `video_alt_url` /
 * `video_alt_url2` داخل كتلة إعداد، وروابط `/get_file/<n>/<hash>/…`.
 * فبدل تكرار منطقٍ ناقصٍ في ثلاث عشرة إضافة، يوجد هنا نسخة واحدة مُقاسة.
 *
 * ## الأنماط الثلاثة للمفاهيم نفسها (مقيسة 2026-10-11)
 * 1. **وسوم `<video><source>` مطلقة** على CDN — عربدة. لا `flashvars` إطلاقاً.
 * 2. **كتلة إعداد عارية بلا اسم** — Sex6x: `video_url: '…'` داخل سكربت بلا
 *    كلمة `flashvars`، وروابطها تحمل توكن `v-acctoken` فتعمل. من يشترط كلمة
 *    `flashvars` لا يجد شيئاً ويُعلن «لا روابط» — وهذا كان عطل Sex6x بالحرف.
 * 3. **`function/0/<رابط>`** — 3rabxn وSexArabFun وSexAllArab وFreeSexArab:
 *    البادئة علامة توليد، وما بعدها رابط `get_file` **يردّ 404** بلا توكن
 *    `v-acctoken`. لهذه المواقع لا يُصدَّر `get_file` أبداً، بل يُشتقّ رابط
 *    القرص المباشر `/contents/videos/<bucket>/<id>/<id>.mp4` — وهو الذي
 *    قاسه الاختبار حيّاً (206) على SexArabFun وFreeSexArab.
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
            var url = Probe.unwrapFunction(raw.trim())
            if (url.isBlank()) return
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http")) return
            // تصفية الاستعلام: مواقع KVS تلحق معاملات دخيلة على الرابط
            // (`?br=652&embed=true` و`?download_filename=…&download=true`)
            // فيردّ الخادم 404 عليها. نفس الرابط عارياً يردّ 302 إلى CDN ثم
            // 206. قيس هذا على 3rabxn وSexArabFun وSexAllArab وFreeSexArab.
            url = url.substringBefore('?')
            if (!url.startsWith("http")) return
            // الروابط المنتهية بشرطة مائلة إضافية (`…/839.mp4/`) هي مسارات
            // KVS الشرعية، ويخدمها الخادم بالتحويل إلى رابط موقّت.
            url = url.trimEnd('/')
            if (!looksLikeMedia(url)) return
            if (!emitted.add(url)) return
            if (options.probe && !Probe.alive(url, referer)) return
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
                    this.referer = referer
                    this.quality = quality
                }
            )
            found++
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
        // تضييق الروابط برقم الفيديو نفسه مهمّ: الصفحة تحمل مصغّرات فيديوهات
        // أخرى (Sex6x: `1963_preview.mp4`) وهي حيّة لكنها ليست مادتنا.
        val vid = Probe.videoId(html)
        if (vid != null) {
            // نلتقط كل روابط get_file ثم نرشّح باسم الملف: يجب أن يبدأ برقم
            // هذا الفيديو (`3329.mp4` / `3329_480p.mp4`). مسار KVS ثلاثي
            // المقاطع بعد الهاش (`/3000/3329/3329.mp4`)، فلا يصحّ نمط يفترض
            // مقطعين — وهذا ما أسقط الحصاد أول مرة.
            val anyGetFile = Regex("""https?://[^"'\s<>]*/get_file/[^"'\s<>]*?\.mp4/?""")
            for (m in anyGetFile.findAll(html)) {
                val u = m.value
                val fname = u.substringBefore('?').trimEnd('/').substringAfterLast('/')
                if (fname.contains("preview", true)) continue
                if (!fname.startsWith("$vid.") && !fname.startsWith("${vid}_")) continue
                emit(u)
            }
        }

        // 3) صفحة الـembed — بعض المواقع لا تضع كتلة الإعداد إلا فيها
        //    (`/embed/<id>`)، وتكون الصفحة الأم حاوية فقط.
        if (found == 0) {
            val embed = doc.selectFirst("iframe[src*=/embed/]")?.attr("src")
                ?: doc.selectFirst("meta[itemprop=embedURL]")?.attr("content")
            if (!embed.isNullOrBlank()) {
                val embedUrl = if (embed.startsWith("//")) "https:$embed"
                else if (embed.startsWith("/")) origin + embed else embed
                fetch(embedUrl, referer)?.let { ed ->
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
                }
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
     * هل يبدو الرابط وسائط فعلية؟ المسار يُنظر إليه قبل علامة الاستفهام، فالمواقع
     * تُلحق التوكنات بالاستعلام (`?br=652&embed=true`) ولا يجوز أن تحجب النهاية.
     * وهذا هو الفرق بين `…/839.mp4` و`…/preview.mp4.jpg` — الأخرى صورة.
     */
    private fun looksLikeMedia(u: String): Boolean {
        val l = u.substringBefore('?').lowercase()
        if (l.endsWith(".jpg") || l.endsWith(".png") || l.endsWith(".webp") ||
            l.endsWith(".jpeg") || l.endsWith(".gif")
        ) return false
        return l.endsWith(".mp4") || l.endsWith(".m4v") || l.endsWith(".m3u8") ||
            l.endsWith(".mpd") || l.endsWith(".webm") || l.endsWith(".mkv") ||
            l.endsWith(".ts")
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
