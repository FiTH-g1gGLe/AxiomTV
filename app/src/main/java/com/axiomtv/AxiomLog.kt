package com.axiomtv

import android.os.Build
import android.util.Log
import com.axiomtv.BuildConfig
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few hundred log lines in memory so they can be copied from the app
 * and sent for debugging. Login details are blanked out before anything is copied.
 */
object AxiomLog : Timber.Tree() {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)

    @Volatile
    private var installed = false

    /** Where the last crash was saved (see AxiomApplication) */
    @Volatile
    var crashFile: java.io.File? = null

    @Volatile
    var secrets: List<String> = emptyList()

    fun install() {
        if (!installed) {
            installed = true
            Timber.plant(this)
        }
    }

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        if (priority < Log.INFO) return
        val level =
            when (priority) {
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                else -> "A"
            }
        val stack = if (t != null) "\n" + Log.getStackTraceString(t).take(3000) else ""
        val line = "${timeFormat.format(Date())} $level ${tag ?: "-"}: $message$stack"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun dump(): String {
        val header =
            "AxiomTV ${BuildConfig.VERSION_NAME} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        var text = synchronized(lines) { lines.joinToString("\n") }
        secrets.filter { it.length >= 3 }.forEach { secret ->
            text = text.replace(secret, "***")
        }
        val crash = runCatching { crashFile?.takeIf { it.exists() }?.readText() }.getOrNull()
        val crashPart = if (crash != null) "\n\n--- LAST CRASH ---\n$crash\n--- END CRASH ---" else ""
        return header + crashPart + "\n\n" + text
    }
}
