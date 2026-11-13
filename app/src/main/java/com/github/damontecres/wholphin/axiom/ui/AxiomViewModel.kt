package com.github.damontecres.wholphin.axiom.ui

import com.github.damontecres.wholphin.axiom.GroupPrefsStore

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.AxiomRepository
import com.github.damontecres.wholphin.axiom.data.CategoryEntity
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.data.ProgrammeEntity
import com.github.damontecres.wholphin.axiom.library.FavouritesStore
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.services.DownloadCallback
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.axiom.player.EngineMemoryStore
import com.github.damontecres.wholphin.axiom.player.MediaRequest
import com.github.damontecres.wholphin.axiom.xtream.XtreamException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.NumberFormat
import timber.log.Timber
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

sealed interface AccountState {
    data object Loading : AccountState

    data object SignedOut : AccountState

    data class SignedIn(
        val account: AccountEntity,
    ) : AccountState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AxiomViewModel
    @Inject
    constructor(
        private val repository: AxiomRepository,
        val engineMemory: EngineMemoryStore,
        private val favourites: FavouritesStore,
        private val updateChecker: UpdateChecker,
        val groupPrefs: GroupPrefsStore,
        private val backups: com.github.damontecres.wholphin.axiom.BackupManager,
        @param:ApplicationContext context: Context,
    ) : ViewModel() {
        private val prefs = context.getSharedPreferences("axiomtv", Context.MODE_PRIVATE)

        val accountState: StateFlow<AccountState> =
            repository.account
                .map { if (it == null) AccountState.SignedOut else AccountState.SignedIn(it) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, AccountState.Loading)

        /** null = "All channels" */
        /** Starts on the group chosen with hold-OK in the guide (All channels if none) */
        val selectedCategory = MutableStateFlow(groupPrefs.current.startGroup)

        /** Every live group from the provider, arranged in the user's order (hidden ones included). */
        val allCategories: StateFlow<List<CategoryEntity>> =
            combine(
                accountState.flatMapLatest { state ->
                    if (state is AccountState.SignedIn) repository.categories(state.account.id) else flowOf(emptyList())
                },
                groupPrefs.prefsFlow,
            ) { list, prefs -> prefs.arrange(list) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** The groups shown in the guide: arranged, hidden ones left out. */
        val categories: StateFlow<List<CategoryEntity>> =
            combine(allCategories, groupPrefs.prefsFlow) { list, prefs -> list.filter { it.categoryId !in prefs.hidden } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val channels: StateFlow<List<ChannelEntity>> =
            combine(accountState, selectedCategory) { state, category -> state to category }
                .flatMapLatest { (state, category) ->
                    if (state is AccountState.SignedIn) {
                        repository.channels(state.account.id, category).map { category to it }
                    } else {
                        flowOf(category to emptyList<ChannelEntity>())
                    }
                }.let { flow ->
                    combine(flow, allCategories, groupPrefs.prefsFlow) { pair, all, prefs -> Triple(pair, all, prefs) }
                }.map { (pair, all, prefs) ->
                    val (category, list) = pair
                    // "All channels" leaves out hidden groups, in the user's group order
                    if (category == null && (prefs.hidden.isNotEmpty() || prefs.order.isNotEmpty())) {
                        val rank = all.withIndex().associate { it.value.categoryId to it.index }
                        list
                            .filter { it.categoryId == null || it.categoryId !in prefs.hidden }
                            .sortedBy { rank[it.categoryId] ?: Int.MAX_VALUE }
                    } else {
                        list
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Stream ids of favourite channels */
        val favouriteChannelIds: StateFlow<Set<Long>> =
            accountState
                .flatMapLatest { state ->
                    if (state is AccountState.SignedIn) favourites.ids(state.account.id, "channel") else flowOf(emptySet())
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        /** Favourite channels, for the Home screen */
        val favouriteChannels: StateFlow<List<ChannelEntity>> =
            accountState
                .flatMapLatest { state ->
                    if (state is AccountState.SignedIn) {
                        repository.channels(state.account.id, AxiomRepository.FAVOURITES_CATEGORY)
                    } else {
                        flowOf(emptyList())
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        // ---- App updates (from the AxiomTV GitHub releases) ----
        var availableUpdate by mutableStateOf<Release?>(null)
            private set
        var updateProgress by mutableStateOf<String?>(null)
            private set
        private var updateChecked = false

        /** @param onResult true if an update was found */
        fun checkForUpdate(
            force: Boolean = false,
            onResult: ((Boolean) -> Unit)? = null,
        ) {
            if ((updateChecked && !force) || BuildConfig.DEBUG) return
            updateChecked = true
            viewModelScope.launch {
                var found = false
                try {
                    val latest = updateChecker.getLatestRelease(UPDATE_URL)
                    val installed = updateChecker.getInstalledVersion()
                    if (latest != null && latest.version.isGreaterThan(installed) && latest.downloadUrl != null) {
                        Timber.i("Update available: %s (installed %s)", latest.version, installed)
                        availableUpdate = latest
                        found = true
                    }
                } catch (ex: Exception) {
                    Timber.w(ex, "Update check failed")
                }
                onResult?.invoke(found)
            }
        }

        val settings get() = engineMemory.settings

        fun forgetEngineChoices() {
            viewModelScope.launch { engineMemory.forgetAll() }
        }

        fun dismissUpdate() {
            availableUpdate = null
        }

        /** @return false if the app can't save the download (older Android without storage permission) */
        fun installUpdate(): Boolean {
            val release = availableUpdate ?: return true
            if (!updateChecker.hasPermissions()) return false
            viewModelScope.launch {
                updateProgress = "Downloading..."
                try {
                    var total = 0L
                    updateChecker.installRelease(
                        release,
                        object : DownloadCallback {
                            override fun contentLength(contentLength: Long) {
                                total = contentLength
                            }

                            override fun bytesDownloaded(bytes: Long) {
                                if (total > 0) {
                                    val percent = (bytes * 100 / total).toInt()
                                    viewModelScope.launch { updateProgress = "Downloading... $percent%" }
                                }
                            }
                        },
                    )
                    updateProgress = null
                    availableUpdate = null
                } catch (ex: Exception) {
                    Timber.e(ex, "Update install failed")
                    updateProgress = "Download failed"
                }
            }
            return true
        }

        fun toggleFavouriteChannel(channel: ChannelEntity) {
            viewModelScope.launch { favourites.toggle(channel.accountId, "channel", channel.streamId) }
        }

        var busy by mutableStateOf(false)
            private set
        var busyMessage by mutableStateOf("")
            private set
        var error by mutableStateOf<String?>(null)
            private set

        /** Guide download state, separate from channel loading */
        var guideBusy by mutableStateOf(false)
            private set
        var guideStatus by mutableStateOf<String?>(null)
            private set

        /** Bumps whenever a new guide has been stored, so the grid reloads. */
        var guideVersion by mutableIntStateOf(0)
            private set

        private val programmeCache = LruCache<String, List<ProgrammeEntity>>(600)

        init {
            // Refresh the guide in the background when it's more than 12 hours old
            viewModelScope.launch {
                accountState
                    .filterIsInstance<AccountState.SignedIn>()
                    .distinctUntilChangedBy { it.account.id }
                    .collect { state ->
                        if (repository.guideNeedsRefresh(state.account)) refreshGuide()
                    }
            }
        }

        fun signIn(
            server: String,
            username: String,
            password: String,
        ) {
            if (server.isBlank() || username.isBlank() || password.isBlank()) {
                error = "Fill in the server, username and password"
                return
            }
            runBusy("Signing in and loading channels...") {
                repository.signIn(server, username, password)
            }
        }

        fun refresh() {
            runBusy("Refreshing channels...") { repository.refresh() }
            refreshGuide()
        }

        fun refreshGuide() {
            if (guideBusy) return
            viewModelScope.launch {
                guideBusy = true
                guideStatus = "Updating TV guide..."
                try {
                    val numbers = NumberFormat.getIntegerInstance()
                    val count =
                        repository.syncGuide { stored ->
                            withContext(Dispatchers.Main) {
                                guideStatus = "Updating TV guide... ${numbers.format(stored)} programmes"
                            }
                        }
                    programmeCache.evictAll()
                    guideVersion++
                    guideStatus = if (count == 0) "No guide data found" else null
                } catch (ex: Exception) {
                    Timber.e(ex, "Guide update failed")
                    guideStatus = "TV guide couldn't be loaded"
                } finally {
                    guideBusy = false
                }
            }
        }

        /** JSON backup of favourites, progress and settings (never the login) */
        suspend fun exportBackup(): String? {
            val account = (accountState.value as? AccountState.SignedIn)?.account ?: return null
            return backups.export(account.id, recentChannelIds.value, account.epgUrl)
        }

        /** @return null when it worked, otherwise what went wrong */
        suspend fun restoreBackup(text: String): String? {
            val account = (accountState.value as? AccountState.SignedIn)?.account ?: return "Sign in first"
            return try {
                val backup = backups.restore(account.id, text)
                recentChannelIds.value = backup.recentChannels
                prefs.edit().putString("recent_channels", backup.recentChannels.joinToString(",")).apply()
                if (backup.guideUrl != account.epgUrl) setGuideUrl(backup.guideUrl)
                null
            } catch (ex: Exception) {
                Timber.w(ex, "Restore failed")
                "That file isn't an AxiomTV backup"
            }
        }

        fun setGuideUrl(url: String?) {
            viewModelScope.launch {
                repository.setGuideUrl(url)
                refreshGuide()
            }
        }

        /** Guide entries for a channel in a time window, cached while scrolling. */
        suspend fun programmes(
            account: AccountEntity,
            channel: ChannelEntity,
            from: Long,
            to: Long,
        ): List<ProgrammeEntity> {
            val epgId = channel.epgChannelId ?: return emptyList()
            val key = "${account.id}|$epgId|$from|$to"
            programmeCache.get(key)?.let { return it }
            val result = repository.programmes(account.id, epgId, from, to)
            programmeCache.put(key, result)
            return result
        }

        var lastChannelId: Long
            get() = prefs.getLong("last_stream_id", -1L)
            set(value) {
                prefs.edit().putLong("last_stream_id", value).apply()
                val updated = (listOf(value) + recentChannelIds.value.filter { it != value }).take(15)
                recentChannelIds.value = updated
                prefs.edit().putString("recent_channels", updated.joinToString(",")).apply()
            }

        private val recentChannelIds =
            MutableStateFlow(
                prefs.getString("recent_channels", "")!!.split(",").mapNotNull { it.toLongOrNull() },
            )

        fun removeRecentChannel(streamId: Long) = setRecent(recentChannelIds.value.filter { it != streamId })

        fun clearRecentChannels() = setRecent(emptyList())

        private fun setRecent(ids: List<Long>) {
            recentChannelIds.value = ids
            prefs.edit().putString("recent_channels", ids.joinToString(",")).apply()
        }

        /** Catch-up channels (hidden groups left out) for the Catch-up section */
        val archiveChannels: StateFlow<List<ChannelEntity>> =
            accountState
                .flatMapLatest { state ->
                    if (state is AccountState.SignedIn) repository.archiveChannels(state.account.id) else flowOf(emptyList())
                }.combine(groupPrefs.prefsFlow) { list, prefs -> list.filter { it.categoryId == null || it.categoryId !in prefs.hidden } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Channels watched most recently, newest first (for Home) */
        val recentChannels: StateFlow<List<ChannelEntity>> =
            accountState
                .flatMapLatest { state ->
                    if (state is AccountState.SignedIn) repository.channels(state.account.id, null) else flowOf(emptyList())
                }.combine(recentChannelIds) { all, ids ->
                    val byId = all.associateBy { it.streamId }
                    ids.mapNotNull { byId[it] }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        fun signOut() {
            viewModelScope.launch {
                selectedCategory.value = groupPrefs.current.startGroup
                repository.signOut()
            }
        }

        fun selectCategory(categoryId: String?) {
            selectedCategory.value = categoryId
        }

        fun clearError() {
            error = null
        }

        fun streamUrl(
            account: AccountEntity,
            channel: ChannelEntity,
        ): String = repository.streamUrl(account, channel)

        suspend fun catchupRequest(
            account: AccountEntity,
            channel: ChannelEntity,
            programme: ProgrammeEntity,
        ): MediaRequest =
            MediaRequest(
                key = "catchup:${account.id}:${channel.streamId}",
                url = repository.catchupUrl(account, channel, programme),
                isLive = false,
                title = programme.title,
            )

        fun liveRequest(
            account: AccountEntity,
            channel: ChannelEntity,
        ): MediaRequest =
            MediaRequest(
                key = "live:${account.id}:${channel.streamId}",
                url = repository.streamUrl(account, channel),
                isLive = true,
                title = "${channel.number} ${channel.name}",
            )

        private fun runBusy(
            message: String,
            block: suspend () -> Unit,
        ) {
            if (busy) return
            viewModelScope.launch {
                busy = true
                busyMessage = message
                error = null
                try {
                    block()
                } catch (ex: Exception) {
                    Timber.e(ex, "Action failed: %s", message)
                    error = friendlyMessage(ex)
                } finally {
                    busy = false
                }
            }
        }

        companion object {
            private const val UPDATE_URL = "https://api.github.com/repos/FiTH-g1gGLe/AxiomTV/releases/latest"
        }

        private fun friendlyMessage(ex: Exception): String =
            when (ex) {
                is XtreamException -> ex.message ?: "Something went wrong"
                is UnknownHostException -> "Can't find that server - check the address"
                is SocketTimeoutException -> "The server took too long to answer"
                else -> ex.message ?: ex::class.java.simpleName
            }
    }
