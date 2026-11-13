package com.github.damontecres.wholphin.axiom.xtream

import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

class XtreamException(
    message: String,
) : Exception(message)

data class XtreamLogin(
    val server: String,
    val username: String,
    val password: String,
)

data class XtreamAccountInfo(
    val status: String?,
    val expiry: Long?,
    val maxConnections: String?,
    /** Server's timezone, e.g. "Europe/London"; catch-up links use the server's local time */
    val timezone: String?,
)

data class XtreamCategory(
    val id: String,
    val name: String,
)

data class XtreamVod(
    val streamId: Long,
    val name: String,
    val icon: String?,
    val rating: Double?,
    val added: Long,
    val categoryId: String?,
    val extension: String,
    val tmdbId: String?,
)

data class XtreamSeries(
    val seriesId: Long,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val rating: Double?,
    val year: Int?,
    val categoryId: String?,
    val lastModified: Long,
    val tmdbId: String?,
)

data class XtreamVodInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationSecs: Long?,
    val backdrop: String?,
    val rating: Double?,
    val tmdbId: String?,
    val trailer: String?,
)

data class XtreamEpisode(
    val id: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String?,
    val durationSecs: Long?,
    val image: String?,
    val extension: String,
)

data class XtreamSeriesInfo(
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val releaseDate: String?,
    val backdrop: String?,
    val tmdbId: String?,
    val seasons: Map<Int, List<XtreamEpisode>>,
)

data class XtreamLiveStream(
    val streamId: Long,
    val number: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val hasArchive: Boolean,
)

/**
 * Talks to an Xtream Codes compatible server (player_api.php).
 *
 * Xtream servers are inconsistent about types (numbers arrive as strings or numbers,
 * fields can be missing or null), so everything is read leniently.
 */
