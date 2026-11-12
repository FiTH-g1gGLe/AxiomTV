@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.axiom.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.player.EngineChoice
import com.github.damontecres.wholphin.axiom.player.EngineMemoryStore
import com.github.damontecres.wholphin.axiom.player.HybridPlayer
import com.github.damontecres.wholphin.axiom.player.MediaRequest
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Live player on the hybrid engine.
 * Remote: Up/Down or Channel +/- to change channel, OK to show info, Right or Menu for the
 * engine menu, Back to leave. Touch: tap for info, swipe up/down to change channel, tap the
 * engine pill for the engine menu.
 */
@Composable
fun LivePlayerScreen(
    channels: List<ChannelEntity>,
    startIndex: Int,
    requestFor: (ChannelEntity) -> MediaRequest,
    engineMemory: EngineMemoryStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hybrid = remember { HybridPlayer(context, engineMemory, scope) }
    DisposableEffect(hybrid) { onDispose { hybrid.release() } }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var infoPulse by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(true) }
    var showNotice by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuSelection by remember { mutableIntStateOf(0) }
    val choices = EngineChoice.entries

    val channel = channels.getOrNull(index)

    LaunchedEffect(index) {
        val current = channel ?: return@LaunchedEffect
        hybrid.play(requestFor(current))
        infoPulse++
    }

    LaunchedEffect(infoPulse) {
        showInfo = true
        delay(5000)
        showInfo = false
    }

    LaunchedEffect(hybrid.noticeCount) {
        if (hybrid.noticeCount == 0) return@LaunchedEffect
        showNotice = true
        delay(6000)
        showNotice = false
    }

    fun changeChannel(step: Int) {
        if (channels.isEmpty()) return
        menuOpen = false
        index = (index + step + channels.size) % channels.size
    }

    fun openMenu() {
        menuSelection = choices.indexOf(hybrid.choice).coerceAtLeast(0)
        menuOpen = true
    }

    fun pick(choice: EngineChoice) {
        menuOpen = false
        hybrid.chooseEngine(choice)
        infoPulse++
    }

    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = !menuOpen, onBack = onBack)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (menuOpen) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                menuSelection = (menuSelection - 1 + choices.size) % choices.size
                                true
                            }

                            Key.DirectionDown -> {
                                menuSelection = (menuSelection + 1) % choices.size
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                pick(choices[menuSelection])
                                true
                            }

                            Key.DirectionLeft -> {
                                menuOpen = false
                                true
                            }

                            else -> false
                        }
                    } else {
                        when (event.key) {
                            Key.DirectionUp, Key.ChannelUp -> {
                                changeChannel(-1)
                                true
                            }

                            Key.DirectionDown, Key.ChannelDown -> {
                                changeChannel(1)
                                true
                            }

                            Key.DirectionRight, Key.Menu -> {
                                openMenu()
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Info -> {
                                infoPulse++
                                true
                            }

                            else -> false
                        }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (menuOpen) menuOpen = false else infoPulse++
                    })
                }.pointerInput(channels.size) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > 120f) changeChannel(if (total < 0) 1 else -1)
                        },
                        onVerticalDrag = { _, dragAmount -> total += dragAmount },
                    )
                },
    ) {
        hybrid.player?.let { activePlayer ->
            PlayerSurface(
                player = activePlayer,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (hybrid.buffering && hybrid.error == null) {
            Text(
                text = "Loading...",
                color = AxiomColors.TextMuted,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        hybrid.error?.let { message ->
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xE6141518))
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(message, color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Change channel with Up/Down (or swipe), or pick an engine with Right. " +
                        "\"Copy debug log\" on the channel list has the details.",
                    color = AxiomColors.TextMuted,
                    fontSize = 15.sp,
                )
            }
        }

        if (showNotice) {
            hybrid.notice?.let { message ->
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xE6141518))
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, color = AxiomColors.Text, fontSize = 16.sp)
                }
            }
        }

        if ((showInfo || menuOpen) && channel != null) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AxiomColors.PanelHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!channel.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${channel.number}  ${channel.name}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Up/Down or swipe: channel · Right or tap engine: engine menu",
                        color = AxiomColors.TextMuted,
                        fontSize = 14.sp,
                    )
                }
                if (hybrid.videoInfo.isNotEmpty()) {
                    InfoPill(hybrid.videoInfo)
                }
                val engineLabel =
                    if (hybrid.choice == EngineChoice.AUTO) hybrid.engine.label else "${hybrid.engine.label} (fixed)"
                InfoPill(engineLabel, gold = true, onClick = { openMenu() })
            }
        }

        if (menuOpen) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 32.dp)
                        .width(320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF0141518))
                        .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "PLAYBACK ENGINE",
                    color = AxiomColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                )
                choices.forEachIndexed { i, option ->
                    val highlighted = i == menuSelection
                    val active = option == hybrid.choice
                    val subtitle =
                        when (option) {
                            EngineChoice.AUTO -> "Picks for you · now ${hybrid.engine.label}"
                            EngineChoice.EXO -> "Always use ExoPlayer here"
                            EngineChoice.MPV -> "Always use mpv here"
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (highlighted) AxiomColors.Gold else Color.Transparent)
                                .clickable { pick(option) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                option.label,
                                color = if (highlighted) AxiomColors.OnGold else AxiomColors.Text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                subtitle,
                                color = if (highlighted) AxiomColors.OnGold else AxiomColors.TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                        if (active) {
                            Text(
                                "✓",
                                color = if (highlighted) AxiomColors.OnGold else AxiomColors.Gold,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(
    text: String,
    gold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (gold) AxiomColors.GoldTint else Color(0x33FFFFFF))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (gold) AxiomColors.Gold else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
