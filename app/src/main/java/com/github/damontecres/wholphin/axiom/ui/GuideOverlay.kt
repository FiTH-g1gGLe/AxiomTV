package com.github.damontecres.wholphin.axiom.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.axiom.AxiomLog
import com.github.damontecres.wholphin.axiom.data.AccountEntity
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
private val CHANNEL_COLUMN = 230.dp
private val ROW_HEIGHT = 58.dp

/** Frosted glass colours: dark, see-through, with a faint light edge. */
private object Glass {
    val Panel = Color(0xD90F1012)
    val PanelStrong = Color(0xF00F1012)
    val Edge = Color(0x22FFFFFF)
    val Past = Color(0x55181A1E)
    val Airing = Color(0xB023262C)
    val Future = Color(0x8C1A1C20)
    val RowFocus = Color(0x33FFFFFF)
}

private enum class GuidePanel { NONE, GROUPS, ACTIONS }

private val ACTIONS = listOf("Refresh channels & guide", "TV guide source", "Copy debug log", "Sign out")

private fun floorHalfHour(time: Long): Long = time - (time % HALF_HOUR)

/**
 * The TV guide, drawn over the playing video. One focus point for the whole grid
 * (moved with the remote) instead of hundreds of focusable cells, which keeps it fast.
 *
 * Remote: Up/Down rows, Left/Right programmes (Left on "now" opens groups), OK plays the
 * channel (OK again = full screen), Channel +/- page, Menu (or Up on the top row) for actions.
 * Touch: tap a row to play it (tap the playing one for full screen), swipe sideways to move in time.
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.UK) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val earliestWindow = floorHalfHour(now)
    var windowStart by remember { mutableLongStateOf(floorHalfHour(System.currentTimeMillis())) }
    if (windowStart < earliestWindow) windowStart = earliestWindow
    val windowEnd = windowStart + WINDOW_MS

    var focusedRow by remember(selectedCategory) {
        mutableIntStateOf(channels.indexOfFirst { it.streamId == playing?.streamId }.coerceAtLeast(0))
    }
    if (channels.isNotEmpty() && focusedRow > channels.lastIndex) focusedRow = channels.lastIndex
    var focusedTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (focusedTime < now) focusedTime = now

    val rowsData = remember { mutableStateMapOf<Long, List<ProgrammeEntity>>() }
    LaunchedEffect(viewModel.guideVersion) { rowsData.clear() }

    var panel by remember { mutableStateOf(GuidePanel.NONE) }
    var panelSelection by remember { mutableIntStateOf(0) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var lastBackPress by remember { mutableLongStateOf(0L) }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusedRow - 2).coerceAtLeast(0))

    val groupOptions: List<Pair<String?, String>> =
        remember(categories) { listOf<Pair<String?, String>>(null to "All channels") + categories.map { it.categoryId to it.name } }
    val currentGroupName = groupOptions.firstOrNull { it.first == selectedCategory }?.second ?: "All channels"

    fun ensureRowVisible(row: Int) {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
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
        if (currentStart <= now) {
            // Already on what's on now: Left opens the channel groups
            panelSelection = groupOptions.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0)
            panel = GuidePanel.GROUPS
            return
        }
        val previous = programmes.lastOrNull { it.stop <= currentStart }
        val target = (previous?.start ?: (currentStart - HALF_HOUR)).coerceAtLeast(now)
        focusedTime = target
        keepTimeVisible(target)
    }

    fun activate(channel: ChannelEntity) {
        if (channel.streamId == playing?.streamId) onFullscreen() else onPlay(channel)
    }

    fun runAction(index: Int) {
        panel = GuidePanel.NONE
        when (index) {
            0 -> viewModel.refresh()
            1 -> showSourceDialog = true
            2 -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("AxiomTV log", AxiomLog.dump()))
                Toast.makeText(context, "Debug log copied", Toast.LENGTH_SHORT).show()
            }

            3 -> viewModel.signOut()
        }
    }

    fun openActions() {
        panelSelection = 0
        panel = GuidePanel.ACTIONS
    }

    BackHandler {
        when {
            panel != GuidePanel.NONE -> panel = GuidePanel.NONE
            System.currentTimeMillis() - lastBackPress < 2000 -> (context as? Activity)?.finish()
            else -> {
                lastBackPress = System.currentTimeMillis()
                Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(showSourceDialog) {
        if (!showSourceDialog) focusRequester.requestFocus()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (panel) {
                        GuidePanel.GROUPS, GuidePanel.ACTIONS -> {
                            val size = if (panel == GuidePanel.GROUPS) groupOptions.size else ACTIONS.size
                            when (event.key) {
                                Key.DirectionUp -> {
                                    panelSelection = (panelSelection - 1 + size) % size
                                    true
                                }

                                Key.DirectionDown -> {
                                    panelSelection = (panelSelection + 1) % size
                                    true
                                }

                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    if (panel == GuidePanel.GROUPS) {
                                        viewModel.selectCategory(groupOptions[panelSelection].first)
                                        panel = GuidePanel.NONE
                                        scope.launch { listState.scrollToItem(0) }
                                    } else {
                                        runAction(panelSelection)
                                    }
                                    true
                                }

                                Key.DirectionRight, Key.DirectionLeft, Key.Menu -> {
                                    panel = GuidePanel.NONE
                                    true
                                }

                                else -> false
                            }
                        }

                        GuidePanel.NONE -> {
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (focusedRow == 0) openActions() else moveRow(-1)
                                    true
                                }

                                Key.DirectionDown -> {
                                    moveRow(1)
                                    true
                                }

                                Key.ChannelUp, Key.PageUp -> {
                                    moveRow(-8)
                                    true
                                }

                                Key.ChannelDown, Key.PageDown -> {
                                    moveRow(8)
                                    true
                                }

                                Key.DirectionRight -> {
                                    moveRight()
                                    true
                                }

                                Key.DirectionLeft -> {
                                    moveLeft()
                                    true
                                }

                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    channels.getOrNull(focusedRow)?.let { activate(it) }
                                    true
                                }

                                Key.Menu -> {
                                    openActions()
                                    true
                                }

                                else -> false
                            }
                        }
                    }
                },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Header: programme info on the left, live video showing through on the right ----
            val focusedChannel = channels.getOrNull(focusedRow)
            val focusedProgramme =
                focusedChannel?.let { ch ->
                    rowsData[ch.streamId]?.firstOrNull { focusedTime >= it.start && focusedTime < it.stop }
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(210.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Glass.PanelStrong, Glass.Panel, Color.Transparent),
                                ),
                            ).padding(start = 28.dp, top = 22.dp, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text =
                                buildAnnotatedString {
                                    append("Axiom")
                                    withStyle(SpanStyle(color = AxiomColors.Gold)) { append("TV") }
                                },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AxiomColors.Text,
                        )
                        Text(timeFormat.format(Date(now)), color = AxiomColors.TextMuted, fontSize = 15.sp)
                    }
                    Text(
                        text = focusedChannel?.let { "${it.number} · ${it.name}" } ?: "",
                        color = AxiomColors.Gold,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = focusedProgramme?.title ?: focusedChannel?.name ?: "",
                        color = AxiomColors.Text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (focusedProgramme != null) {
                        val timing =
                            when {
                                focusedProgramme.start <= now && focusedProgramme.stop > now ->
                                    "${(focusedProgramme.stop - now) / MINUTE} min left"

                                focusedProgramme.start > now -> "Starts in ${(focusedProgramme.start - now) / MINUTE} min"
                                else -> "Finished"
                            }
                        Text(
                            text =
                                "${timeFormat.format(Date(focusedProgramme.start))} – " +
                                    "${timeFormat.format(Date(focusedProgramme.stop))} · $timing",
                            color = AxiomColors.TextMuted,
                            fontSize = 15.sp,
                        )
                        focusedProgramme.description?.let {
                            Text(
                                text = it,
                                color = AxiomColors.TextMuted,
                                fontSize = 14.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = if (viewModel.guideBusy) "Loading TV guide..." else "No guide information",
                            color = AxiomColors.TextMuted,
                            fontSize = 15.sp,
                        )
                    }
                }
                // Right side stays clear so the playing channel shows through as the preview
                Box(
                    modifier =
                        Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFD13438))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (videoInfo.isNotEmpty()) GlassPill(videoInfo)
                        GlassPill(engineLabel, gold = true)
                    }
                    playbackError?.let {
                        Text(
                            it,
                            color = AxiomColors.Error,
                            fontSize = 14.sp,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Glass.PanelStrong)
                                    .padding(10.dp),
                        )
                    }
                }
            }

            // ---- Frosted lower area: toolbar, time ruler and the grid ----
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Glass.Panel)
                        .border(width = 1.dp, color = Glass.Edge),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AxiomPillButton(text = "$currentGroupName  ▾", onClick = {
                        panelSelection = groupOptions.indexOfFirst { it.first == selectedCategory }.coerceAtLeast(0)
                        panel = GuidePanel.GROUPS
                    })
                    val status =
                        when {
                            viewModel.busy -> viewModel.busyMessage
                            viewModel.error != null -> viewModel.error
                            viewModel.guideStatus != null -> viewModel.guideStatus
                            else -> "${channels.size} channels"
                        }
                    Text(
                        text = status ?: "",
                        color = if (viewModel.error != null) AxiomColors.Error else AxiomColors.TextMuted,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AxiomPillButton(text = "Menu", onClick = { openActions() })
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
                        // Time ruler
                        Box(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                            for (slot in 0 until WINDOW_MINUTES / 30) {
                                val time = windowStart + slot * HALF_HOUR
                                Text(
                                    text = timeFormat.format(Date(time)),
                                    color = AxiomColors.TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier =
                                        Modifier
                                            .offset(x = CHANNEL_COLUMN + (slot * 30 * dpPerMinute).dp + 8.dp)
                                            .align(Alignment.CenterStart),
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            if (channels.isEmpty()) {
                                Text(
                                    text = if (viewModel.busy) "Loading channels..." else "No channels in this group",
                                    color = AxiomColors.TextMuted,
                                    fontSize = 16.sp,
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
                                            isFocusedRow = index == focusedRow,
                                            isPlaying = channel.streamId == playing?.streamId,
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
                                                focusedTime = (tappedTime ?: now).coerceAtLeast(now)
                                                activate(channel)
                                            },
                                        )
                                    }
                                    item { Spacer(Modifier.height(24.dp)) }
                                }

                                // "Now" line
                                if (now in windowStart until windowEnd) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .offset(x = CHANNEL_COLUMN + (((now - windowStart) / MINUTE.toFloat()) * dpPerMinute).dp)
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(AxiomColors.Gold),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Side panels ----
        if (panel == GuidePanel.GROUPS) {
            SidePanel(
                title = "CHANNEL GROUPS",
                options = groupOptions.map { it.second },
                selection = panelSelection,
                current = groupOptions.indexOfFirst { it.first == selectedCategory },
                onPick = { i ->
                    viewModel.selectCategory(groupOptions[i].first)
                    panel = GuidePanel.NONE
                    scope.launch { listState.scrollToItem(0) }
                },
                onDismiss = { panel = GuidePanel.NONE },
                alignEnd = false,
            )
        }
        if (panel == GuidePanel.ACTIONS) {
            SidePanel(
                title = "MENU",
                options = ACTIONS,
                selection = panelSelection,
                current = -1,
                onPick = { runAction(it) },
                onDismiss = { panel = GuidePanel.NONE },
                alignEnd = true,
            )
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
private fun GuideRow(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    channel: ChannelEntity,
    isFocusedRow: Boolean,
    isPlaying: Boolean,
    focusedTime: Long,
    windowStart: Long,
    now: Long,
    dpPerMinute: Float,
    guideVersion: Int,
    cached: List<ProgrammeEntity>?,
    timeFormat: SimpleDateFormat,
    onLoaded: (List<ProgrammeEntity>) -> Unit,
    onTap: (Long?) -> Unit,
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
                    detectTapGestures(onTap = { offset ->
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
            )
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
                            .background(if (isFocusedRow) AxiomColors.Gold else Glass.Future)
                            .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (channel.epgChannelId == null) channel.name else "No guide information",
                        color = if (isFocusedRow) AxiomColors.OnGold else AxiomColors.TextMuted,
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
                            focused -> AxiomColors.Gold
                            airing -> Glass.Airing
                            programme.stop <= now -> Glass.Past
                            else -> Glass.Future
                        }
                    val textColor = if (focused) AxiomColors.OnGold else AxiomColors.Text
                    Box(
                        modifier =
                            Modifier
                                .offset(x = x.dp)
                                .width((w - 4f).coerceAtLeast(2f).dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(background)
                                .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Text(
                                programme.title,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = if (airing || focused) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (w > 110f) {
                                Text(
                                    "${timeFormat.format(Date(programme.start))} – ${timeFormat.format(Date(programme.stop))}",
                                    color = if (focused) AxiomColors.OnGold else AxiomColors.TextMuted,
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
private fun SidePanel(
    title: String,
    options: List<String>,
    selection: Int,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    alignEnd: Boolean,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selection) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && (selection <= visible.first().index || selection >= visible.last().index)) {
            listState.scrollToItem((selection - 3).coerceAtLeast(0))
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
    ) {
        Column(
            modifier =
                Modifier
                    .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(340.dp)
                    .fillMaxHeight()
                    .background(Glass.PanelStrong)
                    .border(1.dp, Glass.Edge)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = AxiomColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(options) { index, option ->
                    val highlighted = index == selection
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        highlighted -> AxiomColors.Gold
                                        index == current -> AxiomColors.GoldTint
                                        else -> Color.Transparent
                                    },
                                ).clickable { onPick(index) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option,
                            color = if (highlighted) AxiomColors.OnGold else AxiomColors.Text,
                            fontSize = 16.sp,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (index == current) {
                            Text("✓", color = if (highlighted) AxiomColors.OnGold else AxiomColors.Gold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSourceDialog(
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
