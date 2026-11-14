package com.axiomtv

import android.app.Application
import android.util.Log
import com.axiomtv.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AxiomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        copySubtitleFont()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(
                object : Timber.Tree() {
                    override fun isLoggable(
                        tag: String?,
                        priority: Int,
                    ) = priority >= Log.INFO

                    override fun log(
                        priority: Int,
                        tag: String?,
                        message: String,
                        t: Throwable?,
                    ) {
                        Log.println(priority, tag ?: "AxiomTV", message)
                    }
                },
            )
        }
    }

    /** mpv draws subtitles with the font it finds in its config folder (the app's files folder). */
    private fun copySubtitleFont() {
        runCatching {
            val output = java.io.File(filesDir, "subfont.ttf")
            if (!output.exists()) {
                assets.open("subfont.ttf").use { input -> output.outputStream().use { input.copyTo(it) } }
            }
        }.onFailure { Timber.w(it, "Couldn't copy the subtitle font") }
    }
}
