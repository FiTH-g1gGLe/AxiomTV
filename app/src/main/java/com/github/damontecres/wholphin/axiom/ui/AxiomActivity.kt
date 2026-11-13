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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.axiom.AxiomLog
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

@Composable
private fun AxiomApp(viewModel: AxiomViewModel = hiltViewModel()) {
    val state by viewModel.accountState.collectAsState()

    when (val current = state) {
        AccountState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AxiomColors.Gold)
            }
        }

        AccountState.SignedOut -> {
            LoginScreen(viewModel)
        }

        is AccountState.SignedIn -> {
            LiveScreen(viewModel = viewModel, account = current.account)
        }
    }
}
