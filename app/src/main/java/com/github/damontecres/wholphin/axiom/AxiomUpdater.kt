package com.github.damontecres.wholphin.axiom

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A version like 0.1.48, compared number by number. */
data class AppVersion(
    val parts: List<Int>,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    fun isGreaterThan(other: AppVersion) = this > other

    override fun toString() = parts.joinToString(".")

    companion object {
        fun parse(text: String?): AppVersion? {
            val numbers = Regex("\\d+(\\.\\d+)+").find(text ?: return null)?.value ?: return null
            return AppVersion(numbers.split(".").map { it.toInt() })
        }
    }
}

data class Release(
    val version: AppVersion,
    val downloadUrl: String?,
    val body: String?,
)

interface DownloadCallback {
    fun contentLength(contentLength: Long)

    fun bytesDownloaded(bytes: Long)
}

/** Checks the AxiomTV GitHub releases and installs updates. */
@Singleton
class UpdateChecker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        fun getInstalledVersion(): AppVersion {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            return AppVersion.parse(name) ?: AppVersion(listOf(0))
        }

        suspend fun getLatestRelease(updateUrl: String): Release? =
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(updateUrl).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Update check failed %s", response.code)
                        return@use null
                    }
                    val json = Json.parseToJsonElement(response.body.string()).jsonObject
                    val version = AppVersion.parse(json["name"]?.jsonPrimitive?.contentOrNull) ?: return@use null
                    Release(
                        version = version,
                        downloadUrl = json["assets"]?.jsonArray?.let { pickAsset(it) },
                        body = json["body"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }

        /** Prefers the build for this box's processor, then the universal one. */
        private fun pickAsset(assets: JsonArray): String? {
            val abi = Build.SUPPORTED_ABIS.firstOrNull()
            val preferred = listOfNotNull(abi?.let { "$ASSET_NAME-release-$it.apk" }, "$ASSET_NAME-release.apk", APK_NAME)
            val byName =
                assets.mapNotNull { asset ->
                    val obj = asset.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    name to url
                }.toMap()
            return preferred.firstNotNullOfOrNull { byName[it] }
        }

        /** Older Android needs storage permission to save the download. */
        fun hasPermissions(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                )

        @SuppressLint("RequestInstallPackagesPolicy")
        suspend fun installRelease(
            release: Release,
            callback: DownloadCallback,
        ) {
            withContext(Dispatchers.IO) {
                cleanup()
                val request = Request.Builder().url(release.downloadUrl!!).get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error downloading the update: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                        return@use
                    }
                    callback.contentLength(response.body.contentLength())
                    val uri: Uri =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values =
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, APK_NAME)
                                    put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                            val resolver = context.contentResolver
                            val inserted = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            if (inserted != null) {
                                response.body.byteStream().use { input ->
                                    resolver.openOutputStream(inserted)!!.use { output -> copy(input, output, callback) }
                                }
                                inserted
                            } else {
                                fileUri(saveToFile(response, callback))
                            }
                        } else {
                            fileUri(saveToFile(response, callback))
                        }
                    val intent =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                data = uri
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        } else {
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, APK_MIME_TYPE)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        }
                    context.startActivity(intent)
                }
            }
        }

        private fun fileUri(file: File): Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            } else {
                Uri.fromFile(file)
            }

        private fun saveToFile(
            response: Response,
            callback: DownloadCallback,
        ): File {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, APK_NAME)
            file.outputStream().use { output -> response.body.byteStream().use { input -> copy(input, output, callback) } }
            return file
        }

        private fun copy(
            input: InputStream,
            output: OutputStream,
            callback: DownloadCallback,
        ) {
            var copied = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                copied += read
                callback.bytesDownloaded(copied)
            }
        }

        /** Removes earlier downloaded updates. */
        private fun cleanup() {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
                        arrayOf("$ASSET_NAME%", APK_MIME_TYPE),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APK_NAME).delete()
                }
            }.onFailure { Timber.w(it, "Cleanup of old updates failed") }
        }

        companion object {
            const val ASSET_NAME = "AxiomTV"
            const val APK_NAME = "$ASSET_NAME.apk"
            private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        }
    }
