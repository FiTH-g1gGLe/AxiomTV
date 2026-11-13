package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.data.ProgrammeEntity

/**
 * The quick channel drawer: a strip of channels pulled up over the full-screen video,
 * each showing what's on now. Remote: Left/Right to browse, OK to switch, Down for the
 * full guide, Up/Back to close. Touch: tap a card to switch, swipe sideways to browse.
 */
@Composable
fun ChannelDrawer(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    channels: List<ChannelEntity>,
    playing: ChannelEntity?,
    selectedIndex: Int,
    groupName: String,
    onPick: (Int) -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem((selectedIndex - 2).coerceAtLeast(0))
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE00F1012), Color(0xF20F1012))))
                .padding(top = 28.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(groupName, color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "${selectedIndex + 1} / ${channels.size}",
                color = AxiomColors.TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            GlassPill("TV guide", gold = true, onClick = onOpenGuide)
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.streamId }) { index, channel ->
                DrawerCard(
                    viewModel = viewModel,
                    account = account,
                    channel = channel,
                    selected = index == selectedIndex,
                    isPlaying = channel.streamId == playing?.streamId,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}

@Composable
private fun DrawerCard(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    channel: ChannelEntity,
    selected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val nowNext by produceState<Pair<ProgrammeEntity?, ProgrammeEntity?>>(null to null, channel.streamId, viewModel.guideVersion) {
        val from = now - (now % (30 * 60_000L))
        val list = viewModel.programmes(account, channel, from - 6 * 60 * 60_000L, from + 8 * 60 * 60_000L).sortedBy { it.start }
        val playingNow = list.firstOrNull { it.start <= now && it.stop > now }
        value = playingNow to list.firstOrNull { it.start >= (playingNow?.stop ?: now) }
    }
    val current = nowNext.first
    val next = nowNext.second

    Column(
        modifier =
            Modifier
                .width(240.dp)
                .height(148.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) Color(0xF023262C) else Color(0xB31A1C20))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) AxiomColors.Gold else Color(0x22FFFFFF),
                    shape = RoundedCornerShape(14.dp),
                ).clickable(onClick = onClick)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(channel, 40)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    channel.name,
                    color = if (isPlaying) AxiomColors.Gold else AxiomColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isPlaying) "${channel.number} · Playing" else channel.number.toString(),
                    color = if (isPlaying) AxiomColors.Gold else AxiomColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            current?.title ?: "No guide information",
            color = if (current != null) AxiomColors.Text else AxiomColors.TextMuted,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val programme = current
        val progress =
            if (programme != null && programme.stop > programme.start) {
                ((now - programme.start).toFloat() / (programme.stop - programme.start)).coerceIn(0f, 1f)
            } else {
                0f
            }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(AxiomColors.Gold),
            )
        }
        Text(
            next?.let { "Next  ${drawerClock.format(java.util.Date(it.start))}  ${it.title}" } ?: "",
            color = AxiomColors.TextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val drawerClock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
