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
        sendPending(app)
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
