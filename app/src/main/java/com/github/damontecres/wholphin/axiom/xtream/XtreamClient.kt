package com.github.damontecres.wholphin.axiom.xtream

import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
)

data class XtreamCategory(
    val id: String,
    val name: String,
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
            )
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
