@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.axiom.ui

import com.github.damontecres.wholphin.axiom.sleepTimerLabel
import com.github.damontecres.wholphin.axiom.SleepTimerEffect
import com.github.damontecres.wholphin.axiom.SleepTimer
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.github.damontecres.wholphin.axiom.player.Engine
import com.github.damontecres.wholphin.axiom.player.EngineChoice
import com.github.damontecres.wholphin.axiom.player.EngineMemoryStore
import com.github.damontecres.wholphin.axiom.player.HybridPlayer
import com.github.damontecres.wholphin.axiom.player.MediaRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Film / episode player on the hybrid engine (mpv by default for films and series).
 *
 * Remote, controls hidden: Left/Right skip -10s/+30s, OK/Up/Down show the controls.
 * Remote, controls showing: move between the seek bar and buttons, OK presses; on the seek
 * bar Left/Right skip. Back hides the controls, Back again leaves.
 * Touch: tap to show/hide controls, tap or drag the bar to seek.
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
    /** Poster (or channel logo for catch-up) shown bottom-left with the title */
    artwork: String? = null,
    artworkSquare: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hybrid = remember { HybridPlayer(context, engineMemory, scope) }
    var sleeping by remember { mutableStateOf(false) }
    SleepTimerEffect {
        hybrid.player?.pause()
        sleeping = true
    }
    val sleepLabel = sleepTimerLabel()
    val latestProgress by rememberUpdatedState(onProgress)

    var position by remember { mutableLongStateOf(request.startPositionMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var controlsPulse by remember { mutableIntStateOf(1) }
    var showControls by remember { mutableStateOf(true) }
    var controlsFocused by remember { mutableStateOf(false) }
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

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

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

    fun hideControls() {
        showControls = false
        runCatching { rootFocus.requestFocus() }
    }

    // Controls fade out after a few seconds without input
    LaunchedEffect(controlsPulse) {
        showControls = true
        delay(5000)
        if (!menuOpen && !tracksOpen && !scrubbing) hideControls()
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

    fun showAndFocusControls() {
        controlsPulse++
        showControls = true
        scope.launchFocus(playFocus)
    }

    fun openMenu() {
        menuSelection = choices.indexOf(hybrid.choice).coerceAtLeast(0)
        tracksOpen = false
        menuOpen = true
        controlsPulse++
        runCatching { rootFocus.requestFocus() }
    }

    fun openTracks() {
        menuOpen = false
        trackSelection = firstSelectedTrackRow(trackRows)
        tracksOpen = true
        controlsPulse++
        runCatching { rootFocus.requestFocus() }
    }

    fun playNextNow() {
        if (!advanced) {
            advanced = true
            countdown = null
            onEnded()
        }
    }

    BackHandler {
        when {
            tracksOpen -> tracksOpen = false
            menuOpen -> menuOpen = false
            showControls -> hideControls()
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (sleeping) {
                        if (event.key == Key.Back) return@onPreviewKeyEvent false
                        sleeping = false
                        hybrid.player?.play()
                        return@onPreviewKeyEvent true
                    }
                    // Media keys work everywhere
                    when (event.key) {
                        Key.MediaPlayPause -> {
                            togglePlay()
                            return@onPreviewKeyEvent true
                        }

                        Key.MediaPlay -> {
                            hybrid.player?.playWhenReady = true
                            controlsPulse++
                            return@onPreviewKeyEvent true
                        }

                        Key.MediaPause -> {
                            hybrid.player?.playWhenReady = false
                            controlsPulse++
                            return@onPreviewKeyEvent true
                        }

                        Key.MediaRewind -> {
                            seekBy(-10_000)
                            return@onPreviewKeyEvent true
                        }

                        Key.MediaFastForward -> {
                            seekBy(30_000)
                            return@onPreviewKeyEvent true
                        }
                    }
                    when {
                        countdown != null -> {
                            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                playNextNow()
                                true
                            } else {
                                false
                            }
                        }

                        tracksOpen -> {
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
                        }

                        menuOpen -> {
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
                        }

                        // A button has focus: the remote moves between buttons as normal
                        controlsFocused && showControls -> {
                            controlsPulse++
                            if (event.key == Key.Menu) {
                                openTracks()
                                true
                            } else {
                                false
                            }
                        }

                        else -> {
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    seekBy(-10_000)
                                    true
                                }

                                Key.DirectionRight -> {
                                    seekBy(30_000)
                                    true
                                }

                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown -> {
                                    showAndFocusControls()
                                    true
                                }

                                Key.Menu -> {
                                    openTracks()
                                    true
                                }

                                else -> false
                            }
                        }
                    }
                }.pointerInput(menuOpen, tracksOpen, showControls) {
                    detectTapGestures(onTap = {
                        when {
                            tracksOpen -> tracksOpen = false
                            menuOpen -> menuOpen = false
                            showControls -> hideControls()
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
                update = { subtitleView -> subtitleView.setCues(hybrid.cues) },
                modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
            )
        }

        if (hybrid.buffering && hybrid.error == null) {
            Text("Loading...", color = AxiomColors.TextMuted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        }

        hybrid.error?.let { message ->
            Text(
                "$message\nTry another engine from the engine button.",
                color = AxiomColors.Text,
                fontSize = 15.sp,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xE6141518))
                        .padding(22.dp),
            )
        }

        if (showControls || menuOpen || tracksOpen) {
            // Top: just a soft shade so the picture doesn't look cut off
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))),
            )

            // Bottom: seek bar, then the buttons
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                        .onFocusChanged { controlsFocused = it.hasFocus }
                        .padding(start = 36.dp, end = 36.dp, top = 40.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Poster + title, bottom-left (like the channel logo in live TV)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val artSize = if (artworkSquare) Modifier.size(72.dp) else Modifier.width(72.dp).height(108.dp)
                    Box(
                        modifier =
                            artSize
                                .clip(RoundedCornerShape(10.dp))
                                .background(AxiomColors.PanelHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!artwork.isNullOrBlank()) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = null,
                                contentScale = if (artworkSquare) ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (artworkSquare) 6.dp else 0.dp),
                            )
                        } else {
                            Text(request.title.take(2).uppercase(), color = AxiomColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            request.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            subtitle?.let {
                                Text(it, color = AxiomColors.TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (hybrid.videoInfo.isNotEmpty()) GlassPill(hybrid.videoInfo)
                        }
                    }
                }
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val shown = if (scrubbing && duration > 0) (scrubValue * duration).toLong() else position
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(formatTime(shown), color = Color.White, fontSize = 13.sp)
                    SeekBar(
                        fraction = if (scrubbing) scrubValue else fraction,
                        onScrub = { value ->
                            scrubbing = true
                            scrubValue = value
                            controlsPulse++
                        },
                        onScrubEnd = {
                            if (duration > 0) {
                                val target = (scrubValue * duration).toLong()
                                hybrid.player?.seekTo(target)
                                position = target
                            }
                            scrubbing = false
                        },
                        onStep = { delta -> seekBy(delta) },
                        onFocused = { controlsPulse++ },
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (duration > 0) "-${formatTime(duration - shown)}" else "", color = Color.White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f)) { }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerButton("−10", onFocused = { controlsPulse++ }) { seekBy(-10_000) }
                        PlayerButton(
                            if (playing) "❚❚" else "▶",
                            big = true,
                            onFocused = { controlsPulse++ },
                            modifier = Modifier.focusRequester(playFocus),
                        ) { togglePlay() }
                        PlayerButton("+30", onFocused = { controlsPulse++ }) { seekBy(30_000) }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        PlayerPill(sleepLabel, onFocused = { controlsPulse++ }) { SleepTimer.cycle() }
                        PlayerPill("Audio & subtitles", onFocused = { controlsPulse++ }) { openTracks() }
                        val engineLabel =
                            if (hybrid.choice == EngineChoice.AUTO) hybrid.engine.label else "${hybrid.engine.label} (fixed)"
                        PlayerPill(engineLabel, gold = true, onFocused = { controlsPulse++ }) { openMenu() }
                    }
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
                Text("UP NEXT", color = AxiomColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(nextUpLabel ?: "", color = AxiomColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Playing in $seconds s · OK to play now", color = AxiomColors.TextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailButton("Play now", primary = true) { playNextNow() }
                    DetailButton("Cancel") { onBack() }
                }
            }
        }

        if (sleeping) {
            SleepOverlay {
                sleeping = false
                hybrid.player?.play()
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

private fun CoroutineScope.launchFocus(requester: FocusRequester) {
    // Wait a frame so the controls exist before moving focus to them
    launch {
        delay(50)
        runCatching { requester.requestFocus() }
    }
}

/** A round transport button; white ring and slight zoom when the remote is on it. */
@Composable
private fun PlayerButton(
    label: String,
    big: Boolean = false,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "buttonScale")
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.size(if (big) 58.dp else 46.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        big -> AxiomColors.Gold
                        focused -> Color(0x66FFFFFF)
                        else -> Color(0x26FFFFFF)
                    },
                ).then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (big) AxiomColors.OnGold else Color.White,
            fontSize = if (big) 18.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A pill button in the player's control bar. */
@Composable
private fun PlayerPill(
    text: String,
    gold: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color =
            when {
                focused -> AxiomColors.OnGold
                gold -> AxiomColors.Gold
                else -> Color.White
            },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        focused -> AxiomColors.Gold
                        gold -> AxiomColors.GoldTint
                        else -> Color(0x26FFFFFF)
                    },
                ).onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }.clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Seek bar that works with the remote (focus it, then Left/Right skip) and touch
 * (tap or drag).
 */
@Composable
private fun SeekBar(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onStep: (Long) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier =
            modifier
                .height(28.dp)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onStep(-10_000)
                            true
                        }

                        Key.DirectionRight -> {
                            onStep(30_000)
                            true
                        }

                        else -> false
                    }
                }.focusable()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset ->
                        onScrub((offset.x / size.width).coerceIn(0f, 1f))
                        onScrubEnd()
                    })
                }.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onScrub((offset.x / size.width).coerceIn(0f, 1f)) },
                        onDragEnd = { onScrubEnd() },
                        onDragCancel = { onScrubEnd() },
                        onHorizontalDrag = { change, _ -> onScrub((change.position.x / size.width).coerceIn(0f, 1f)) },
                    )
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 8.dp else 4.dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(50))
                .background(Color(0x40FFFFFF)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(barHeight)
                .clip(RoundedCornerShape(50))
                .background(AxiomColors.Gold),
        )
        val thumb = if (focused) 18.dp else 12.dp
        Box(
            Modifier
                .offset(x = (maxWidth - thumb) * fraction.coerceIn(0f, 1f))
                .size(thumb)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .then(if (focused) Modifier.border(3.dp, AxiomColors.Gold, RoundedCornerShape(50)) else Modifier),
        )
    }
}
