package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.AxiomRepository
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.data.ProgrammeEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val catchupClock = SimpleDateFormat("HH:mm", Locale.UK)
private val dayName = SimpleDateFormat("EEEE", Locale.UK)
private val dayDate = SimpleDateFormat("d MMM", Locale.UK)

/** One day in the catch-up window (midnight to midnight, local time). */
private data class CatchupDay(
    val label: String,
    val date: String,
    val start: Long,
    val end: Long,
)

private fun catchupDays(now: Long): List<CatchupDay> {
    val days = (AxiomRepository.ARCHIVE_MS / (24 * 60 * 60 * 1000L)).toInt() + 1
    return (0 until days).map { back ->
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -back)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val label =
            when (back) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> dayName.format(Date(start))
            }
        CatchupDay(label, dayDate.format(Date(start)), start, cal.timeInMillis)
    }
}

/** Catch-up: channel → day → programme. Moving onto a channel or day picks it, so Right goes straight to the next list. */
@Composable
fun CatchupScreen(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    onPlay: (ChannelEntity, ProgrammeEntity) -> Unit,
) {
    val channels by viewModel.archiveChannels.collectAsState()
    val now = remember { System.currentTimeMillis() }
    val days = remember(now) { catchupDays(now) }
    var channelIndex by remember { mutableIntStateOf(0) }
    var dayIndex by remember { mutableIntStateOf(0) }
    val channel = channels.getOrNull(channelIndex)
    val day = days[dayIndex.coerceIn(days.indices)]

    LaunchedEffect(Unit) { Backdrop.image = null }

    // Only blank the list when the channel or day changes, not when the guide refreshes in the background
    // (otherwise the list kept jumping back to the top and couldn't be scrolled while the guide was downloading)
    var shownKey by remember { mutableStateOf("") }
    val programmes by produceState<List<ProgrammeEntity>?>(null, channel?.streamId, day.start, viewModel.guideVersion) {
        val key = "${channel?.streamId}|${day.start}"
        if (key != shownKey) {
            value = null
            shownKey = key
        }
        val ch = channel ?: return@produceState
        val earliest = now - AxiomRepository.ARCHIVE_MS
        value =
            viewModel
                .programmes(account, ch, day.start, day.end)
                .filter { it.start in day.start until day.end && it.start < now && it.start >= earliest }
                .sortedByDescending { it.start }
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
        // Title on the left, day tabs along the top
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.History, contentDescription = null, tint = AxiomColors.Gold, modifier = Modifier.size(28.dp))
            Text("Catch-up", color = AxiomColors.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(18.dp))
            if (channels.isNotEmpty()) {
                // Scrolls sideways when the days don't all fit (phones)
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(days) { index, d ->
                        DayTab(d, selected = index == dayIndex) { dayIndex = index }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (channels.isEmpty()) {
            Text(
                "None of your channels offer catch-up. Channels that do show a CATCH-UP tag in the TV guide.",
                color = AxiomColors.TextMuted,
                fontSize = 17.sp,
            )
            return@Column
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Channels (narrow)
            GlassColumn(Modifier.width(250.dp)) {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(channels, key = { _, it -> it.streamId }) { index, ch ->
                        CatchupRow(selected = index == channelIndex, onFocus = { channelIndex = index }, onClick = { channelIndex = index }) {
                            ChannelLogo(ch, 34)
                            Text(
                                ch.name,
                                color = AxiomColors.Text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Programmes (wide)
            val listState = rememberLazyListState()
            GlassColumn(Modifier.weight(1f)) {
                Column {
                    Row(
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        channel?.let { ChannelLogo(it, 30) }
                        Text(channel?.name ?: "", color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${day.label} · ${day.date}", color = AxiomColors.TextMuted, fontSize = 15.sp)
                    }
                    val list = programmes
                    when {
                        list == null -> Text("Loading...", color = AxiomColors.TextMuted, modifier = Modifier.padding(18.dp))
                        list.isEmpty() ->
                            Text(
                                "No guide information for this channel on this day.",
                                color = AxiomColors.TextMuted,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(18.dp),
                            )
                        else -> {
                            LaunchedEffect(channel?.streamId, day.start) { listState.scrollToItem(0) }
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(list, key = { it.id }) { programme ->
                                    ProgrammeCard(programme, now) { channel?.let { onPlay(it, programme) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Day pill along the top: moving onto it switches day. */
@Composable
private fun DayTab(
    day: CatchupDay,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        focused -> AxiomColors.Gold
                        selected -> Color(0x33E5A00D)
                        else -> Color(0x1AFFFFFF)
                    },
                ).then(if (selected && !focused) Modifier.border(1.dp, Color(0x99E5A00D), RoundedCornerShape(50)) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onSelect()
                }.clickable(onClick = onSelect)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val colour = if (focused) AxiomColors.OnGold else if (selected) AxiomColors.Gold else AxiomColors.Text
        Text(day.label, color = colour, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Text(day.date, color = if (focused) AxiomColors.OnGold else AxiomColors.TextMuted, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun GlassColumn(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(bottom = 22.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x99101318))
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp)),
    ) { content() }
}

@Composable
private fun CatchupRow(
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        focused -> Color(0x40E5A00D)
                        selected -> Color(0x1FE5A00D)
                        else -> Color.Transparent
                    },
                ).then(if (focused) Modifier.border(2.dp, AxiomColors.Gold, RoundedCornerShape(12.dp)) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }.clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected) Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(AxiomColors.Gold))
        content()
    }
}

@Composable
private fun ProgrammeCard(
    programme: ProgrammeEntity,
    now: Long,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val onNow = programme.stop > now
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .glowFocus(focused, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) Color(0xE623262C) else Color(0x66181B20))
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.width(110.dp)) {
            Text(catchupClock.format(Date(programme.start)), color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${(programme.stop - programme.start) / 60_000} min",
                color = AxiomColors.TextMuted,
                fontSize = 13.sp,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    programme.title,
                    color = if (focused) AxiomColors.Gold else AxiomColors.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onNow) {
                    Text(
                        "ON NOW · FROM START",
                        color = AxiomColors.OnGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AxiomColors.Gold).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            programme.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AxiomColors.TextMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = "Play",
            tint = if (focused) AxiomColors.Gold else AxiomColors.TextMuted,
            modifier = Modifier.size(28.dp),
        )
    }
}
