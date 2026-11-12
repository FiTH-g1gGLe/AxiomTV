package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.AxiomRepository
import com.github.damontecres.wholphin.axiom.data.CategoryEntity
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.player.EngineMemoryStore
import com.github.damontecres.wholphin.axiom.player.MediaRequest
import com.github.damontecres.wholphin.axiom.xtream.XtreamException
import dagger.hilt.android.lifecycle.HiltViewModel
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
    ) : ViewModel() {
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

        fun refresh() = runBusy("Refreshing channels...") { repository.refresh() }

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
