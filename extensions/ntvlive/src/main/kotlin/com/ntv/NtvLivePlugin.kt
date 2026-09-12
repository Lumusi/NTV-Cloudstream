package com.ntv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.ntv.NtvLive
import com.ntv.NtvEmbedExtractor

@CloudstreamPlugin
class NtvLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NtvLive())
        registerExtractorAPI(NtvEmbedExtractor(context))
    }
}
