package com.github.damontecres.wholphin.axiom.ui

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
        @param:ApplicationContext context: Context,
    ) : ViewModel() {
        private val prefs = context.getSharedPreferences("axiomtv", Context.MODE_PRIVATE)

        val accountState: StateFlow<AccountState> =
            repository.account
                .map { if (it == null) AccountState.SignedOut else AccountState.SignedIn(it) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, AccountState.Loading)

        /** null = "All channels" */
        val selectedCategory = MutableStateFlow<String?>(null)

        val categories: StateFlow<List<CategoryEntity>> =
            accountState
                .flatMapLatest { state ->
                    if (state is AccountState.SignedIn) repository.categories(state.account.id) else flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val channels: StateFlow<List<ChannelEntity>> =
            combine(accountState, selectedCategory) { state, category -> state to category }
                .flatMapLatest { (state, category) ->
                    if (state is AccountState.SignedIn) {
                        repository.channels(state.account.id, category)
                    } else {
                        flowOf(emptyList())
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                    val count = repository.syncGuide()
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
            }

        fun signOut() {
            viewModelScope.launch {
                selectedCategory.value = null
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

        private fun friendlyMessage(ex: Exception): String =
            when (ex) {
                is XtreamException -> ex.message ?: "Something went wrong"
                is UnknownHostException -> "Can't find that server - check the address"
                is SocketTimeoutException -> "The server took too long to answer"
                else -> ex.message ?: ex::class.java.simpleName
            }
    }
