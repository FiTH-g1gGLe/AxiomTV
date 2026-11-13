package com.github.damontecres.wholphin.axiom.library

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class TmdbCast(
    val name: String,
    val character: String?,
    val photo: String?,
)

data class TmdbDetails(
    val id: String,
    val title: String?,
    val overview: String?,
    val tagline: String?,
    val backdrop: String?,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val certification: String?,
    val cast: List<TmdbCast>,
    /** YouTube video key of the trailer */
    val trailer: String? = null,
)

/**
 * Looks up films and series on TMDB for fanart, cast and descriptions.
 * Uses the provider's TMDB id when it has one, otherwise searches by title + year.
 */
@Singleton
class TmdbClient
    @Inject
    constructor(
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = HashMap<String, TmdbDetails?>()
        private val apiKey = BuildConfig.TMDB_API_KEY

        val enabled: Boolean get() = apiKey.isNotBlank()

        suspend fun movie(
            tmdbId: String?,
            title: String,
            year: Int?,
        ): TmdbDetails? = lookup("movie", tmdbId, title, year)

        suspend fun tv(
            tmdbId: String?,
            title: String,
            year: Int?,
        ): TmdbDetails? = lookup("tv", tmdbId, title, year)

        private suspend fun lookup(
            type: String,
            tmdbId: String?,
            title: String,
            year: Int?,
        ): TmdbDetails? {
            if (!enabled) return null
            val cacheKey = "$type|${tmdbId ?: ""}|$title|${year ?: ""}"
            synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }
            val result =
                try {
                    val id = tmdbId?.takeIf { it.isNotBlank() && it != "0" } ?: search(type, title, year)
                    id?.let { details(type, it) }
                } catch (ex: Exception) {
                    Timber.w(ex, "TMDB lookup failed for %s", title)
                    null
                }
            synchronized(cache) { cache[cacheKey] = result }
            return result
        }

        private suspend fun search(
            type: String,
            title: String,
            year: Int?,
        ): String? {
            val params =
                buildMap {
                    put("query", title)
                    if (year != null) put(if (type == "movie") "year" else "first_air_date_year", year.toString())
                }
            var results = (get("search/$type", params)?.get("results") as? JsonArray)
            if (results.isNullOrEmpty() && year != null) {
                results = get("search/$type", mapOf("query" to title))?.get("results") as? JsonArray
            }
            return (results?.firstOrNull() as? JsonObject)?.str("id")
        }

        private suspend fun details(
            type: String,
            id: String,
        ): TmdbDetails? {
            val extra = if (type == "movie") "credits,release_dates,videos" else "credits,content_ratings,videos"
            val obj = get("$type/$id", mapOf("append_to_response" to extra)) ?: return null
            val genres =
                (obj["genres"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty()
            val cast =
                ((obj["credits"] as? JsonObject)?.get("cast") as? JsonArray)
                    ?.take(15)
                    ?.mapNotNull { element ->
                        val person = element as? JsonObject ?: return@mapNotNull null
                        val name = person.str("name") ?: return@mapNotNull null
                        TmdbCast(name, person.str("character"), person.str("profile_path")?.let { "$IMAGE_BASE/w185$it" })
                    }.orEmpty()
            val date = obj.str("release_date") ?: obj.str("first_air_date")
            val runtime =
                obj.str("runtime")?.toIntOrNull()
                    ?: (obj["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            return TmdbDetails(
                id = id,
                title = obj.str("title") ?: obj.str("name"),
                overview = obj.str("overview"),
                tagline = obj.str("tagline"),
                backdrop = obj.str("backdrop_path")?.let { "$IMAGE_BASE/w1280$it" },
                poster = obj.str("poster_path")?.let { "$IMAGE_BASE/w500$it" },
                rating = obj.str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 },
                year = date?.take(4)?.toIntOrNull(),
                runtimeMinutes = runtime?.takeIf { it > 0 },
                genres = genres,
                certification = certification(type, obj),
                cast = cast,
                trailer = trailer(obj),
            )
        }

        /** Best YouTube trailer: official trailers first, then any trailer or teaser. */
        private fun trailer(obj: JsonObject): String? {
            val videos =
                ((obj["videos"] as? JsonObject)?.get("results") as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter { it.str("site") == "YouTube" && !it.str("key").isNullOrBlank() }
                    .orEmpty()
            val ranked =
                videos.sortedBy { video ->
                    when {
                        video.str("type") == "Trailer" && video.str("official") == "true" -> 0
                        video.str("type") == "Trailer" -> 1
                        video.str("type") == "Teaser" -> 2
                        else -> 3
                    }
                }
            return ranked.firstOrNull()?.str("key")
        }

        /** UK age rating if TMDB has one, e.g. "15". */
        private fun certification(
            type: String,
            obj: JsonObject,
        ): String? {
            if (type == "movie") {
                val countries = (obj["release_dates"] as? JsonObject)?.get("results") as? JsonArray ?: return null
                val gb = countries.firstOrNull { (it as? JsonObject)?.str("iso_3166_1") == "GB" } as? JsonObject ?: return null
                return (gb["release_dates"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.str("certification") }
                    ?.firstOrNull { it.isNotBlank() }
            }
            val ratings = (obj["content_ratings"] as? JsonObject)?.get("results") as? JsonArray ?: return null
            return (ratings.firstOrNull { (it as? JsonObject)?.str("iso_3166_1") == "GB" } as? JsonObject)?.str("rating")
        }

        private suspend fun get(
            path: String,
            params: Map<String, String>,
        ): JsonObject? =
            withContext(Dispatchers.IO) {
                val url =
                    "https://api.themoviedb.org/3/$path"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("api_key", apiKey)
                        .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                        .build()
                okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("TMDB %s replied %d", path, response.code)
                        return@use null
                    }
                    json.parseToJsonElement(response.body.string()) as? JsonObject
                }
            }

        companion object {
            private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        }
    }

private fun JsonObject.str(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.takeIf { it.isNotBlank() && it != "null" }
}
