package com.github.damontecres.wholphin.axiom

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Fallback for TV boxes without a file picker: backups go straight into Downloads. */
object BackupFiles {
    fun saveToDownloads(
        context: Context,
        name: String,
        text: String,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, name).writeText(text)
            }
            true
        } catch (ex: Exception) {
            Timber.w(ex, "Saving backup to Downloads failed")
            false
        }

    suspend fun latestFromDownloads(context: Context): String? =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    resolver
                        .query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Downloads._ID),
                            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                            arrayOf("AxiomTV-backup%"),
                            "${MediaStore.Downloads.DATE_ADDED} DESC",
                        )?.use { cursor ->
                            if (!cursor.moveToFirst()) return@withContext null
                            val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                            resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.listFiles { file -> file.name.startsWith("AxiomTV-backup") }?.maxByOrNull { it.lastModified() }?.readText()
                }
            } catch (ex: Exception) {
                Timber.w(ex, "Reading backup from Downloads failed")
                null
            }
        }
}
