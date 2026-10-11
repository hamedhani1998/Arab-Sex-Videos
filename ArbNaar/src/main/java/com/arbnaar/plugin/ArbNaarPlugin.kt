package com.arbnaar.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import sharedplay.PluginSettings

@CloudstreamPlugin
class ArbNaarPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات خاصة بهذه الإضافة وحدها: اسم ملف التفضيلات = اسم الوحدة،
        // فلكل إضافة خياراتها المستقلة ولا تتقاطع مع غيرها.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(ArbNaarProvider(prefs))
        openSettings = { ctx ->
            (ctx as? AppCompatActivity)?.supportFragmentManager?.let {
                PluginSettings.show(it, PREFS_NAME, prefs, "عرب نار")
            }
        }
    }
}
