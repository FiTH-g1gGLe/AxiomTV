package com.axiomtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiomtv.player.ErrorInfo

/**
 * Full-screen notice when the app opens and the provider account has expired, been suspended or stopped
 * accepting the login. Says plainly it's the provider, not AxiomTV, and that the AxiomTV licence is safe.
 */
@Composable
fun ProviderAccountScreen(
    problem: ErrorInfo,
    viewModel: AxiomViewModel,
) {
    val checking by viewModel.providerChecking.collectAsState()
    val state = remember { ErrorCardState() }
    val focus = remember { FocusRequester() }
    var asked by remember { mutableStateOf(false) }

    val info =
        problem.copy(
            message = problem.message + " Your AxiomTV licence is safe: you won't need to pay for AxiomTV again.",
            tips =
                when (problem.code) {
                    2002 -> listOf("Renew with your provider, then press Check again.", "Got a new login from your provider? Press Change login.")
                    2003 -> listOf("Contact your provider to find out why.", "Got a new login from your provider? Press Change login.")
                    else -> listOf("Check your username and password with your provider.", "Then press Change login and enter them again.")
                },
        )
    val actions =
        listOf(
            ErrorAction(if (checking) "Checking…" else "Check again") {
                asked = true
                viewModel.checkProviderAccount(force = true)
            },
            ErrorAction("Change login") {
                viewModel.dismissProviderProblem()
                viewModel.signOut()
            },
            ErrorAction("Continue anyway") { viewModel.dismissProviderProblem() },
            ErrorAction("Help") { state.showHelp = true },
        )

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { viewModel.dismissProviderProblem() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF1D2B55), Color(0xFF0A1024)), radius = 1600f))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown && !state.showHelp && state.handleKey(event.key, actions)
                },
        contentAlignment = Alignment.Center,
    ) {
        ErrorCard(info = info, actions = actions, state = state, modifier = Modifier.padding(32.dp))
        if (asked && !checking) {
            // Pressed Check again and the provider still says no
            Text(
                "Still not working: your provider hasn't turned the account back on yet.",
                color = Color(0xFFB9C2DA),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp),
            )
        }
    }
}

/** Small heads-up for a few seconds when the provider account ends within 3 days. */
@Composable
fun ProviderEndsSoonNote(
    endsAtMs: Long,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(endsAtMs) {
        kotlinx.coroutines.delay(10_000)
        visible = false
    }
    val days = ((endsAtMs - System.currentTimeMillis()) / (24 * 60 * 60_000L)).toInt()
    val text =
        when {
            days <= 0 -> "Your provider account ends today"
            days == 1 -> "Your provider account ends tomorrow"
            else -> "Your provider account ends in $days days"
        }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Text(
            text,
            color = Color(0xFFFFB454),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .padding(18.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(Color(0xE6141518))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
