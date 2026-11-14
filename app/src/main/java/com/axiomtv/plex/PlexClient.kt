package com.axiomtv.plex

import com.axiomtv.BuildConfig
import com.axiomtv.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class PlexPin(
    val id: Long,
    val code: String,
)

data class PlexConnection(
    val uri: String,
    val local: Boolean,
    val relay: Boolean,
)

data class PlexServer(
    val id: String,
    val name: String,
    val owned: Boolean,
    val accessToken: String,
    val connections: List<PlexConnection>,
)

/** A server we've reached: where to send requests and with which token. */
data class PlexSession(
    val server: PlexServer,
    val baseUrl: String,
) {
    val token get() = server.accessToken
}

data class PlexLibrary(
    val key: String,
    val title: String,
    /** "movie" or "show" (others are ignored) */
    val type: String,
)

data class PlexMarker(
    /** "intro" or "credits" */
    val type: String,
    val startMs: Long,
    val endMs: Long,
)

data class PlexItem(
    val ratingKey: String,
    /** movie, show, season or episode */
    val type: String,
    val title: String,
    val year: Int?,
    val summary: String?,
    val thumb: String?,
    val art: String?,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val viewCount: Int,
    val leafCount: Int,
    val viewedLeafCount: Int,
    /** Episode or season number */
    val index: Int?,
    /** Season number of an episode */
    val parentIndex: Int?,
    val parentRatingKey: String?,
    val grandparentRatingKey: String?,
    val grandparentTitle: String?,
    val grandparentThumb: String?,
    val grandparentArt: String?,
    val contentRating: String?,
    val rating: Double?,
    val librarySectionId: String?,
    val partKey: String?,
    val resolution: String?,
    val addedAt: Long,
) {
    val watched get() = if (type == "show" || type == "season") leafCount > 0 && viewedLeafCount >= leafCount else viewCount > 0
    val inProgress get() = viewOffsetMs > 0 && durationMs > 0
    val progress: Float? get() = if (inProgress) (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    val episodeLabel get() = if (parentIndex != null && index != null) "S$parentIndex E$index" else null
}

/**
 * Talks to plex.tv (sign-in, finding servers) and to Plex Media Servers (libraries, playback, watched status).
 * The login itself never passes through AxiomTV: the user approves a short code at plex.tv/link.
 */
@Singleton
class PlexClient
    @Inject
    constructor(
        @param:StandardOkHttpClient okHttpClient: OkHttpClient,
    ) {
        private val client = okHttpClient.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        private val probeClient = okHttpClient.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        private val json = Json { ignoreUnknownKeys = true }

        var clientId: String = "axiomtv"

        private fun Request.Builder.plexHeaders(token: String? = null): Request.Builder =
            apply {
                header("Accept", "application/json")
                header("X-Plex-Product", "AxiomTV")
                header("X-Plex-Version", BuildConfig.VERSION_NAME)
                header("X-Plex-Client-Identifier", clientId)
                header("X-Plex-Platform", "Android")
                header("X-Plex-Device", "Android TV")
                header("X-Plex-Device-Name", "AxiomTV")
                if (token != null) header("X-Plex-Token", token)
            }

        private suspend fun call(
            request: Request,
            http: OkHttpClient = client,
        ): JsonElement? =
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Plex %s replied %d", request.url.encodedPath, response.code)
                        return@use null
                    }
                    val body = response.body.string()
                    if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
                }
            }

        // ---- plex.tv ----

        suspend fun createPin(): PlexPin? {
            val request =
                Request.Builder()
                    .url("https://plex.tv/api/v2/pins")
                    .post(FormBody.Builder().add("strong", "false").build())
                    .plexHeaders()
                    .build()
            val obj = call(request) as? JsonObject ?: return null
            return PlexPin(obj.long("id") ?: return null, obj.str("code") ?: return null)
        }

        /** The account token once the user has approved the code, else null. */
        suspend fun checkPin(id: Long): String? {
            val request = Request.Builder().url("https://plex.tv/api/v2/pins/$id").get().plexHeaders().build()
            return (call(request) as? JsonObject)?.str("authToken")
        }

        suspend fun userName(token: String): String? {
            val request = Request.Builder().url("https://plex.tv/api/v2/user").get().plexHeaders(token).build()
            val obj = call(request) as? JsonObject ?: return null
            return obj.str("friendlyName") ?: obj.str("username") ?: obj.str("title")
        }

        suspend fun servers(token: String): List<PlexServer>? {
            val request =
                Request.Builder()
                    .url("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
                    .get()
                    .plexHeaders(token)
                    .build()
            val array = call(request) as? JsonArray ?: return null
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                if (obj.str("provides")?.split(",")?.contains("server") != true) return@mapNotNull null
                PlexServer(
                    id = obj.str("clientIdentifier") ?: return@mapNotNull null,
                    name = obj.str("name") ?: "Plex server",
                    owned = obj.bool("owned"),
                    accessToken = obj.str("accessToken") ?: token,
                    connections =
                        (obj["connections"] as? JsonArray).orEmpty().mapNotNull { c ->
                            val co = c as? JsonObject ?: return@mapNotNull null
                            PlexConnection(co.str("uri") ?: return@mapNotNull null, co.bool("local"), co.bool("relay"))
                        },
                )
            }.sortedWith(compareByDescending<PlexServer> { it.owned }.thenBy { it.name.lowercase() })
        }

        /** Finds an address that answers: home network first, then the internet, then Plex's relay. */
        suspend fun connect(server: PlexServer): PlexSession? {
            val ordered =
                server.connections.sortedBy {
                    when {
                        it.local && !it.relay -> 0
                        !it.relay -> 1
                        else -> 2
                    }
                }
            for (connection in ordered) {
                val base = connection.uri.trimEnd('/')
                val ok =
                    runCatching {
                        call(Request.Builder().url("$base/identity").get().plexHeaders(server.accessToken).build(), probeClient) != null
                    }.getOrDefault(false)
                if (ok) {
                    Timber.i("Plex: %s via %s", server.name, if (connection.relay) "relay" else if (connection.local) "local" else "remote")
                    return PlexSession(server, base)
                }
            }
            return null
        }

        // ---- a server ----

        private suspend fun get(
            session: PlexSession,
            path: String,
        ): JsonObject? {
            val request = Request.Builder().url(session.baseUrl + path).get().plexHeaders(session.token).build()
            return ((call(request) as? JsonObject)?.get("MediaContainer")) as? JsonObject
        }

        suspend fun libraries(session: PlexSession): List<PlexLibrary> =
            (get(session, "/library/sections")?.get("Directory") as? JsonArray).orEmpty().mapNotNull {
                val obj = it as? JsonObject ?: return@mapNotNull null
                val type = obj.str("type")
                if (type != "movie" && type != "show") return@mapNotNull null
                PlexLibrary(obj.str("key") ?: return@mapNotNull null, obj.str("title") ?: "Library", type)
            }

        suspend fun libraryItems(
            session: PlexSession,
            key: String,
            sort: String,
        ): List<PlexItem> = items(get(session, "/library/sections/$key/all?sort=$sort&X-Plex-Container-Start=0&X-Plex-Container-Size=20000"))

        suspend fun onDeck(session: PlexSession): List<PlexItem> = items(get(session, "/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=40"))

        suspend fun recentlyAdded(
            session: PlexSession,
            key: String,
        ): List<PlexItem> = items(get(session, "/library/sections/$key/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=30"))

        suspend fun children(
            session: PlexSession,
            ratingKey: String,
        ): List<PlexItem> = items(get(session, "/library/metadata/$ratingKey/children"))

        /** Every episode of a show, in order. */
        suspend fun allEpisodes(
            session: PlexSession,
            showKey: String,
        ): List<PlexItem> = items(get(session, "/library/metadata/$showKey/allLeaves"))

        suspend fun metadata(
            session: PlexSession,
            ratingKey: String,
        ): Pair<PlexItem, List<PlexMarker>>? {
            val obj = (get(session, "/library/metadata/$ratingKey?includeMarkers=1")?.get("Metadata") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val item = parseItem(obj) ?: return null
            val markers =
                (obj["Marker"] as? JsonArray).orEmpty().mapNotNull {
                    val m = it as? JsonObject ?: return@mapNotNull null
                    PlexMarker(m.str("type") ?: return@mapNotNull null, m.long("startTimeOffset") ?: return@mapNotNull null, m.long("endTimeOffset") ?: return@mapNotNull null)
                }
            return item to markers
        }

        fun streamUrl(
            session: PlexSession,
            partKey: String,
        ): String = session.baseUrl + partKey + (if ('?' in partKey) "&" else "?") + "X-Plex-Token=" + session.token

        fun imageUrl(
            session: PlexSession,
            path: String?,
            width: Int,
            height: Int,
        ): String? {
            if (path.isNullOrBlank()) return null
            if (path.startsWith("http")) return path
            return "${session.baseUrl}/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=${enc(path)}&X-Plex-Token=${session.token}"
        }

        /** Tells the server where we are, so Continue Watching on every Plex app stays right. */
        suspend fun reportProgress(
            session: PlexSession,
            ratingKey: String,
            positionMs: Long,
            durationMs: Long,
            state: String,
        ) {
            val url =
                "${session.baseUrl}/:/timeline".toHttpUrl().newBuilder()
                    .addQueryParameter("ratingKey", ratingKey)
                    .addQueryParameter("key", "/library/metadata/$ratingKey")
                    .addQueryParameter("state", state)
                    .addQueryParameter("time", positionMs.toString())
                    .addQueryParameter("duration", durationMs.toString())
                    .build()
            runCatching { call(Request.Builder().url(url).get().plexHeaders(session.token).build()) }
        }

        suspend fun setWatched(
            session: PlexSession,
            ratingKey: String,
            watched: Boolean,
        ) {
            val action = if (watched) "scrobble" else "unscrobble"
            runCatching {
                call(
                    Request.Builder()
                        .url("${session.baseUrl}/:/$action?key=$ratingKey&identifier=com.plexapp.plugins.library")
                        .get()
                        .plexHeaders(session.token)
                        .build(),
                )
            }
        }

        private fun items(container: JsonObject?): List<PlexItem> = (container?.get("Metadata") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseItem) }

        private fun parseItem(obj: JsonObject): PlexItem? {
            val media = (obj["Media"] as? JsonArray)?.firstOrNull() as? JsonObject
            val part = (media?.get("Part") as? JsonArray)?.firstOrNull() as? JsonObject
            return PlexItem(
                ratingKey = obj.str("ratingKey") ?: return null,
                type = obj.str("type") ?: return null,
                title = obj.str("title") ?: "",
                year = obj.str("year")?.toIntOrNull(),
                summary = obj.str("summary"),
                thumb = obj.str("thumb"),
                art = obj.str("art"),
                durationMs = obj.long("duration") ?: media?.long("duration") ?: 0,
                viewOffsetMs = obj.long("viewOffset") ?: 0,
                viewCount = obj.str("viewCount")?.toIntOrNull() ?: 0,
                leafCount = obj.str("leafCount")?.toIntOrNull() ?: 0,
                viewedLeafCount = obj.str("viewedLeafCount")?.toIntOrNull() ?: 0,
                index = obj.str("index")?.toIntOrNull(),
                parentIndex = obj.str("parentIndex")?.toIntOrNull(),
                parentRatingKey = obj.str("parentRatingKey"),
                grandparentRatingKey = obj.str("grandparentRatingKey"),
                grandparentTitle = obj.str("grandparentTitle"),
                grandparentThumb = obj.str("grandparentThumb"),
                grandparentArt = obj.str("grandparentArt"),
                contentRating = obj.str("contentRating"),
                rating = (obj.str("audienceRating") ?: obj.str("rating"))?.toDoubleOrNull()?.takeIf { it > 0 },
                librarySectionId = obj.str("librarySectionID"),
                partKey = part?.str("key"),
                resolution = media?.str("videoResolution"),
                addedAt = obj.long("addedAt") ?: 0,
            )
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }

private fun JsonObject.str(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.takeIf { it.isNotBlank() && it != "null" }
}

private fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()

private fun JsonObject.bool(key: String): Boolean = str(key).let { it == "true" || it == "1" }
