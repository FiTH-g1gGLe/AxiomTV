package com.github.damontecres.wholphin.axiom.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.axiom.AxiomLog
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.library.LibraryViewModel
import com.github.damontecres.wholphin.axiom.player.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: AxiomViewModel,
    library: LibraryViewModel,
    account: AccountEntity,
) {
    val context = LocalContext.current
    val settings = viewModel.settings
    var showSourceDialog by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun engineName(engine: Engine) = if (engine == Engine.EXO) "ExoPlayer" else "mpv"
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AxiomColors.Background),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text("Settings", color = AxiomColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold) }

        item { SettingsHeader("Playback") }
        item {
            SettingsRow(
                "Live TV engine",
                "${engineName(settings.liveEngine)} first",
                hint = "Which engine channels start on. The other is the backup.",
                modifier = Modifier.focusRequester(firstRow),
            ) { settings.updateLiveEngine(if (settings.liveEngine == Engine.EXO) Engine.MPV else Engine.EXO) }
        }
        item {
            SettingsRow(
                "Films & series engine",
                "${engineName(settings.vodEngine)} first",
                hint = "Which engine films, episodes and catch-up start on.",
            ) { settings.updateVodEngine(if (settings.vodEngine == Engine.MPV) Engine.EXO else Engine.MPV) }
        }
        item {
            SettingsRow(
                "Switch engine automatically",
                if (settings.autoFallback) "On" else "Off",
                hint = "If an engine can't play something, try the other one.",
            ) { settings.updateAutoFallback(!settings.autoFallback) }
        }
        item {
            SettingsRow("Forget engine choices", null, hint = "Clears the engine remembered for each channel and film.") {
                viewModel.forgetEngineChoices()
                toast("Engine choices cleared")
            }
        }

        item { SettingsHeader("Start-up") }
        item {
            SettingsRow("Open the app on", if (settings.startScreen == "live") "Live TV" else "Home") {
                settings.updateStartScreen(if (settings.startScreen == "live") "home" else "live")
            }
        }

        item { SettingsHeader("TV guide & channels") }
        item {
            SettingsRow("TV guide source", if (account.epgUrl == null) "Provider's guide" else "Custom link") {
                showSourceDialog = true
            }
        }
        item {
            val updated = if (account.lastEpgSync > 0) "Last updated ${dateFormat.format(Date(account.lastEpgSync))}" else "Not loaded yet"
            SettingsRow("Update TV guide now", viewModel.guideStatus ?: if (viewModel.guideBusy) "Updating..." else null, hint = updated) {
                viewModel.refreshGuide()
            }
        }
        item {
            SettingsRow("Refresh channel list now", if (viewModel.busy) viewModel.busyMessage else null) { viewModel.refresh() }
        }

        item { SettingsHeader("Films & series") }
        item {
            SettingsRow("Refresh films & series now", library.syncStatus ?: if (library.syncing) "Loading..." else null) { library.sync() }
        }
        item {
            SettingsRow(
                "Artwork & cast (TMDB)",
                if (library.tmdbEnabled) "On" else "Off",
                hint = if (library.tmdbEnabled) "Fanart, cast and ratings from TMDB" else "Add the TMDB_API_KEY secret on GitHub to turn this on",
            ) { }
        }

        item { SettingsHeader("Account") }
        item { SettingsRow("Server", account.name) { } }
        item {
            val expiry = account.expiry?.let { dateFormat.format(Date(it * 1000)) } ?: "Unknown"
            SettingsRow("Subscription ends", expiry) { }
        }
        item { SettingsRow("Sign out", null, hint = "You'll need your Xtream details to sign back in.") { viewModel.signOut() } }

        item { SettingsHeader("About") }
        item { SettingsRow("Version", BuildConfig.VERSION_NAME) { } }
        item {
            SettingsRow("Check for updates", null) {
                viewModel.checkForUpdate(force = true) { found -> if (!found) toast("You're on the latest version") }
            }
        }
        item {
            SettingsRow("Copy debug log", null, hint = "Paste it to Claude if something isn't working.") {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("AxiomTV log", AxiomLog.dump()))
                toast("Debug log copied")
            }
        }
    }

    if (showSourceDialog) {
        GuideSourceDialog(
            currentUrl = account.epgUrl,
            onDismiss = { showSourceDialog = false },
            onSave = { url ->
                showSourceDialog = false
                viewModel.setGuideUrl(url)
            },
        )
    }
}

@Composable
private fun SettingsHeader(title: String) {
    Text(
        title.uppercase(),
        color = AxiomColors.Gold,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    value: String?,
    hint: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) Color(0xFF23262C) else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (focused) AxiomColors.Gold else AxiomColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            hint?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 12.sp, maxLines = 2) }
        }
        value?.let {
            Text(
                it,
                color = AxiomColors.TextMuted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp).widthIn(max = 320.dp),
            )
        }
    }
}
