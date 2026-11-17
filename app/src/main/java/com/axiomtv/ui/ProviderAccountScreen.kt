package com.axiomtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
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

/** What the provider says about the account, from the last check (app start, or back from standby). */
data class ProviderAccount(
    val expiryMs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val status: String?,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    /** Whole days left, rounding up (ends later today = 1); null when the provider gives no end date. */
    val daysLeft: Int?
        get() = expiryMs?.let { kotlin.math.ceil((it - System.currentTimeMillis()) / 86_400_000.0).toInt().coerceAtLeast(0) }
    val endingSoon: Boolean get() = daysLeft?.let { it <= 7 } ?: false
}

private val ProviderAmber = Color(0xFFFFB454)
private val ProviderGreen = Color(0xFF6FDC8C)

private fun daysText(days: Int) =
    when (days) {
        0 -> "ends today"
        1 -> "1 day left"
        else -> "$days days left"
    }

/** Small pill in the top corner of Home: grey normally, amber in the last 7 days. */
@Composable
fun ProviderPill(
    account: ProviderAccount,
    modifier: Modifier = Modifier,
) {
    val days = account.daysLeft ?: return
    val amber = account.endingSoon
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(if (amber) Color(0x33FFB454) else Color(0x29FFFFFF))
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.Event,
            contentDescription = null,
            tint = if (amber) ProviderAmber else AxiomColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            if (amber) "Provider ${daysText(days)}" else "Provider · ${daysText(days)}",
            color = if (amber) ProviderAmber else AxiomColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Last row on Home: the provider account at a glance. OK opens Settings. */
@Composable
fun ProviderAccountCard(
    account: ProviderAccount,
    name: String,
    username: String,
    onOpen: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.03f else 1f, label = "accountCard")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val days = account.daysLeft
    val (statusText, statusColour) =
        when {
            days == 0 && account.expiryMs != null && account.expiryMs < System.currentTimeMillis() -> "Expired" to AxiomColors.Error
            account.endingSoon -> "Ends soon" to ProviderAmber
            else -> "Active" to ProviderGreen
        }
    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(top = 18.dp)) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
        ) {
            Box(Modifier.size(width = 4.dp, height = 18.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(AxiomColors.Gold))
            Text("Your provider account", color = AxiomColors.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp),
            modifier =
                Modifier
                    .padding(start = 40.dp, top = 14.dp, bottom = 14.dp, end = 40.dp)
                    .graphicsLayerScale(scale)
                    .glowFocus(focused, shape)
                    .clip(shape)
                    .background(if (focused) AxiomColors.PanelHigh else AxiomColors.Panel)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AxiomColors.GoldTint),
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = AxiomColors.Gold,
                    modifier = Modifier.size(30.dp),
                )
            }
            androidx.compose.foundation.layout.Column(modifier = Modifier.width(220.dp)) {
                Text(name, color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(username, color = AxiomColors.TextMuted, fontSize = 13.sp, maxLines = 1)
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(statusColour))
                    Text(statusText, color = statusColour, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            AccountStat(
                "Ends",
                account.expiryMs?.let { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(it)) } ?: "No end date",
                days?.let { daysText(it).replaceFirstChar { c -> c.uppercase() } },
                if (account.endingSoon) ProviderAmber else AxiomColors.TextMuted,
            )
            AccountStat(
                "Screens",
                account.maxConnections?.toString() ?: "–",
                account.activeConnections?.let { "$it in use" },
                AxiomColors.TextMuted,
            )
            Text(
                if (focused) "Press OK for settings" else "",
                color = AxiomColors.Gold,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(150.dp),
            )
        }
    }
}

@Composable
private fun AccountStat(
    label: String,
    value: String,
    sub: String?,
    subColour: Color,
) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.width(140.dp)) {
        Text(label, color = AxiomColors.TextMuted, fontSize = 13.sp)
        Text(value, color = AxiomColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        sub?.let { Text(it, color = subColour, fontSize = 13.sp, maxLines = 1) }
    }
}

private fun Modifier.graphicsLayerScale(scale: Float) =
    this.then(
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
