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
        installCrashRecorder()
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

    /** Saves the stack trace of a crash so it can be copied with the debug log after reopening the app. */
    private fun installCrashRecorder() {
        val file = java.io.File(filesDir, "last_crash.txt")
        AxiomLog.crashFile = file
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    "Crash at ${java.util.Date()} in ${BuildConfig.VERSION_NAME} (thread ${thread.name})\n" +
                        Log.getStackTraceString(error).take(12000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
