package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.kreastream.HDMomPlayer
import com.kreastream.HDPlayerSystem
import com.kreastream.VideoSeyred
import com.kreastream.PeaceMakerst
import com.kreastream.HDStreamAble
import com.kreastream.DiziMom

@CloudstreamPlugin
class DiziMomPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziMom())
        registerExtractorAPI(HDMomPlayer())
        registerExtractorAPI(HDPlayerSystem())
        registerExtractorAPI(VideoSeyred())
        registerExtractorAPI(PeaceMakerst())
        registerExtractorAPI(HDStreamAble())
    }
}