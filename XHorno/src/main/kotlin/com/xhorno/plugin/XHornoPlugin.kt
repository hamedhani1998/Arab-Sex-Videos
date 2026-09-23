package com.xhorno.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XHornoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XHornoProvider())
    }
}