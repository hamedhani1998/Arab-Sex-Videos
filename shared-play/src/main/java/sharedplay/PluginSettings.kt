package sharedplay

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * لوحة إعدادات أي إضافة في هذا المستودع.
 *
 * كل إضافة لها **ملف تفضيلات خاص بها** (اسم الإضافة) ومفاتيح مسبوقة باسمها،
 * فلا تتصادم الإضافات داخل عملية التطبيق الواحدة ويمكن ضبط كل واحدة على حِدة
 * — وهو المطلوب.
 *
 * ## لماذا هذان الخياران بالتحديد
 * الخيارات تُقاس بفائدتها لا بكثرتها. هذان الاثنان يعالجان مشكلتين حقيقيتين
 * في هذه المواقع:
 *
 * 1. **فحص الروابط قبل التشغيل** — المواقع تنشر جودات ميتة (عربدة 720p يردّ
 *    404)، فينفجر المشغّل بالخطأ 2004. الفحص يُسقطها قبل العرض، وثمنه طلب
 *    شبكة صغير لكل رابط. يُوفَّر مفتاح إيقافه لمن يفضّل السرعة على السلامة.
 * 2. **وسم صيغة السيرفر في الاسم** — المشغّل يعرض اسم السيرفر فقط، فلا سبيل
 *    لمعرفة MP4 من M3U8 من DASH إلا بوسمٍ في الاسم.
 */
class PluginSettings(
    private val prefsName: String,
    private val prefs: SharedPreferences,
    private val pluginTitle: String
) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefsName, prefs, pluginTitle))
            .commit()
    }

    companion object {
        /** مفتاح «فحص الروابط» داخل ملف تفضيلات الإضافة. */
        fun probeKey(name: String) = "${name}_probe"

        /** مفتاح «وسم الصيغة» داخل ملف تفضيلات الإضافة. */
        fun tagKey(name: String) = "${name}_format_tag"

        fun show(fm: FragmentManager, prefsName: String, prefs: SharedPreferences, title: String) {
            PluginSettings(prefsName, prefs, title).show(fm, "${prefsName}_settings")
        }
    }

    class PrefsFragment(
        private val prefsName: String,
        private val prefs: SharedPreferences,
        private val pluginTitle: String
    ) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(prefsName)
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val play = PreferenceCategory(ctx)
            play.title = "التشغيل"
            preferenceScreen.addPreference(play)

            // فحص الروابط: يمنع وصول الجودة الميتة إلى المشغّل (خطأ 2004).
            play.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = probeKey(prefsName)
                title = "فحص الروابط قبل التشغيل"
                summary = "يُسقط الجودات الميتة فلا يظهر خطأ 2004 — أبطأ قليلاً"
                setDefaultValue(true)
            })

            // وسم الصيغة: طريق المستخدم الوحيد لتمييز MP4/M3U8/DASH قبل الضغط.
            play.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = tagKey(prefsName)
                title = "إظهار صيغة الفيديو في الاسم"
                summary = "يضيف [MP4] أو [M3U8] أو [HLS] أو [DASH] أو [TS] بجانب اسم السيرفر"
                setDefaultValue(true)
            })

            val reset = Preference(ctx).apply {
                key = "${prefsName}_reset"
                title = "إعادة الضبط"
                summary = "يعيد خيارات «$pluginTitle» إلى الافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit()
                        .remove(probeKey(prefsName))
                        .remove(tagKey(prefsName))
                        .apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(reset)
        }
    }
}
