package com.axiomtv.library

import com.axiomtv.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Skip times for one episode, all in milliseconds. */
data class SkipTimes(
    val intro: LongRange? = null,
    val recap: LongRange? = null,
    val creditsStartMs: Long? = null,
    /** Where they came from, for the log */
    val source: String,
) {
    val isEmpty get() = intro == null && recap == null && creditsStartMs == null
}

/**
 * Community skip-time databases, tried in order:
 *  1. TheIntroDB (intro, recap, credits) by TVDB / TMDB / IMDb id
 *  2. IntroDB.app (intro, recap, outro) by IMDb id
 * Every answer is sanity-checked against the episode's length so a different cut can't send us somewhere silly.
 */
@Singleton
class SkipTimesClient
    @Inject
    constructor(
        @param:StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val client =
            okHttpClient
                .newBuilder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = HashMap<String, SkipTimes?>()

        suspend fun episode(
            tmdbId: String?,
            tvdbId: String?,
            imdbId: String?,
            season: Int,
            episode: Int,
            durationMs: Long,
        ): SkipTimes? {
            if (tmdbId.isNullOrBlank() && tvdbId.isNullOrBlank() && imdbId.isNullOrBlank()) return null
            val key = "$tvdbId|$tmdbId|$imdbId|$season|$episode"
            synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
            suspend fun attempt(source: suspend () -> SkipTimes?): SkipTimes? =
                try {
                    source()?.let { sanitise(it, durationMs) }?.takeUnless { it.isEmpty }
                } catch (ex: Exception) {
                    Timber.w(ex, "Skip times lookup failed")
                    null
                }
            val result =
                attempt { theIntroDb(tmdbId, tvdbId, imdbId, season, episode, durationMs) }
                    ?: attempt { introDbApp(imdbId, season, episode) }
            synchronized(cache) { cache[key] = result }
            Timber.i("Skip times S%dE%d: %s", season, episode, result ?: "none")
            return result
        }

        private suspend fun theIntroDb(
            tmdbId: String?,
            tvdbId: String?,
            imdbId: String?,
            season: Int,
            episode: Int,
            durationMs: Long,
        ): SkipTimes? {
            val url =
                "https://api.theintrodb.org/v4/media".toHttpUrl().newBuilder().apply {
                    when {
                        !tvdbId.isNullOrBlank() -> addQueryParameter("tvdb_id", tvdbId)
                        !tmdbId.isNullOrBlank() -> addQueryParameter("tmdb_id", tmdbId)
                        !imdbId.isNullOrBlank() -> addQueryParameter("imdb_id", imdbId)
                        else -> return null
                    }
                    addQueryParameter("season", season.toString())
                    addQueryParameter("episode", episode.toString())
                    if (durationMs > 0) addQueryParameter("duration_ms", durationMs.toString())
                }.build()
            val obj = getJson(url) ?: return null
            return SkipTimes(
                intro = obj.segments("intro").firstNotNullOfOrNull { it.range(endRequired = true) },
                recap = obj.segments("recap").firstNotNullOfOrNull { it.range(endRequired = true) },
                creditsStartMs = obj.segments("credits").firstNotNullOfOrNull { it.range(endRequired = false)?.first },
                source = "TheIntroDB",
            )
        }

        private suspend fun introDbApp(
            imdbId: String?,
            season: Int,
            episode: Int,
        ): SkipTimes? {
            if (imdbId.isNullOrBlank()) return null
            val url =
                "https://api.introdb.app/segments".toHttpUrl().newBuilder()
                    .addQueryParameter("imdb_id", imdbId)
                    .addQueryParameter("season", season.toString())
                    .addQueryParameter("episode", episode.toString())
                    .build()
            val obj = getJson(url) ?: return null
            return SkipTimes(
                intro = obj.segments("intro").firstNotNullOfOrNull { it.range(endRequired = true) },
                recap = obj.segments("recap").firstNotNullOfOrNull { it.range(endRequired = true) },
                creditsStartMs =
                    (obj.segments("outro") + obj.segments("credits"))
                        .firstNotNullOfOrNull { it.range(endRequired = false)?.first },
                source = "IntroDB",
            )
        }

        /** Drop anything that can't belong to an episode this long. */
        private fun sanitise(
            times: SkipTimes,
            durationMs: Long,
        ): SkipTimes {
            if (durationMs <= 0) return times
            fun LongRange.fitsStart() = last - first in 5_000..300_000 && last < durationMs * 0.45
            return times.copy(
                intro = times.intro?.takeIf { it.fitsStart() },
                recap = times.recap?.takeIf { it.fitsStart() },
                creditsStartMs = times.creditsStartMs?.takeIf { it > durationMs * 0.6 && it < durationMs - 5_000 },
            )
        }

        private suspend fun getJson(url: HttpUrl): JsonObject? =
            withContext(Dispatchers.IO) {
                val request =
                    Request.Builder().url(url).header("User-Agent", "AxiomTV").header("Accept", "application/json").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code != 404) Timber.w("%s replied %d", url.host, response.code)
                        return@use null
                    }
                    json.parseToJsonElement(response.body.string()) as? JsonObject
                }
            }
    }

/** A segment key may hold one object or a list of them. */
private fun JsonObject.segments(key: String): List<JsonObject> =
    when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(value)
        else -> emptyList()
    }

/** Reads start/end in ms (start_ms) or seconds (start_sec / start). */
private fun JsonObject.range(endRequired: Boolean): LongRange? {
    fun time(name: String): Long? {
        this["${name}_ms"].num()?.let { return it.toLong() }
        (this["${name}_sec"] ?: this[name]).num()?.let { return (it * 1000).toLong() }
        return null
    }
    val start = time("start")
    val end = time("end")
    return when {
        end != null && end > (start ?: 0) -> (start ?: 0)..end
        !endRequired && start != null && end == null -> start..Long.MAX_VALUE
        else -> null
    }
}

private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
