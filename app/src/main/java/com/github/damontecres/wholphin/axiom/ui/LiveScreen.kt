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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView
import com.github.damontecres.wholphin.axiom.player.Engine
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.player.EngineChoice
import com.github.damontecres.wholphin.axiom.player.HybridPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Live TV: one screen with the channel always playing. The TV guide floats over the
 * video as a frosted glass layer (TiviMate-style); close it for full screen.
 *
 * Full screen, remote: Up/Down or Channel +/- zap, OK opens the channel drawer, Left/Back open the
 * guide, Right/Menu engine menu.
 * Full screen, touch: swipe up/down to zap, tap for the channel drawer, tap the engine pill for the engine menu.
 */
@Composable
fun LiveScreen(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    onNavigate: (Section) -> Unit,
    onPlayCatchup: (ChannelEntity, com.github.damontecres.wholphin.axiom.data.ProgrammeEntity) -> Unit = { _, _ -> },
) {
    val channels by viewModel.channels.collectAsState()
    val favouriteIds by viewModel.favouriteChannelIds.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hybrid = remember { HybridPlayer(context, viewModel.engineMemory, scope) }
    DisposableEffect(hybrid) { onDispose { hybrid.release() } }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var playing by remember { mutableStateOf<ChannelEntity?>(null) }
    var guideOpen by remember { mutableStateOf(true) }
    var infoPulse by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuSelection by remember { mutableIntStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var trackSelection by remember { mutableIntStateOf(0) }
    val trackRows = remember(hybrid.tracks) { buildTrackRows(hybrid.audioOptions(), hybrid.subtitleOptions()) }
    var drawerIndex by remember { mutableIntStateOf(0) }
    val choices = EngineChoice.entries
    val groupName =
        if (selectedCategory == null) {
            "All channels"
        } else {
            categories.firstOrNull { it.categoryId == selectedCategory }?.name ?: "Channels"
        }

    // Start on the last channel watched (or the first one)
    LaunchedEffect(channels.isNotEmpty()) {
        if (playing == null && channels.isNotEmpty()) {
            val last = viewModel.lastChannelId
            playing = channels.firstOrNull { it.streamId == last } ?: channels.first()
        }
    }

    LaunchedEffect(playing?.streamId) {
        val channel = playing ?: return@LaunchedEffect
        viewModel.lastChannelId = channel.streamId
        hybrid.play(viewModel.liveRequest(account, channel))
        if (!guideOpen) infoPulse++
    }

    LaunchedEffect(infoPulse) {
        if (infoPulse == 0) return@LaunchedEffect
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

    fun zap(step: Int) {
        if (channels.isEmpty()) return
        menuOpen = false
        val current = channels.indexOfFirst { it.streamId == playing?.streamId }
        val next = if (current < 0) 0 else (current + step + channels.size) % channels.size
        playing = channels[next]
        infoPulse++
    }

    fun openDrawer() {
        menuOpen = false
        drawerIndex = channels.indexOfFirst { it.streamId == playing?.streamId }.coerceAtLeast(0)
        drawerOpen = true
    }

    // The drawer tucks itself away after a few seconds without input
    LaunchedEffect(drawerOpen, drawerIndex) {
        if (drawerOpen) {
            delay(8000)
            drawerOpen = false
        }
    }

    fun openMenu() {
        drawerOpen = false
        menuSelection = choices.indexOf(hybrid.choice).coerceAtLeast(0)
        menuOpen = true
    }

    fun pick(choice: EngineChoice) {
        menuOpen = false
        hybrid.chooseEngine(choice)
        infoPulse++
    }

    val engineLabel =
        if (hybrid.choice == EngineChoice.AUTO) hybrid.engine.label else "${hybrid.engine.label} (fixed)"

    fun openTracks() {
        menuOpen = false
        drawerOpen = false
        trackSelection = firstSelectedTrackRow(trackRows)
        tracksOpen = true
    }

    BackHandler(enabled = !guideOpen) {
        when {
            tracksOpen -> tracksOpen = false
            menuOpen -> menuOpen = false
            drawerOpen -> drawerOpen = false
            // Guide -> Back -> full screen -> Back -> Home
            else -> onNavigate(Section.HOME)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        hybrid.player?.let { activePlayer ->
            PlayerSurface(
                player = activePlayer,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (hybrid.engine == Engine.EXO && !guideOpen) {
            AndroidView(
                factory = { ctx ->
                    SubtitleView(ctx).apply {
                        setUserDefaultStyle()
                        setUserDefaultTextSize()
                    }
                },
                update = { view -> view.setCues(hybrid.cues) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!guideOpen) {
            FullscreenLayer(
                playing = playing,
                hybrid = hybrid,
                engineLabel = engineLabel,
                showInfo = showInfo,
                menuOpen = menuOpen,
                menuSelection = menuSelection,
                choices = choices,
                onZap = { zap(it) },
                onOpenGuide = {
                    menuOpen = false
                    drawerOpen = false
                    guideOpen = true
                },
                drawerOpen = drawerOpen,
                onOpenDrawer = { openDrawer() },
                onCloseDrawer = { drawerOpen = false },
                onDrawerMove = { step ->
                    if (channels.isNotEmpty()) drawerIndex = (drawerIndex + step).coerceIn(0, channels.lastIndex)
                },
                onDrawerPick = {
                    channels.getOrNull(drawerIndex)?.let { channel ->
                        if (channel.streamId == playing?.streamId) drawerOpen = false else playing = channel
                    }
                },
                tracksOpen = tracksOpen,
                trackRows = trackRows,
                trackSelection = trackSelection,
                onOpenTracks = { openTracks() },
                onCloseTracks = { tracksOpen = false },
                onTrackMove = { step -> trackSelection = moveTrackSelection(trackRows, trackSelection, step) },
                onTrackPick = { option -> hybrid.selectTrack(option) },
                drawer = {
                    ChannelDrawer(
                        viewModel = viewModel,
                        account = account,
                        channels = channels,
                        playing = playing,
                        selectedIndex = drawerIndex,
                        groupName = groupName,
                        onPick = { index ->
                            drawerIndex = index
                            channels.getOrNull(index)?.let { channel ->
                                if (channel.streamId == playing?.streamId) drawerOpen = false else playing = channel
                            }
                        },
                        onOpenGuide = {
                            drawerOpen = false
                            guideOpen = true
                        },
                        modifier = it,
                    )
                },
                onOpenMenu = { openMenu() },
                onCloseMenu = { menuOpen = false },
                onMenuMove = { step -> menuSelection = (menuSelection + step + choices.size) % choices.size },
                onPick = { pick(it) },
                onShowInfo = { infoPulse++ },
            )
        } else {
            GuideOverlay(
                viewModel = viewModel,
                account = account,
                channels = channels,
                categories = categories,
                selectedCategory = selectedCategory,
                playing = playing,
                engineLabel = engineLabel,
                videoInfo = hybrid.videoInfo,
                playbackError = hybrid.error,
                onPlay = { channel -> playing = channel },
                onFullscreen = {
                    guideOpen = false
                    infoPulse++
                },
                onOpenMovies = { onNavigate(Section.MOVIES) },
                onOpenSeries = { onNavigate(Section.SERIES) },
                onHome = { onNavigate(Section.HOME) },
                favouriteIds = favouriteIds,
                onToggleFavourite = { viewModel.toggleFavouriteChannel(it) },
                onPlayCatchup = onPlayCatchup,
                onOpenSettings = { onNavigate(Section.SETTINGS) },
            )
        }

        if (showNotice) {
            hybrid.notice?.let { message ->
                Text(
                    text = message,
                    color = AxiomColors.Text,
                    fontSize = 16.sp,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 20.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xE6141518))
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun FullscreenLayer(
    playing: ChannelEntity?,
    hybrid: HybridPlayer,
    engineLabel: String,
    showInfo: Boolean,
    menuOpen: Boolean,
    menuSelection: Int,
    choices: List<EngineChoice>,
    onZap: (Int) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onMenuMove: (Int) -> Unit,
    onPick: (EngineChoice) -> Unit,
    onShowInfo: () -> Unit,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit,
    onDrawerMove: (Int) -> Unit,
    onDrawerPick: () -> Unit,
    tracksOpen: Boolean,
    trackRows: List<TrackRow>,
    trackSelection: Int,
    onOpenTracks: () -> Unit,
    onCloseTracks: () -> Unit,
    onTrackMove: (Int) -> Unit,
    onTrackPick: (com.github.damontecres.wholphin.axiom.player.TrackOption) -> Unit,
    drawer: @Composable (Modifier) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (tracksOpen) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                onTrackMove(-1)
                                true
                            }

                            Key.DirectionDown -> {
                                onTrackMove(1)
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                (trackRows.getOrNull(trackSelection) as? TrackRow.Choice)?.let { onTrackPick(it.option) }
                                true
                            }

                            Key.DirectionLeft, Key.DirectionRight, Key.Menu -> {
                                onCloseTracks()
                                true
                            }

                            else -> false
                        }
                    } else if (drawerOpen) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onDrawerMove(-1)
                                true
                            }

                            Key.DirectionRight -> {
                                onDrawerMove(1)
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                onDrawerPick()
                                true
                            }

                            Key.DirectionUp -> {
                                onCloseDrawer()
                                true
                            }

                            Key.DirectionDown -> {
                                onOpenGuide()
                                true
                            }

                            Key.ChannelUp -> {
                                onZap(-1)
                                true
                            }

                            Key.ChannelDown -> {
                                onZap(1)
                                true
                            }

                            else -> false
                        }
                    } else if (menuOpen) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                onMenuMove(-1)
                                true
                            }

                            Key.DirectionDown -> {
                                onMenuMove(1)
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                onPick(choices[menuSelection])
                                true
                            }

                            Key.DirectionLeft -> {
                                onCloseMenu()
                                true
                            }

                            else -> false
                        }
                    } else {
                        when (event.key) {
                            Key.DirectionUp, Key.ChannelUp -> {
                                onZap(-1)
                                true
                            }

                            Key.DirectionDown, Key.ChannelDown -> {
                                onZap(1)
                                true
                            }

                            Key.DirectionRight -> {
                                onOpenMenu()
                                true
                            }

                            Key.Menu -> {
                                onOpenTracks()
                                true
                            }

                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                onOpenDrawer()
                                true
                            }

                            Key.DirectionLeft -> {
                                onOpenGuide()
                                true
                            }

                            Key.Info -> {
                                onShowInfo()
                                true
                            }

                            else -> false
                        }
                    }
                }.pointerInput(menuOpen, drawerOpen, tracksOpen) {
                    detectTapGestures(onTap = {
                        when {
                            tracksOpen -> onCloseTracks()
                            menuOpen -> onCloseMenu()
                            drawerOpen -> onCloseDrawer()
                            else -> onOpenDrawer()
                        }
                    })
                }.pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > 120f) onZap(if (total < 0) 1 else -1)
                        },
                        onVerticalDrag = { _, dragAmount -> total += dragAmount },
                    )
                },
    ) {
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
                    "Change channel with Up/Down (or swipe), or pick an engine with Right.",
                    color = AxiomColors.TextMuted,
                    fontSize = 15.sp,
                )
            }
        }

        if ((showInfo || menuOpen || tracksOpen) && !drawerOpen && playing != null) {
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
                ChannelLogo(playing, 64)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${playing.number}  ${playing.name}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "OK or tap: channels · Left: TV guide · Up/Down or swipe: zap · Right: engine · Menu: audio & subs · Back: Home",
                        color = AxiomColors.TextMuted,
                        fontSize = 14.sp,
                    )
                }
                if (hybrid.videoInfo.isNotEmpty()) GlassPill(hybrid.videoInfo)
                GlassPill("Audio & subs", onClick = onOpenTracks)
                GlassPill(engineLabel, gold = true, onClick = onOpenMenu)
            }
        }

        if (drawerOpen) {
            drawer(Modifier.align(Alignment.BottomCenter))
        }

        if (tracksOpen) {
            TrackPanel(
                rows = trackRows,
                selection = trackSelection,
                onPick = onTrackPick,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp),
            )
        }

        if (menuOpen) {
            EngineMenu(
                hybrid = hybrid,
                choices = choices,
                selection = menuSelection,
                onPick = onPick,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp),
            )
        }
    }
}

@Composable
fun EngineMenu(
    hybrid: HybridPlayer,
    choices: List<EngineChoice>,
    selection: Int,
    onPick: (EngineChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
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
            val highlighted = i == selection
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
                        .clickable { onPick(option) }
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

@Composable
fun ChannelLogo(
    channel: ChannelEntity,
    sizeDp: Int,
) {
    Box(
        modifier =
            Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape((sizeDp / 6).dp))
                .background(AxiomColors.PanelHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!channel.logo.isNullOrBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding((sizeDp / 12).dp),
            )
        } else {
            Text(channel.name.take(2).uppercase(), fontSize = (sizeDp / 4).sp, fontWeight = FontWeight.Bold, color = AxiomColors.Text)
        }
    }
}

@Composable
fun GlassPill(
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
