package com.sexalarabnet.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import sharedplay.PluginSettings

@CloudstreamPlugin
class SexAlArabNetPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات مستقلة لهذه الإضافة (ملف تفضيلات باسمها): فحص الروابط قبل
        // التشغيل، ووسم صيغة الفيديو في اسم السيرفر.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(SexAlArabNetProvider(prefs))
        openSettings = { ctx ->
            (ctx as? AppCompatActivity)?.supportFragmentManager?.let {
                PluginSettings.show(it, PREFS_NAME, prefs, "سكس العرب نت")
            }
        }
    }
}
