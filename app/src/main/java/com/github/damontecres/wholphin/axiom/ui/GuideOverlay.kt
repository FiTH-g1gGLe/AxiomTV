package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.material.icons.rounded.History
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.AxiomRepository
import com.github.damontecres.wholphin.axiom.data.CategoryEntity
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.data.ProgrammeEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MINUTE = 60_000L
private const val HALF_HOUR = 30 * MINUTE
private const val WINDOW_MINUTES = 120
private const val WINDOW_MS = WINDOW_MINUTES * MINUTE
private const val LOOKAHEAD_MS = 4 * 60 * MINUTE
private val CHANNEL_COLUMN = 250.dp
private val ROW_HEIGHT = 50.dp

/** v2 colours: calm, solid darks; gold only for focus, the now line and progress. */
private object Glass {
    val Background = Color(0xFF0B0C0E)
    val Panel = Color(0xF20E0F12)
    val PanelStrong = Color(0xFA0E0F12)
    val Edge = Color(0x1FFFFFFF)
    val Past = Color(0xFF0F1114)
    val Airing = Color(0xFF1A1D21)
    val Future = Color(0xFF131518)
    val RowFocus = Color(0xFF15171B)
    val Muted = Color(0xFF858A92)
    val Soft = Color(0xFFC9CCD1)
}

/** Which part of the collapsible left menu is open. */
private enum class MenuLevel { NONE, GROUPS, MAIN }

private val MAIN_MENU = listOf("Home", "Search", "Movies", "Series", "Settings", "Update TV guide")
private val MAIN_MENU_ICONS: List<ImageVector?> =
    listOf(Icons.Filled.Home, Icons.Filled.Search, AxiomIcons.Movie, AxiomIcons.Series, Icons.Filled.Settings, AxiomIcons.Refresh)

private fun floorHalfHour(time: Long): Long = time - (time % HALF_HOUR)