@Singleton
class XtreamClient
    @Inject
    constructor(
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /** Turns whatever the user typed into a clean base URL, e.g. "host:8080/" -> "http://host:8080" */
        fun normaliseServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
                s = "http://$s"
            }
            s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("?")
            return s.trimEnd('/')
        }

        private fun baseUrl(login: XtreamLogin): HttpUrl =
            try {
                login.server.toHttpUrl()
            } catch (ex: IllegalArgumentException) {
                throw XtreamException("That server address doesn't look right")
            }

        private suspend fun call(
            login: XtreamLogin,
            action: String?,
        ): JsonElement =
            withContext(Dispatchers.IO) {
                val url =
                    baseUrl(login)
                        .newBuilder()
                        .addPathSegment("player_api.php")
                        .addQueryParameter("username", login.username)
                        .addQueryParameter("password", login.password)
                        .apply { if (action != null) addQueryParameter("action", action) }
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                Timber.i("Xtream request: %s", action ?: "login")
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw XtreamException("Server replied with error ${response.code}")
                    }
                    val body = response.body.string()
                    if (body.isBlank()) throw XtreamException("The server sent an empty reply")
                    try {
                        json.parseToJsonElement(body)
                    } catch (ex: Exception) {
                        Timber.w("Xtream reply was not JSON: %s", body.take(200))
                        throw XtreamException("The server didn't send valid data")
                    }
                }
            }

        suspend fun authenticate(login: XtreamLogin): XtreamAccountInfo {
            val root = call(login, null) as? JsonObject ?: throw XtreamException("Unexpected reply from the server")
            val user =
                root["user_info"] as? JsonObject
                    ?: throw XtreamException("Login failed - check the server, username and password")
            if (user.str("auth") == "0") {
                throw XtreamException("Login rejected - check the username and password")
            }
            val status = user.str("status")
            if (status != null && !status.equals("Active", ignoreCase = true)) {
                Timber.w("Xtream account status: %s", status)
            }
            return XtreamAccountInfo(
                status = status,
                expiry = user.str("exp_date")?.toLongOrNull(),
                maxConnections = user.str("max_connections"),
                timezone = (root["server_info"] as? JsonObject)?.str("timezone"),
            )
        }

        /**
         * Catch-up (timeshift) link for a programme that has already aired.
         * Format: /timeshift/<user>/<pass>/<minutes>/<yyyy-MM-dd:HH-mm in server time>/<stream>.ts
         */
        fun catchupUrl(
            login: XtreamLogin,
            streamId: Long,
            startMillis: Long,
            durationMinutes: Int,
            serverZone: java.time.ZoneId,
        ): String {
            val start =
                java.time.Instant
                    .ofEpochMilli(startMillis)
                    .atZone(serverZone)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
            return baseUrl(login)
                .newBuilder()
                .addPathSegment("timeshift")
                .addPathSegment(login.username)
                .addPathSegment(login.password)
                .addPathSegment(durationMinutes.toString())
                .addPathSegment(start)
                .addPathSegment("$streamId.ts")
                .build()
                .toString()
        }

        suspend fun liveCategories(login: XtreamLogin): List<XtreamCategory> {
            val array = call(login, "get_live_categories") as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.str("category_id") ?: return@mapNotNull null
                XtreamCategory(id = id, name = obj.str("category_name") ?: "Uncategorised")
            }
        }

        suspend fun liveStreams(login: XtreamLogin): List<XtreamLiveStream> {
            val array = call(login, "get_live_streams") as? JsonArray ?: return emptyList()
            return array.mapIndexedNotNull { index, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                val id = obj.str("stream_id")?.toLongOrNull() ?: return@mapIndexedNotNull null
                XtreamLiveStream(
                    streamId = id,
                    number = obj.str("num")?.toIntOrNull() ?: (index + 1),
                    name = obj.str("name") ?: "Channel $id",
                    icon = obj.str("stream_icon"),
                    categoryId = obj.str("category_id"),
                    epgChannelId = obj.str("epg_channel_id"),
                    hasArchive = obj.str("tv_archive") == "1",
                )
            }
        }

        /** Reads a big JSON array one item at a time (films/series lists can be 20MB+). */
        @OptIn(ExperimentalSerializationApi::class)
        private suspend fun <T> streamArray(
            login: XtreamLogin,
            action: String,
            map: (Int, JsonObject) -> T?,
        ): List<T> =
            withContext(Dispatchers.IO) {
                val url =
                    baseUrl(login)
                        .newBuilder()
                        .addPathSegment("player_api.php")
                        .addQueryParameter("username", login.username)
                        .addQueryParameter("password", login.password)
                        .addQueryParameter("action", action)
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                Timber.i("Xtream request (streamed): %s", action)
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw XtreamException("Server replied with error ${response.code}")
                    val result = ArrayList<T>()
                    try {
                        json
                            .decodeToSequence<JsonElement>(response.body.byteStream(), DecodeSequenceMode.ARRAY_WRAPPED)
                            .forEachIndexed { index, element ->
                                val obj = element as? JsonObject ?: return@forEachIndexed
                                map(index, obj)?.let { result.add(it) }
                            }
                    } catch (ex: Exception) {
                        if (result.isEmpty()) {
                            Timber.w(ex, "Could not read %s", action)
                            return@use emptyList()
                        }
                        Timber.w(ex, "Stopped reading %s after %d items", action, result.size)
                    }
                    result
                }
            }

        private suspend fun categories(
            login: XtreamLogin,
            action: String,
        ): List<XtreamCategory> {
            val array = call(login, action) as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.str("category_id") ?: return@mapNotNull null
                XtreamCategory(id = id, name = obj.str("category_name") ?: "Uncategorised")
            }
        }

        suspend fun vodCategories(login: XtreamLogin) = categories(login, "get_vod_categories")

        suspend fun seriesCategories(login: XtreamLogin) = categories(login, "get_series_categories")

        suspend fun vodStreams(login: XtreamLogin): List<XtreamVod> =
            streamArray(login, "get_vod_streams") { _, obj ->
                val id = obj.str("stream_id")?.toLongOrNull() ?: return@streamArray null
                XtreamVod(
                    streamId = id,
                    name = obj.str("name") ?: "Film $id",
                    icon = obj.str("stream_icon"),
                    rating = obj.str("rating")?.toDoubleOrNull(),
                    added = obj.str("added")?.toLongOrNull() ?: 0L,
                    categoryId = obj.str("category_id"),
                    extension = obj.str("container_extension") ?: "mp4",
                    tmdbId = obj.str("tmdb") ?: obj.str("tmdb_id"),
                )
            }

        suspend fun seriesList(login: XtreamLogin): List<XtreamSeries> =
            streamArray(login, "get_series") { _, obj ->
                val id = obj.str("series_id")?.toLongOrNull() ?: return@streamArray null
                XtreamSeries(
                    seriesId = id,
                    name = obj.str("name") ?: "Series $id",
                    cover = obj.str("cover"),
                    backdrop = obj.firstString("backdrop_path"),
                    plot = obj.str("plot"),
                    rating = obj.str("rating")?.toDoubleOrNull(),
                    year = (obj.str("year") ?: obj.str("releaseDate") ?: obj.str("release_date"))?.take(4)?.toIntOrNull(),
                    categoryId = obj.str("category_id"),
                    lastModified = obj.str("last_modified")?.toLongOrNull() ?: 0L,
                    tmdbId = obj.str("tmdb") ?: obj.str("tmdb_id"),
                )
            }

        suspend fun vodInfo(
            login: XtreamLogin,
            streamId: Long,
        ): XtreamVodInfo? {
            val root = callWith(login, "get_vod_info", "vod_id", streamId.toString()) as? JsonObject ?: return null
            val info = root["info"] as? JsonObject ?: return null
            return XtreamVodInfo(
                plot = info.str("plot") ?: info.str("description"),
                cast = info.str("cast") ?: info.str("actors"),
                director = info.str("director"),
                genre = info.str("genre"),
                releaseDate = info.str("releasedate") ?: info.str("release_date"),
                durationSecs = info.str("duration_secs")?.toLongOrNull(),
                backdrop = info.firstString("backdrop_path"),
                rating = info.str("rating")?.toDoubleOrNull(),
                tmdbId = info.str("tmdb_id") ?: info.str("tmdb"),
                trailer = info.str("youtube_trailer"),
            )
        }

        suspend fun seriesInfo(
            login: XtreamLogin,
            seriesId: Long,
        ): XtreamSeriesInfo? {
            val root = callWith(login, "get_series_info", "series_id", seriesId.toString()) as? JsonObject ?: return null
            val info = root["info"] as? JsonObject
            val seasons = sortedMapOf<Int, List<XtreamEpisode>>()
            val episodesElement = root["episodes"]
            val groups: List<Pair<String, JsonArray>> =
                when (episodesElement) {
                    is JsonObject -> episodesElement.entries.mapNotNull { (k, v) -> (v as? JsonArray)?.let { k to it } }
                    is JsonArray -> episodesElement.mapIndexedNotNull { i, v -> (v as? JsonArray)?.let { (i + 1).toString() to it } }
                    else -> emptyList()
                }
            groups.forEach { (seasonKey, array) ->
                val episodes =
                    array.mapIndexedNotNull { index, element ->
                        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                        val id = obj.str("id")?.toLongOrNull() ?: return@mapIndexedNotNull null
                        val epInfo = obj["info"] as? JsonObject
                        val season = obj.str("season")?.toIntOrNull() ?: seasonKey.toIntOrNull() ?: 1
                        XtreamEpisode(
                            id = id,
                            season = season,
                            episode = obj.str("episode_num")?.toIntOrNull() ?: (index + 1),
                            title = obj.str("title") ?: "Episode ${index + 1}",
                            plot = epInfo?.str("plot"),
                            durationSecs = epInfo?.str("duration_secs")?.toLongOrNull(),
                            image = epInfo?.str("movie_image"),
                            extension = obj.str("container_extension") ?: "mp4",
                        )
                    }
                episodes.groupBy { it.season }.forEach { (season, list) ->
                    seasons[season] = (seasons[season].orEmpty() + list).sortedBy { it.episode }
                }
            }
            return XtreamSeriesInfo(
                plot = info?.str("plot"),
                cast = info?.str("cast"),
                genre = info?.str("genre"),
                releaseDate = info?.str("releaseDate") ?: info?.str("release_date"),
                backdrop = info?.firstString("backdrop_path"),
                tmdbId = info?.str("tmdb") ?: info?.str("tmdb_id"),
                seasons = seasons,
            )
        }

        private suspend fun callWith(
            login: XtreamLogin,
            action: String,
            param: String,
            value: String,
        ): JsonElement =
            withContext(Dispatchers.IO) {
                val url =
                    baseUrl(login)
                        .newBuilder()
                        .addPathSegment("player_api.php")
                        .addQueryParameter("username", login.username)
                        .addQueryParameter("password", login.password)
                        .addQueryParameter("action", action)
                        .addQueryParameter(param, value)
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw XtreamException("Server replied with error ${response.code}")
                    json.parseToJsonElement(response.body.string())
                }
            }

        fun movieUrl(
            login: XtreamLogin,
            streamId: Long,
            extension: String,
        ): String = mediaUrl(login, "movie", streamId, extension)

        fun episodeUrl(
            login: XtreamLogin,
            episodeId: Long,
            extension: String,
        ): String = mediaUrl(login, "series", episodeId, extension)

        private fun mediaUrl(
            login: XtreamLogin,
            type: String,
            id: Long,
            extension: String,
        ): String =
            baseUrl(login)
                .newBuilder()
                .addPathSegment(type)
                .addPathSegment(login.username)
                .addPathSegment(login.password)
                .addPathSegment("$id.$extension")
                .build()
                .toString()

        /** The playback URL for a live channel. "ts" works with most providers. */
        fun liveUrl(
            login: XtreamLogin,
            streamId: Long,
            extension: String = "ts",
        ): String =
            baseUrl(login)
                .newBuilder()
                .addPathSegment("live")
                .addPathSegment(login.username)
                .addPathSegment(login.password)
                .addPathSegment("$streamId.$extension")
                .build()
                .toString()

        /** The provider's full TV guide (XMLTV). */
        fun epgUrl(login: XtreamLogin): String =
            baseUrl(login)
                .newBuilder()
                .addPathSegment("xmltv.php")
                .addQueryParameter("username", login.username)
                .addQueryParameter("password", login.password)
                .build()
                .toString()

        companion object {
            const val USER_AGENT = "AxiomTV"
        }
    }

private fun JsonObject.str(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.content.takeIf { it.isNotBlank() && it != "null" }
}

/** backdrop_path can be a string or an array of strings. */
private fun JsonObject.firstString(key: String): String? =
    when (val value = this[key]) {
        is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        is JsonPrimitive -> if (value is JsonNull) null else value.content.takeIf { it.isNotBlank() && it != "null" }
        else -> null
    }
