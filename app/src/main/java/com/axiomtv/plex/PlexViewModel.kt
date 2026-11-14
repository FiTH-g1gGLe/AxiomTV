package com.axiomtv.plex

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiomtv.library.SkipTimes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class PlexPlayback(
    val item: PlexItem,
    val request: com.axiomtv.player.MediaRequest?,
    val markers: List<PlexMarker>,
    val episodes: List<PlexItem>,
)

/**
 * Plex: linked account, servers, which libraries show and in what order.
 * Kept in its own preferences file so backups never contain the Plex token.
 */
@HiltViewModel
class PlexViewModel
    @Inject
    constructor(
        private val client: PlexClient,
        @param:ApplicationContext context: Context,
    ) : ViewModel() {
        private val prefs = context.getSharedPreferences("axiomtv_plex", Context.MODE_PRIVATE)

        init {
            client.clientId =
                prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client_id", it).apply() }
        }

        var token by mutableStateOf(prefs.getString("token", null))
            private set
        var userName by mutableStateOf(prefs.getString("user", null))
            private set
        val linked get() = token != null

        // ---- linking ----
        var linkCode by mutableStateOf<String?>(null)
            private set
        var linkError by mutableStateOf<String?>(null)
            private set
        private var linkJob: Job? = null

        fun startLink() {
            linkJob?.cancel()
            linkError = null
            linkCode = null
            linkJob =
                viewModelScope.launch {
                    val pin = runCatching { client.createPin() }.getOrNull()
                    if (pin == null) {
                        linkError = "Couldn't reach plex.tv. Check the internet connection."
                        return@launch
                    }
                    linkCode = pin.code
                    // Codes last about 15 minutes
                    repeat(450) {
                        delay(2000)
                        val authToken = runCatching { client.checkPin(pin.id) }.getOrNull()
                        if (authToken != null) {
                            prefs.edit().putString("token", authToken).apply()
                            token = authToken
                            linkCode = null
                            userName = runCatching { client.userName(authToken) }.getOrNull()?.also { prefs.edit().putString("user", it).apply() }
                            loadServers()
                            return@launch
                        }
                    }
                    linkCode = null
                    linkError = "The code expired. Get a new one."
                }
        }

        fun cancelLink() {
            linkJob?.cancel()
            linkCode = null
        }

        fun unlink() {
            prefs.edit().remove("token").remove("user").remove("server").apply()
            token = null
            userName = null
            servers = emptyList()
            session = null
            libraries = emptyList()
            onDeck = emptyList()
            recent = emptyList()
            items = emptyList()
        }

        // ---- servers ----
        var servers by mutableStateOf<List<PlexServer>>(emptyList())
            private set
        var session by mutableStateOf<PlexSession?>(null)
            private set
        var status by mutableStateOf<String?>(null)
            private set
        var loading by mutableStateOf(false)
            private set

        val currentServerId get() = session?.server?.id ?: prefs.getString("server", null)

        fun ensureLoaded() {
            if (linked && servers.isEmpty() && !loading) loadServers()
        }

        fun loadServers() {
            val t = token ?: return
            viewModelScope.launch {
                loading = true
                status = "Finding your Plex servers…"
                val found = runCatching { client.servers(t) }.getOrNull()
                if (found == null) {
                    status = "Couldn't reach plex.tv"
                    loading = false
                    return@launch
                }
                servers = found
                if (found.isEmpty()) {
                    status = "No Plex servers on this account"
                    loading = false
                    return@launch
                }
                val saved = prefs.getString("server", null)
                connect(found.firstOrNull { it.id == saved } ?: found.first())
            }
        }

        fun selectServer(server: PlexServer) {
            if (server.id == session?.server?.id) return
            viewModelScope.launch { connect(server) }
        }

        fun nextServer() {
            if (servers.size < 2) return
            val index = servers.indexOfFirst { it.id == currentServerId }
            selectServer(servers[(index + 1) % servers.size])
        }

        private suspend fun connect(server: PlexServer) {
            loading = true
            status = "Connecting to ${server.name}…"
            prefs.edit().putString("server", server.id).apply()
            val connected = runCatching { client.connect(server) }.getOrNull()
            session = connected
            libraries = emptyList()
            items = emptyList()
            onDeck = emptyList()
            recent = emptyList()
            if (connected == null) {
                status = "Can't reach ${server.name}. Is it switched on?"
                loading = false
                return
            }
            libraries = runCatching { client.libraries(connected) }.getOrDefault(emptyList())
            tab = startTab()
            status = null
            loading = false
            refresh()
        }

        // ---- libraries: shown/hidden, order, start ----
        var libraries by mutableStateOf<List<PlexLibrary>>(emptyList())
            private set

        /** Bumped whenever library prefs change so the UI recomposes. */
        var prefsVersion by mutableStateOf(0)
            private set

        private fun serverKey() = currentServerId ?: "none"

        fun isHidden(library: PlexLibrary): Boolean = prefs.getStringSet("hidden_${serverKey()}", emptySet())!!.contains(library.key)

        fun toggleHidden(library: PlexLibrary) {
            val key = "hidden_${serverKey()}"
            val set = prefs.getStringSet(key, emptySet())!!.toMutableSet()
            if (!set.add(library.key)) set.remove(library.key)
            prefs.edit().putStringSet(key, set).apply()
            prefsVersion++
            if (tab == library.key && set.contains(library.key)) tab = HOME_TAB
            refresh()
        }

        /** All libraries in the user's order. */
        fun orderedLibraries(): List<PlexLibrary> {
            if (prefsVersion < 0) return emptyList()
            val order = prefs.getString("order_${serverKey()}", "")!!.split(",").filter { it.isNotBlank() }
            return libraries.sortedBy { lib -> order.indexOf(lib.key).let { if (it < 0) Int.MAX_VALUE else it } }
        }

        fun visibleLibraries(): List<PlexLibrary> = orderedLibraries().filterNot { isHidden(it) }

        fun moveUp(library: PlexLibrary) {
            val list = orderedLibraries().toMutableList()
            val i = list.indexOfFirst { it.key == library.key }
            if (i <= 0) return
            list.add(i - 1, list.removeAt(i))
            prefs.edit().putString("order_${serverKey()}", list.joinToString(",") { it.key }).apply()
            prefsVersion++
        }

        fun startTab(): String {
            val saved = prefs.getString("start_${serverKey()}", HOME_TAB)!!
            return if (saved == HOME_TAB || visibleLibraries().any { it.key == saved }) saved else HOME_TAB
        }

        fun cycleStartTab() {
            val options = listOf(HOME_TAB) + visibleLibraries().map { it.key }
            val next = options.getOrElse(options.indexOf(startTab()) + 1) { HOME_TAB }
            prefs.edit().putString("start_${serverKey()}", next).apply()
            prefsVersion++
        }

        fun tabName(key: String): String = if (key == HOME_TAB) "Home" else libraries.firstOrNull { it.key == key }?.title ?: "Home"

        // ---- browsing ----
        var tab by mutableStateOf(HOME_TAB)
            private set
        var sortRecent by mutableStateOf(false)
            private set
        var onDeck by mutableStateOf<List<PlexItem>>(emptyList())
            private set
        var recent by mutableStateOf<List<Pair<PlexLibrary, List<PlexItem>>>>(emptyList())
            private set
        var items by mutableStateOf<List<PlexItem>>(emptyList())
            private set
        var itemsLoading by mutableStateOf(false)
            private set

        fun selectTab(key: String) {
            if (key == tab) return
            tab = key
            refresh()
        }

        fun toggleSort() {
            sortRecent = !sortRecent
            refresh()
        }

        fun refresh() {
            val s = session ?: return
            val current = tab
            viewModelScope.launch {
                itemsLoading = true
                if (current == HOME_TAB) {
                    val visible = visibleLibraries()
                    val keys = visible.map { it.key }.toSet()
                    onDeck = runCatching { client.onDeck(s) }.getOrDefault(emptyList()).filter { it.librarySectionId == null || it.librarySectionId in keys }
                    recent = visible.map { lib -> lib to runCatching { client.recentlyAdded(s, lib.key) }.getOrDefault(emptyList()) }.filter { it.second.isNotEmpty() }
                } else {
                    items = runCatching { client.libraryItems(s, current, if (sortRecent) "addedAt:desc" else "titleSort") }.getOrDefault(emptyList())
                }
                itemsLoading = false
            }
        }

        // ---- search ----
        var searchQuery by mutableStateOf("")
            private set
        var searchResults by mutableStateOf<List<PlexItem>>(emptyList())
            private set
        var searching by mutableStateOf(false)
            private set
        private var searchJob: Job? = null

        fun search(query: String) {
            searchQuery = query
            searchJob?.cancel()
            val s = session
            if (query.isBlank() || s == null) {
                searchResults = emptyList()
                searching = false
                return
            }
            searchJob =
                viewModelScope.launch {
                    searching = true
                    delay(400)
                    val keys = visibleLibraries().map { it.key }.toSet()
                    searchResults =
                        runCatching { client.search(s, query.trim()) }.getOrDefault(emptyList())
                            .filter { it.librarySectionId == null || it.librarySectionId in keys }
                            .distinctBy { it.ratingKey }
                    searching = false
                }
        }

        // ---- details & playback ----
        suspend fun children(item: PlexItem): List<PlexItem> = session?.let { s -> runCatching { client.children(s, item.ratingKey) }.getOrNull() }.orEmpty()

        suspend fun allEpisodes(showKey: String): List<PlexItem> = session?.let { s -> runCatching { client.allEpisodes(s, showKey) }.getOrNull() }.orEmpty()

        suspend fun metadata(ratingKey: String): Pair<PlexItem, List<PlexMarker>>? = session?.let { s -> runCatching { client.metadata(s, ratingKey) }.getOrNull() }

        fun image(
            path: String?,
            width: Int = 400,
            height: Int = 600,
        ): String? = session?.let { client.imageUrl(it, path, width, height) }

        fun streamUrl(partKey: String): String? = session?.let { client.streamUrl(it, partKey) }

        private var lastReport = 0L
        private val scrobbled = HashSet<String>()

        fun reportProgress(
            item: PlexItem,
            positionMs: Long,
            durationMs: Long,
        ) {
            val s = session ?: return
            if (durationMs <= 0) return
            val finished = positionMs >= durationMs * 0.9
            val now = System.currentTimeMillis()
            if (!finished && now - lastReport < 9000) return
            lastReport = now
            viewModelScope.launch {
                if (finished) {
                    if (scrobbled.add(item.ratingKey)) {
                        client.reportProgress(s, item.ratingKey, durationMs, durationMs, "stopped")
                        client.setWatched(s, item.ratingKey, true)
                    }
                } else {
                    client.reportProgress(s, item.ratingKey, positionMs, durationMs, "playing")
                }
            }
        }

        fun setWatched(
            item: PlexItem,
            watched: Boolean,
            then: () -> Unit = {},
        ) {
            val s = session ?: return
            viewModelScope.launch {
                client.setWatched(s, item.ratingKey, watched)
                then()
                refresh()
            }
        }

        /** Everything the player needs: the stream, Plex's markers and (for episodes) the whole show in order. */
        suspend fun preparePlayback(
            item: PlexItem,
            startMs: Long,
        ): PlexPlayback {
            val meta = metadata(item.ratingKey)
            val full = meta?.first ?: item
            val url = full.partKey?.let { streamUrl(it) }
            val episodes = if (full.type == "episode") full.grandparentRatingKey?.let { allEpisodes(it) }.orEmpty() else emptyList()
            val title = if (full.type == "episode") full.grandparentTitle ?: full.title else full.title
            return PlexPlayback(
                item = full,
                request = url?.let { com.axiomtv.player.MediaRequest(key = "plex:${full.ratingKey}", url = it, isLive = false, title = title, startPositionMs = startMs) },
                markers = meta?.second.orEmpty(),
                episodes = episodes,
            )
        }

        /** Plex's own intro and credits markers as skip times. */
        fun skipTimes(markers: List<PlexMarker>): SkipTimes? {
            val intro = markers.firstOrNull { it.type == "intro" }?.let { it.startMs..it.endMs }
            val credits = markers.filter { it.type == "credits" }.minByOrNull { it.startMs }?.startMs
            if (intro == null && credits == null) return null
            Timber.i("Plex markers: intro=%s credits=%s", intro, credits)
            return SkipTimes(intro = intro, creditsStartMs = credits, source = "Plex")
        }

        companion object {
            const val HOME_TAB = "__home__"
        }
    }
