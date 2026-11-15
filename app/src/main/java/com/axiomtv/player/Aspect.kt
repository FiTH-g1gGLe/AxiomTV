package com.axiomtv.player

import android.content.Context
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.media3.ui.compose.modifiers.resizeWithContentScale

/** How the picture fits the screen. */
enum class AspectMode(
    val label: String,
) {
    FIT("Fit"),
    ZOOM("Zoom"),
    STRETCH("Stretch"),
    WIDE("16:9"),
    CLASSIC("4:3"),
    ;

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Remembers the picture shape per film, per show and per channel. Anything not set is Fit. */
class AspectMemory(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("axiomtv_aspect", Context.MODE_PRIVATE)

    fun get(key: String?): AspectMode =
        key?.let { prefs.getString(it, null) }?.let { name -> AspectMode.entries.firstOrNull { it.name == name } } ?: AspectMode.FIT

    fun set(
        key: String?,
        mode: AspectMode,
    ) {
        if (key == null) return
        prefs.edit().apply { if (mode == AspectMode.FIT) remove(key) else putString(key, mode.name) }.apply()
    }
}

/**
 * Size of the video surface. mpv always gets the whole screen and shapes the picture itself
 * (see MpvPlayer.applyAspect); ExoPlayer's surface is shaped here.
 */
fun Modifier.videoAspect(
    mode: AspectMode,
    engine: Engine,
    videoSizeDp: Size?,
): Modifier =
    if (engine == Engine.MPV) {
        fillMaxSize()
    } else {
        when (mode) {
            AspectMode.FIT -> resizeWithContentScale(ContentScale.Fit, videoSizeDp)
            AspectMode.ZOOM -> resizeWithContentScale(ContentScale.Crop, videoSizeDp)
            AspectMode.STRETCH -> fillMaxSize()
            AspectMode.WIDE -> fillMaxSize().wrapContentSize().aspectRatio(16f / 9f)
            AspectMode.CLASSIC -> fillMaxSize().wrapContentSize().aspectRatio(4f / 3f)
        }
    }
