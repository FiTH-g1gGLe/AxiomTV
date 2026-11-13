package com.github.damontecres.wholphin.axiom.epg

import android.util.Xml
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext

data class EpgProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Downloads an XMLTV guide and reads it piece by piece, so even a 100MB+ guide never
 * has to fit in memory. Only programmes for channels we have, inside the time window,
 * are kept, and they're handed over in batches.
 */
@Singleton
class EpgLoader
    @Inject
    constructor(
        @param:StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val client =
            okHttpClient
                .newBuilder()
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        /**
         * @param wantedChannels lower-case guide ids of the channels in the playlist
         * @return number of programmes kept
         */
        suspend fun load(
            url: String,
            wantedChannels: Set<String>,
            from: Long,
            to: Long,
            archiveChannels: Set<String> = emptySet(),
            archiveFrom: Long = from,
            onBatch: suspend (List<EpgProgramme>) -> Unit,
        ): Int =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "AxiomTV")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Guide server replied ${response.code}")
                    val raw = BufferedInputStream(response.body.byteStream(), 64 * 1024)
                    parse(maybeGunzip(raw), wantedChannels, from, to, archiveChannels, archiveFrom, onBatch)
                }
            }

        private fun maybeGunzip(input: BufferedInputStream): InputStream {
            input.mark(2)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            return if (b1 == 0x1f && b2 == 0x8b) {
                Timber.i("Guide is gzipped")
                GZIPInputStream(input, 64 * 1024)
            } else {
                input
            }
        }

        private suspend fun parse(
            input: InputStream,
            wanted: Set<String>,
            from: Long,
            to: Long,
            archive: Set<String>,
            archiveFrom: Long,
            onBatch: suspend (List<EpgProgramme>) -> Unit,
        ): Int {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            val batch = ArrayList<EpgProgramme>(BATCH_SIZE)
            var kept = 0
            var seen = 0

            var channelId: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var description: String? = null
            var inProgramme = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "programme" -> {
                                seen++
                                if (seen % 5000 == 0) currentCoroutineContext().ensureActive()
                                val id = parser.getAttributeValue(null, "channel")?.lowercase()
                                val s = parseTime(parser.getAttributeValue(null, "start"))
                                val e = parseTime(parser.getAttributeValue(null, "stop"))
                                val earliest = if (id != null && id in archive) archiveFrom else from
                                if (id != null && id in wanted && s != null && e != null && e > earliest && s < to) {
                                    inProgramme = true
                                    channelId = id
                                    start = s
                                    stop = e
                                    title = null
                                    description = null
                                } else {
                                    inProgramme = false
                                }
                            }

                            "title" -> {
                                if (inProgramme && title == null) title = readText(parser)
                            }

                            "desc" -> {
                                if (inProgramme && description == null) description = readText(parser)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && inProgramme) {
                            inProgramme = false
                            val id = channelId
                            if (id != null) {
                                batch.add(EpgProgramme(id, start, stop, title?.trim() ?: "No title", description?.trim()))
                                kept++
                                if (batch.size >= BATCH_SIZE) {
                                    onBatch(ArrayList(batch))
                                    batch.clear()
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
            if (batch.isNotEmpty()) onBatch(batch)
            Timber.i("Guide parsed: %d programmes seen, %d kept", seen, kept)
            return kept
        }

        private fun readText(parser: XmlPullParser): String? =
            try {
                parser.nextText()
            } catch (ex: Exception) {
                null
            }

        companion object {
            private const val BATCH_SIZE = 2000
            private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

            /** XMLTV time, e.g. "20261112200000 +0000". No offset means UTC. */
            fun parseTime(value: String?): Long? {
                if (value == null || value.length < 14) return null
                return try {
                    val local = LocalDateTime.parse(value.substring(0, 14), TIME_FORMAT)
                    val offsetText = value.substring(14).trim()
                    val offset = if (offsetText.isEmpty()) ZoneOffset.UTC else ZoneOffset.of(offsetText)
                    local.toInstant(offset).toEpochMilli()
                } catch (ex: Exception) {
                    null
                }
            }
        }
    }
