package com.axiomtv

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Saves a crash (stack trace + recent log, logins blanked out) to a file, and sends it to the
 * licence server the next time the app starts, where it shows under Crash reports on the admin page.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
                val report =
                    buildString {
                        append("Crash at $time on thread ${t.name}\n\n")
                        append(Log.getStackTraceString(e).take(12000))
                        append("\n\n--- recent log ---\n")
                        append(AxiomLog.dump().takeLast(14000))
                    }
                File(app.filesDir, FILE).writeText(report)
            }
            previous?.uncaughtException(t, e)
        }
        recordExitReasons(app)
        sendPending(app)
    }

    /**
     * Android 11+: asks the system why the app last closed. Freezes (ANR) and crashes in the native
     * playback libraries never reach the handler above, but Android records them with details.
     */
    private fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences("axiomtv_crash", Context.MODE_PRIVATE)
            // First run with this feature: look back a day, to catch the crash that's already happened
            val lastSeen = prefs.getLong("last_exit_ts", System.currentTimeMillis() - 86_400_000L)
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val newest = exits.maxOfOrNull { it.timestamp } ?: return
            prefs.edit().putLong("last_exit_ts", newest).apply()
            val bad =
                exits.filter {
                    it.timestamp > lastSeen &&
                        it.reason in
                        setOf(
                            android.app.ApplicationExitInfo.REASON_ANR,
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                            android.app.ApplicationExitInfo.REASON_CRASH,
                            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                            android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
                        )
                }
            if (bad.isEmpty()) return
            val file = File(context.filesDir, FILE)
            if (file.exists()) return // a full Java crash report is already waiting
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
            val report =
                buildString {
                    for (exit in bad) {
                        val kind =
                            when (exit.reason) {
                                android.app.ApplicationExitInfo.REASON_ANR -> "App froze (not responding)"
                                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crash in native code (playback libraries)"
                                android.app.ApplicationExitInfo.REASON_CRASH -> "Crash"
                                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: low memory"
                                else -> "Failed to start"
                            }
                        append("$kind at ${time.format(Date(exit.timestamp))}\n")
                        append("Description: ${exit.description}\n")
                        append("Memory used: ${exit.pss / 1024} MB\n")
                        val trace = runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        if (!trace.isNullOrBlank()) append("\n--- details ---\n").append(trace.take(20000))
                        append("\n\n")
                    }
                }
            file.writeText(report)
        }
    }

    private fun sendPending(context: Context) {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return
        thread(name = "crash-report", isDaemon = true) {
            runCatching {
                val code = context.getSharedPreferences("axiomtv_licence", Context.MODE_PRIVATE).getString("code", null)
                val body =
                    JSONObject()
                        .put("report", file.readText().take(30000))
                        .put("code", code ?: JSONObject.NULL)
                        .put("model", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .toString()
                val connection = URL(LicenceManager.SERVER + "/v1/crash").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                if (connection.responseCode in 200..299) file.delete()
                connection.disconnect()
            }
        }
    }
}
