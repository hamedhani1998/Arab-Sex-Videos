package com.arabxnsex.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ArabXNSexPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabXNSexProvider())
    }
}