private fun isOk(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * The TV guide (TiviMate-style). Programme details at the top with the live channel in a small
 * preview window, the grid below. One focus point for the whole grid keeps it fast.
 *
 * Remote: Up/Down rows, Left/Right programmes. Left on what's on now opens the channel groups;
 * Left again opens the main menu (Home, Search, Movies, Series, Settings). Right or Back closes.
 * OK plays the channel (OK again = full screen). Hold OK to favourite. Back = full screen.
 * Touch: tap a row to play, tap the playing one for full screen, hold for favourite, ☰ for menu.
 */
@Composable
fun GuideOverlay(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    channels: List<ChannelEntity>,
    categories: List<CategoryEntity>,
    selectedCategory: String?,
    playing: ChannelEntity?,
    engineLabel: String,
    videoInfo: String,
    playbackError: String?,
    onPlay: (ChannelEntity) -> Unit,
    onFullscreen: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onHome: () -> Unit,
    favouriteIds: Set<Long>,
    onToggleFavourite: (ChannelEntity) -> Unit,
    onPlayCatchup: (ChannelEntity, ProgrammeEntity) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    /** Where the preview window is on screen, so the live video can be drawn there */
    onPreviewBounds: (Rect?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.UK) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // Archive (catch-up) channels can be browsed back in time
    val earliestWindow = floorHalfHour(now - AxiomRepository.ARCHIVE_MS)
    var windowStart by remember { mutableLongStateOf(floorHalfHour(System.currentTimeMillis())) }
    if (windowStart < earliestWindow) windowStart = earliestWindow
    val windowEnd = windowStart + WINDOW_MS

    var focusedRow by remember(selectedCategory) {
        mutableIntStateOf(channels.indexOfFirst { it.streamId == playing?.streamId }.coerceAtLeast(0))
    }
    if (channels.isNotEmpty() && focusedRow > channels.lastIndex) focusedRow = channels.lastIndex
    var focusedTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focusedHasArchive = channels.getOrNull(focusedRow)?.hasArchive == true
    if (focusedTime < now && !focusedHasArchive) focusedTime = now
    if (focusedTime < now - AxiomRepository.ARCHIVE_MS) focusedTime = now - AxiomRepository.ARCHIVE_MS

    val rowsData = remember { mutableStateMapOf<Long, List<ProgrammeEntity>>() }
    LaunchedEffect(viewModel.guideVersion) { rowsData.clear() }

    var menu by remember { mutableStateOf(MenuLevel.NONE) }
    var groupSelection by remember { mutableIntStateOf(0) }
    var mainSelection by remember { mutableIntStateOf(0) }
    var okDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }
    var previewRect by remember { mutableStateOf<Rect?>(null) }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusedRow - 2).coerceAtLeast(0))

    val groupOptions: List<Pair<String?, String>> =
        remember(categories) {
            listOf<Pair<String?, String>>(null to "All channels", AxiomRepository.FAVOURITES_CATEGORY to "Favourites") +
                categories.map { it.categoryId to it.name }
        }
    val currentGroupName = groupOptions.firstOrNull { it.first == selectedCategory }?.second ?: "All channels"

    fun ensureRowVisible(row: Int) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val first = visible.first().index
        val last = visible.last().index
        if (row <= first || row >= last) {
            val visibleCount = (last - first).coerceAtLeast(3)
            val target = if (row <= first) (row - 1) else (row - visibleCount + 2)
            scope.launch { listState.scrollToItem(target.coerceAtLeast(0)) }
        }
    }

    fun moveRow(step: Int) {
        if (channels.isEmpty()) return
        focusedRow = (focusedRow + step).coerceIn(0, channels.lastIndex)
        ensureRowVisible(focusedRow)
    }

    fun keepTimeVisible(time: Long) {
        while (time >= windowStart + WINDOW_MS - HALF_HOUR / 2) windowStart += HALF_HOUR
        while (time < windowStart && windowStart > earliestWindow) windowStart -= HALF_HOUR
    }

    fun focusedProgrammes(): List<ProgrammeEntity> {
        val channel = channels.getOrNull(focusedRow) ?: return emptyList()
        return rowsData[channel.streamId].orEmpty()
    }

    fun openGroups() {
        groupSelection = groupOptions.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0)
        menu = MenuLevel.GROUPS
    }

    fun openMain() {
        if (menu == MenuLevel.NONE) {
            groupSelection = groupOptions.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0)
        }
        mainSelection = 0
        menu = MenuLevel.MAIN
    }

    fun pickGroup(index: Int) {
        viewModel.selectCategory(groupOptions[index].first)
        menu = MenuLevel.NONE
        scope.launch { listState.scrollToItem(0) }
    }

    fun runMain(index: Int) {
        menu = MenuLevel.NONE
        when (index) {
            0 -> onHome()
            1 -> onOpenSearch()
            2 -> onOpenMovies()
            3 -> onOpenSeries()
            4 -> onOpenSettings()
            5 -> viewModel.refreshGuide()
        }
    }

    fun moveRight() {
        val programmes = focusedProgrammes()
        val current = programmes.firstOrNull { focusedTime >= it.start && focusedTime < it.stop }
        val after = current?.stop ?: (focusedTime + 1)
        val next = programmes.firstOrNull { it.start >= after }
        val target = next?.start ?: (floorHalfHour(focusedTime) + HALF_HOUR)
        if (target > now + 28 * 60 * MINUTE) return
        focusedTime = target
        keepTimeVisible(target)
    }

    fun moveLeft() {
        val programmes = focusedProgrammes()
        val current = programmes.firstOrNull { focusedTime >= it.start && focusedTime < it.stop }
        val currentStart = current?.start ?: focusedTime
        val archive = channels.getOrNull(focusedRow)?.hasArchive == true
        val limit = if (archive) now - AxiomRepository.ARCHIVE_MS else now
        val previousInArchive = programmes.lastOrNull { it.stop <= currentStart && it.start >= limit }
        if (archive && previousInArchive != null) {
            focusedTime = previousInArchive.start
            keepTimeVisible(previousInArchive.start)
            return
        }
        if (currentStart <= now) {
            // On what's on now: Left opens the channel groups
            openGroups()
            return
        }
        val previous = programmes.lastOrNull { it.stop <= currentStart }
        val target = (previous?.start ?: (currentStart - HALF_HOUR)).coerceAtLeast(limit)
        focusedTime = target
        keepTimeVisible(target)
    }

    fun activate(channel: ChannelEntity) {
        if (channel.hasArchive && focusedTime < now) {
            val past = rowsData[channel.streamId]?.firstOrNull { focusedTime >= it.start && focusedTime < it.stop }
            if (past != null && past.stop <= now) {
                onPlayCatchup(channel, past)
                return
            }
        }
        if (channel.streamId == playing?.streamId) onFullscreen() else onPlay(channel)
    }

    fun toggleFavourite(channel: ChannelEntity) {
        val nowFavourite = channel.streamId !in favouriteIds
        onToggleFavourite(channel)
        Toast
            .makeText(context, if (nowFavourite) "Added ${channel.name} to favourites" else "Removed ${channel.name} from favourites", Toast.LENGTH_SHORT)
            .show()
    }

    // Back: close the menu a level at a time, otherwise go full screen
    BackHandler {
        when (menu) {
            MenuLevel.MAIN -> menu = MenuLevel.GROUPS
            MenuLevel.GROUPS -> menu = MenuLevel.NONE
            MenuLevel.NONE -> if (playing != null) onFullscreen() else onHome()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onPreviewBounds(null) } }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                // Solid background everywhere except the preview window, where the live video shows
                .drawBehind {
                    val hole = previewRect
                    if (hole == null) {
                        drawRect(Glass.Background)
                    } else {
                        clipRect(hole.left, hole.top, hole.right, hole.bottom, clipOp = ClipOp.Difference) {
                            drawRect(Glass.Background)
                        }
                    }
                }.focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    when (menu) {
                        MenuLevel.GROUPS -> {
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent isOk(event.key)
                            when {
                                event.key == Key.DirectionUp -> groupSelection = (groupSelection - 1 + groupOptions.size) % groupOptions.size
                                event.key == Key.DirectionDown -> groupSelection = (groupSelection + 1) % groupOptions.size
                                isOk(event.key) -> pickGroup(groupSelection)
                                event.key == Key.DirectionLeft || event.key == Key.Menu -> openMain()
                                event.key == Key.DirectionRight -> menu = MenuLevel.NONE
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        }

                        MenuLevel.MAIN -> {
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent isOk(event.key)
                            when {
                                event.key == Key.DirectionUp -> mainSelection = (mainSelection - 1 + MAIN_MENU.size) % MAIN_MENU.size
                                event.key == Key.DirectionDown -> mainSelection = (mainSelection + 1) % MAIN_MENU.size
                                isOk(event.key) -> runMain(mainSelection)
                                event.key == Key.DirectionRight -> menu = MenuLevel.GROUPS
                                event.key == Key.Menu -> menu = MenuLevel.NONE
                                event.key == Key.DirectionLeft -> Unit
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        }

                        MenuLevel.NONE -> {
                            // OK: short press plays, long press (held) toggles favourite
                            if (isOk(event.key)) {
                                val channel = channels.getOrNull(focusedRow)
                                if (event.type == KeyEventType.KeyDown) {
                                    val repeat = event.nativeKeyEvent.repeatCount
                                    if (repeat == 0) {
                                        okDown = true
                                        okLong = false
                                    } else if (okDown && !okLong) {
                                        okLong = true
                                        channel?.let { toggleFavourite(it) }
                                    }
                                } else if (event.type == KeyEventType.KeyUp) {
                                    if (okDown && !okLong) channel?.let { activate(it) }
                                    okDown = false
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> moveRow(-1)
                                Key.DirectionDown -> moveRow(1)
                                Key.ChannelUp, Key.PageUp -> moveRow(-8)
                                Key.ChannelDown, Key.PageDown -> moveRow(8)
                                Key.DirectionRight -> moveRight()
                                Key.DirectionLeft -> moveLeft()
                                Key.Menu -> openMain()
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        }
                    }
                },
    ) {
        val compact = maxHeight < 480.dp
        val headerHeight = if (compact) 118.dp else 200.dp
        val focusedChannel = channels.getOrNull(focusedRow)
        val focusedProgramme =
            focusedChannel?.let { ch ->
                rowsData[ch.streamId]?.firstOrNull { focusedTime >= it.start && focusedTime < it.stop }
            }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Header: programme details + small live preview ----
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .padding(start = 32.dp, end = 24.dp, top = if (compact) 10.dp else 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
                    if (focusedChannel != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelLogo(focusedChannel, if (compact) 26 else 34)
                            Text(focusedChannel.number.toString(), color = Glass.Muted, fontSize = 13.sp)
                            Text(
                                focusedChannel.name,
                                color = Glass.Soft,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (focusedChannel.streamId in favouriteIds) {
                                Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = AxiomColors.Gold, modifier = Modifier.size(16.dp))
                            }
                            if (focusedChannel.hasArchive) MiniTag("CATCH-UP")
                        }
                    }
                    Text(
                        text = focusedProgramme?.title ?: focusedChannel?.name ?: "",
                        color = Color.White,
                        fontSize = if (compact) 18.sp else 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (focusedProgramme != null) {
                        val airing = focusedProgramme.start <= now && focusedProgramme.stop > now
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "${timeFormat.format(Date(focusedProgramme.start))} – ${timeFormat.format(Date(focusedProgramme.stop))}",
                                color = Glass.Muted,
                                fontSize = 13.sp,
                            )
                            if (airing) {
                                val fraction =
                                    ((now - focusedProgramme.start).toFloat() / (focusedProgramme.stop - focusedProgramme.start)).coerceIn(0f, 1f)
                                Box(Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF24272C))) {
                                    Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(AxiomColors.Gold))
                                }
                            }
                            Text(
                                when {
                                    airing -> "${(focusedProgramme.stop - now) / MINUTE} min left"
                                    focusedProgramme.start > now -> "Starts in ${(focusedProgramme.start - now) / MINUTE} min"
                                    focusedChannel?.hasArchive == true -> "Catch-up · press OK to watch"
                                    else -> "Finished"
                                },
                                color = Glass.Muted,
                                fontSize = 13.sp,
                            )
                        }
                        if (!compact) {
                            focusedProgramme.description?.let {
                                Text(it, color = Glass.Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text(
                            if (viewModel.guideBusy) viewModel.guideStatus ?: "Loading TV guide..." else "No guide information",
                            color = Glass.Muted,
                            fontSize = 13.sp,
                        )
                    }
                }
                // Preview window: left transparent; the live video is drawn here by the Live screen
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInRoot()
                                if (bounds != previewRect) {
                                    previewRect = bounds
                                    onPreviewBounds(bounds)
                                }
                            }.pointerInput(Unit) { detectTapGestures(onTap = { onFullscreen() }) },
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "LIVE",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFC62828)).padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        val tags = listOf(videoInfo, engineLabel).filter { it.isNotEmpty() }.joinToString(" · ")
                        Text(
                            tags,
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                    playbackError?.let {
                        Text(
                            it,
                            color = AxiomColors.Error,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(6.dp),
                        )
                    }
                }
            }

            // ---- Group strip ----
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = if (compact) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "☰",
                    color = Glass.Soft,
                    fontSize = 18.sp,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { openMain() }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Text(
                    currentGroupName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openGroups() }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                val status =
                    when {
                        viewModel.busy -> viewModel.busyMessage
                        viewModel.error != null -> viewModel.error
                        viewModel.guideStatus != null -> viewModel.guideStatus
                        else -> "${channels.size} channels · ◀ groups & menu · hold OK to favourite"
                    }
                Text(
                    status ?: "",
                    color = if (viewModel.error != null) AxiomColors.Error else Glass.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${dayFormat.format(Date(now))}  ${timeFormat.format(Date(now))}", color = Glass.Soft, fontSize = 14.sp)
            }

            // ---- Ruler + grid ----
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                val gridWidth = maxWidth - CHANNEL_COLUMN
                val dpPerMinute = gridWidth.value / WINDOW_MINUTES
                val density = LocalDensity.current
                val swipeThresholdPx = with(density) { (dpPerMinute * 30).dp.toPx() }

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(windowStart, earliestWindow) {
                                var accumulated = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { accumulated = 0f },
                                    onHorizontalDrag = { _, dragAmount ->
                                        accumulated += dragAmount
                                        if (accumulated <= -swipeThresholdPx) {
                                            accumulated = 0f
                                            windowStart += HALF_HOUR
                                            if (focusedTime < windowStart) focusedTime = windowStart
                                        } else if (accumulated >= swipeThresholdPx && windowStart > earliestWindow) {
                                            accumulated = 0f
                                            windowStart -= HALF_HOUR
                                        }
                                    },
                                )
                            },
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(if (compact) 20.dp else 24.dp)) {
                        for (slot in 0 until WINDOW_MINUTES / 30) {
                            val time = windowStart + slot * HALF_HOUR
                            Text(
                                text = timeFormat.format(Date(time)),
                                color = Glass.Muted,
                                fontSize = 12.sp,
                                modifier =
                                    Modifier
                                        .offset(x = CHANNEL_COLUMN + (slot * 30 * dpPerMinute).dp + 6.dp)
                                        .align(Alignment.CenterStart),
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (channels.isEmpty()) {
                            Text(
                                text = if (viewModel.busy) "Loading channels..." else "No channels in this group",
                                color = Glass.Muted,
                                fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                itemsIndexed(channels, key = { _, channel -> channel.streamId }) { index, channel ->
                                    GuideRow(
                                        viewModel = viewModel,
                                        account = account,
                                        channel = channel,
                                        isFocusedRow = index == focusedRow && menu == MenuLevel.NONE,
                                        isPlaying = channel.streamId == playing?.streamId,
                                        isFavourite = channel.streamId in favouriteIds,
                                        focusedTime = focusedTime,
                                        windowStart = windowStart,
                                        now = now,
                                        dpPerMinute = dpPerMinute,
                                        guideVersion = viewModel.guideVersion,
                                        cached = rowsData[channel.streamId],
                                        timeFormat = timeFormat,
                                        onLoaded = { rowsData[channel.streamId] = it },
                                        onTap = { tappedTime ->
                                            focusedRow = index
                                            focusedTime =
                                                if (channel.hasArchive) {
                                                    (tappedTime ?: now).coerceAtLeast(now - AxiomRepository.ARCHIVE_MS)
                                                } else {
                                                    (tappedTime ?: now).coerceAtLeast(now)
                                                }
                                            activate(channel)
                                        },
                                        onLongPress = {
                                            focusedRow = index
                                            toggleFavourite(channel)
                                        },
                                    )
                                }
                                item { Spacer(Modifier.height(24.dp)) }
                            }

                            if (now in windowStart until windowEnd) {
                                Box(
                                    modifier =
                                        Modifier
                                            .offset(x = CHANNEL_COLUMN + (((now - windowStart) / MINUTE.toFloat()) * dpPerMinute).dp)
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(AxiomColors.Gold.copy(alpha = 0.85f)),
                                    )
                            }
                        }
                    }
                }
            }
        }

        // ---- Collapsible left menu: main menu (far left) + channel groups ----
        if (menu != MenuLevel.NONE) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000))
                        .pointerInput(Unit) { detectTapGestures(onTap = { menu = MenuLevel.NONE }) },
            )
            Row(modifier = Modifier.fillMaxHeight()) {
                if (menu == MenuLevel.MAIN) {
                    MenuColumn(
                        title = "MENU",
                        options = MAIN_MENU,
                        selection = mainSelection,
                        current = -1,
                        active = true,
                        width = 220,
                        onPick = { runMain(it) },
                        icons = MAIN_MENU_ICONS,
                    )
                }
                MenuColumn(
                    title = "CHANNEL GROUPS",
                    options = groupOptions.map { it.second },
                    icons =
                        groupOptions.map {
                            when (it.first) {
                                null -> AxiomIcons.LiveTv
                                AxiomRepository.FAVOURITES_CATEGORY -> Icons.Filled.Star
                                else -> Icons.AutoMirrored.Filled.List
                            }
                        },
                    selection = groupSelection,
                    current = groupOptions.indexOfFirst { it.first == selectedCategory },
                    active = menu == MenuLevel.GROUPS,
                    width = 320,
                    onPick = { pickGroup(it) },
                    footer = if (menu == MenuLevel.GROUPS) "◀ Menu" else null,
                )
            }
        }
    }
}

