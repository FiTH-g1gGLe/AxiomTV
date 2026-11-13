@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.axiom.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView
import com.github.damontecres.wholphin.axiom.player.Engine
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.github.damontecres.wholphin.axiom.player.EngineChoice
import com.github.damontecres.wholphin.axiom.player.EngineMemoryStore
import com.github.damontecres.wholphin.axiom.player.HybridPlayer
import com.github.damontecres.wholphin.axiom.player.MediaRequest
import kotlinx.coroutines.delay

/**
 * Film / episode player on the hybrid engine (mpv by default for films and series).
 * Remote: OK play/pause, Left/Right -10s/+30s, Down audio & subtitles, Up show controls,
 * Menu engine menu, Back to leave.
 * Touch: tap to show/hide controls, drag the bar to seek, buttons for skip, tracks and engine.
 */
@Composable
fun VodPlayerScreen(
    request: MediaRequest,
    subtitle: String?,
    engineMemory: EngineMemoryStore,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onEnded: () -> Unit,
    onBack: () -> Unit,
    /** e.g. "S1 E3 · The Harbour"; shows a 10 second "Up next" countdown at the end */
    nextUpLabel: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hybrid = remember { HybridPlayer(context, engineMemory, scope) }
    val latestProgress by rememberUpdatedState(onProgress)

    var position by remember { mutableLongStateOf(request.startPositionMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var controlsPulse by remember { mutableIntStateOf(1) }
    var showControls by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuSelection by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var tracksOpen by remember { mutableStateOf(false) }
    var trackSelection by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val choices = EngineChoice.entries
    val trackRows = remember(hybrid.tracks) { buildTrackRows(hybrid.audioOptions(), hybrid.subtitleOptions()) }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(request.key) { hybrid.play(request) }

    // Save where we got to when leaving
    DisposableEffect(hybrid) {
        onDispose {
            hybrid.player?.let { p ->
                val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: duration
                latestProgress(p.currentPosition, d)
            }
            hybrid.release()
        }
    }

    // Track position; save every 10 seconds
    LaunchedEffect(hybrid) {
        var ticks = 0
        while (true) {
            delay(500)
            val p = hybrid.player ?: continue
            if (!scrubbing) position = p.currentPosition.coerceAtLeast(0)
            val d = p.duration
            if (d != C.TIME_UNSET && d > 0) duration = d
            playing = p.playWhenReady
            ticks++
            if (ticks % 20 == 0 && duration > 0) latestProgress(position, duration)
        }
    }

    LaunchedEffect(hybrid.ended) {
        if (hybrid.ended) {
            latestProgress(duration, duration)
            if (nextUpLabel == null) {
                onEnded()
            } else {
                for (seconds in 10 downTo 1) {
                    if (advanced) return@LaunchedEffect
                    countdown = seconds
                    delay(1000)
                }
                countdown = null
                if (!advanced) {
                    advanced = true
                    onEnded()
                }
            }
        }
    }

    LaunchedEffect(controlsPulse) {
        showControls = true
        delay(4500)
        if (!menuOpen) showControls = false
    }

    fun seekBy(deltaMs: Long) {
        val p = hybrid.player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        p.seekTo(target)
        position = target
        controlsPulse++
    }

    fun togglePlay() {
        val p = hybrid.player ?: return
        p.playWhenReady = !p.playWhenReady
        playing = p.playWhenReady
        controlsPulse++
    }

    fun openMenu() {
        menuSelection = choices.indexOf(hybrid.choice).coerceAtLeast(0)
        menuOpen = true
        controlsPulse++
    }

    fun openTracks() {
        menuOpen = false
        trackSelection = firstSelectedTrackRow(trackRows)
        tracksOpen = true
        controlsPulse++
    }

    BackHandler {
        when {
            tracksOpen -> tracksOpen = false
            menuOpen -> menuOpen = false
            else -> onBack()
        }
    }

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
                    if (countdown != null) {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (!advanced) {
                                    advanced = true
                                    countdown = null
                                    onEnded()
                                }
                                true
                            }

                            else -> false
                        }
                    } else if (tracksOpen) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                trackSelection = moveTrackSelection(trackRows, trackSelection, -1)
                                true
                            }

                            Key.DirectionDown -> {
                                trackSelection = moveTrackSelection(trackRows, trackSelection, 1)
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                (trackRows.getOrNull(trackSelection) as? TrackRow.Choice)?.let { hybrid.selectTrack(it.option) }
                                controlsPulse++
                                true
                            }

                            Key.DirectionLeft, Key.DirectionRight, Key.Menu -> {
                                tracksOpen = false
                                true
                            }

                            else -> false
                        }
                    } else if (menuOpen) {
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
                                menuOpen = false
                                hybrid.chooseEngine(choices[menuSelection])
                                true
                            }

                            Key.DirectionLeft, Key.DirectionRight, Key.Menu -> {
                                menuOpen = false
                                true
                            }

                            else -> false
                        }
                    } else {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> {
                                togglePlay()
                                true
                            }

                            Key.MediaPlay -> {
                                hybrid.player?.playWhenReady = true
                                controlsPulse++
                                true
                            }

                            Key.MediaPause -> {
                                hybrid.player?.playWhenReady = false
                                controlsPulse++
                                true
                            }

                            Key.DirectionLeft, Key.MediaRewind -> {
                                seekBy(-10_000)
                                true
                            }

                            Key.DirectionRight, Key.MediaFastForward -> {
                                seekBy(30_000)
                                true
                            }

                            Key.DirectionUp -> {
                                controlsPulse++
                                true
                            }

                            Key.DirectionDown -> {
                                openTracks()
                                true
                            }

                            Key.Menu -> {
                                openMenu()
                                true
                            }

                            else -> false
                        }
                    }
                }.pointerInput(menuOpen, tracksOpen) {
                    detectTapGestures(onTap = {
                        when {
                            tracksOpen -> tracksOpen = false
                            menuOpen -> menuOpen = false
                            showControls -> showControls = false
                            else -> controlsPulse++
                        }
                    })
                },
    ) {
        hybrid.player?.let { activePlayer ->
            PlayerSurface(
                player = activePlayer,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ExoPlayer hands us subtitle lines to draw; mpv draws its own
        if (hybrid.engine == Engine.EXO) {
            AndroidView(
                factory = { ctx ->
                    SubtitleView(ctx).apply {
                        setUserDefaultStyle()
                        setUserDefaultTextSize()
                    }
                },
                update = { view -> view.setCues(hybrid.cues) },
                modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
            )
        }

        if (hybrid.buffering && hybrid.error == null) {
            Text("Loading...", color = AxiomColors.TextMuted, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        }

        hybrid.error?.let { message ->
            Text(
                "$message\nTry another engine from the menu.",
                color = AxiomColors.Text,
                fontSize = 17.sp,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xE6141518))
                        .padding(22.dp),
            )
        }

        if (showControls || menuOpen || tracksOpen) {
            // Top bar
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 32.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        request.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (hybrid.videoInfo.isNotEmpty()) GlassPill(hybrid.videoInfo)
                GlassPill("Audio & subtitles", onClick = { openTracks() })
                val engineLabel =
                    if (hybrid.choice == EngineChoice.AUTO) hybrid.engine.label else "${hybrid.engine.label} (fixed)"
                GlassPill(engineLabel, gold = true, onClick = { openMenu() })
            }

            // Bottom bar
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Slider(
                    value = if (scrubbing) scrubValue else fraction,
                    onValueChange = {
                        scrubbing = true
                        scrubValue = it
                        controlsPulse++
                    },
                    onValueChangeFinished = {
                        if (duration > 0) {
                            val target = (scrubValue * duration).toLong()
                            hybrid.player?.seekTo(target)
                            position = target
                        }
                        scrubbing = false
                    },
                    colors =
                        SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = AxiomColors.Gold,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = if (scrubbing && duration > 0) (scrubValue * duration).toLong() else position
                    Text(formatTime(shown), color = Color.White, fontSize = 15.sp)
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundControl("-10") { seekBy(-10_000) }
                        RoundControl(if (playing) "❚❚" else "▶", big = true) { togglePlay() }
                        RoundControl("+30") { seekBy(30_000) }
                    }
                    Text(
                        if (duration > 0) "-${formatTime(duration - shown)}" else "",
                        color = Color.White,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        if (tracksOpen) {
            TrackPanel(
                rows = trackRows,
                selection = trackSelection,
                onPick = { option -> hybrid.selectTrack(option) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp),
            )
        }

        countdown?.let { seconds ->
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF0141518))
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("UP NEXT", color = AxiomColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(nextUpLabel ?: "", color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Playing in $seconds s", color = AxiomColors.TextMuted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailButton("Play now", primary = true) {
                        if (!advanced) {
                            advanced = true
                            countdown = null
                            onEnded()
                        }
                    }
                    DetailButton("Cancel") { onBack() }
                }
            }
        }

        if (menuOpen) {
            EngineMenu(
                hybrid = hybrid,
                choices = choices,
                selection = menuSelection,
                onPick = { choice ->
                    menuOpen = false
                    hybrid.chooseEngine(choice)
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp),
            )
        }
    }
}

@Composable
private fun RoundControl(
    label: String,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(horizontal = 10.dp)
                .size(if (big) 64.dp else 50.dp)
                .clip(RoundedCornerShape(50))
                .background(if (big) AxiomColors.Gold else Color(0x33FFFFFF))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (big) AxiomColors.OnGold else Color.White,
            fontSize = if (big) 20.sp else 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
