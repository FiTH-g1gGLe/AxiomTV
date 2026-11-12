package com.github.damontecres.wholphin.axiom.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.axiom.AxiomLog
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * AxiomTV's own start screen. Replaces Wholphin's Jellyfin flow as the launcher.
 */
@AndroidEntryPoint
class AxiomActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AxiomLog.install()
        Timber.i("AxiomActivity started")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            AxiomTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AxiomColors.Background) {
                    AxiomApp()
                }
            }
        }
    }
}

private data class PlayRequest(
    val channels: List<ChannelEntity>,
    val index: Int,
)

@Composable
private fun AxiomApp(viewModel: AxiomViewModel = hiltViewModel()) {
    val state by viewModel.accountState.collectAsState()
    var playing by remember { mutableStateOf<PlayRequest?>(null) }

    when (val current = state) {
        AccountState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AxiomColors.Gold)
            }
        }

        AccountState.SignedOut -> {
            playing = null
            LoginScreen(viewModel)
        }

        is AccountState.SignedIn -> {
            val request = playing
            if (request != null) {
                LivePlayerScreen(
                    channels = request.channels,
                    startIndex = request.index,
                    requestFor = { channel -> viewModel.liveRequest(current.account, channel) },
                    engineMemory = viewModel.engineMemory,
                    onBack = { playing = null },
                )
            } else {
                ChannelsScreen(
                    viewModel = viewModel,
                    account = current.account,
                    onPlay = { channels, index -> playing = PlayRequest(channels, index) },
                )
            }
        }
    }
}