@Composable
private fun MiniTag(text: String) {
    Text(
        text,
        color = Glass.Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1C1F24))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** One column of the collapsible left menu. */
@Composable
private fun MenuColumn(
    title: String,
    options: List<String>,
    selection: Int,
    current: Int,
    active: Boolean,
    width: Int,
    onPick: (Int) -> Unit,
    footer: String? = null,
    icons: List<ImageVector?> = emptyList(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selection) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && (selection <= visible.first().index || selection >= visible.last().index)) {
            listState.scrollToItem((selection - 3).coerceAtLeast(0))
        }
    }
    Column(
        modifier =
            Modifier
                .width(width.dp)
                .fillMaxHeight()
                .background(if (active) Glass.PanelStrong else Glass.Panel)
                .padding(vertical = 18.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Glass.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(options) { index, option ->
                val highlighted = active && index == selection
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    highlighted -> Color(0xFF23262C)
                                    else -> Color.Transparent
                                },
                            ).clickable { onPick(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (highlighted) {
                        Box(Modifier.width(3.dp).height(18.dp).background(AxiomColors.Gold))
                        Spacer(Modifier.width(10.dp))
                    }
                    val textColour =
                        when {
                            highlighted -> Color.White
                            index == current -> AxiomColors.Gold
                            active -> Glass.Soft
                            else -> Glass.Muted
                        }
                    icons.getOrNull(index)?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (highlighted) AxiomColors.Gold else textColour,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        option,
                        color = textColour,
                        fontSize = 15.sp,
                        fontWeight = if (highlighted || index == current) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        footer?.let { Text(it, color = Glass.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp)) }
    }
}

@Composable
private fun GuideRow(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    channel: ChannelEntity,
    isFocusedRow: Boolean,
    isPlaying: Boolean,
    isFavourite: Boolean,
    focusedTime: Long,
    windowStart: Long,
    now: Long,
    dpPerMinute: Float,
    guideVersion: Int,
    cached: List<ProgrammeEntity>?,
    timeFormat: SimpleDateFormat,
    onLoaded: (List<ProgrammeEntity>) -> Unit,
    onTap: (Long?) -> Unit,
    onLongPress: () -> Unit,
) {
    val windowEnd = windowStart + WINDOW_MS
    val programmes by produceState(initialValue = cached.orEmpty(), channel.streamId, windowStart, guideVersion) {
        val loaded = viewModel.programmes(account, channel, windowStart, windowStart + LOOKAHEAD_MS)
        value = loaded
        onLoaded(loaded)
    }
    val density = LocalDensity.current

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .background(if (isFocusedRow) Glass.RowFocus else Color.Transparent)
                .pointerInput(channel.streamId, windowStart, dpPerMinute) {
                    detectTapGestures(onLongPress = { onLongPress() }, onTap = { offset ->
                        val columnPx = with(density) { CHANNEL_COLUMN.toPx() }
                        val minutePx = with(density) { dpPerMinute.dp.toPx() }
                        val time =
                            if (offset.x > columnPx && minutePx > 0f) {
                                windowStart + ((offset.x - columnPx) / minutePx).toLong() * MINUTE
                            } else {
                                null
                            }
                        onTap(time)
                    })
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel cell
        Row(
            modifier =
                Modifier
                    .width(CHANNEL_COLUMN)
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPlaying) AxiomColors.GoldTint else Color.Transparent)
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                channel.number.toString(),
                color = if (isPlaying) AxiomColors.Gold else AxiomColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.width(34.dp),
            )
            ChannelLogo(channel, 40)
            Text(
                channel.name,
                color = if (isPlaying) AxiomColors.Gold else AxiomColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isFavourite) Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = AxiomColors.Gold, modifier = Modifier.size(15.dp))
        }

        // Programmes
        Box(modifier = Modifier.fillMaxSize().padding(vertical = 3.dp)) {
            val visible = programmes.filter { it.stop > windowStart && it.start < windowEnd }
            if (visible.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isFocusedRow) Color(0x29E5A00D) else Glass.Future)
                            .then(if (isFocusedRow) Modifier.border(2.dp, AxiomColors.Gold, RoundedCornerShape(6.dp)) else Modifier)
                            .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (channel.epgChannelId == null) channel.name else "No guide information",
                        color = if (isFocusedRow) Color.White else AxiomColors.TextMuted,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                visible.forEach { programme ->
                    val start = maxOf(programme.start, windowStart)
                    val end = minOf(programme.stop, windowEnd)
                    val x = ((start - windowStart) / MINUTE.toFloat()) * dpPerMinute
                    val w = ((end - start) / MINUTE.toFloat()) * dpPerMinute
                    val focused = isFocusedRow && focusedTime >= programme.start && focusedTime < programme.stop
                    val airing = programme.start <= now && programme.stop > now
                    val background =
                        when {
                            focused -> Color(0x29E5A00D)
                            airing -> Glass.Airing
                            programme.stop <= now -> Glass.Past
                            else -> Glass.Future
                        }
                    val textColor = if (focused) Color.White else if (programme.stop <= now) Glass.Muted else AxiomColors.Text
                    Box(
                        modifier =
                            Modifier
                                .offset(x = x.dp)
                                .width((w - 4f).coerceAtLeast(2f).dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(background)
                                .then(if (focused) Modifier.border(2.dp, AxiomColors.Gold, RoundedCornerShape(6.dp)) else Modifier)
                                .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (channel.hasArchive && programme.stop <= now) {
                                    Icon(Icons.Rounded.History, contentDescription = "Catch-up", tint = textColor, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    programme.title,
                                    color = textColor,
                                    fontSize = 14.sp,
                                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (w > 110f) {
                                Text(
                                    "${timeFormat.format(Date(programme.start))} – ${timeFormat.format(Date(programme.stop))}",
                                    color = Glass.Muted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun GuideSourceDialog(
    currentUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AxiomColors.Panel,
        title = { Text("TV guide source", color = AxiomColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Leave empty to use your provider's guide, or paste your own XMLTV link (.xml or .xml.gz).",
                    color = AxiomColors.TextMuted,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Guide link (optional)") },
                    singleLine = true,
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AxiomColors.Gold,
                            focusedLabelColor = AxiomColors.Gold,
                            cursorColor = AxiomColors.Gold,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url.trim().ifEmpty { null }) }) {
                Text("Save", color = AxiomColors.Gold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) {
                    Text("Use provider guide", color = AxiomColors.Text)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AxiomColors.TextMuted)
                }
            }
        },
    )
}
