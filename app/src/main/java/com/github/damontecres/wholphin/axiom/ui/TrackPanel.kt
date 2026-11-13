package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.axiom.player.TrackOption

/** A row in the Infuse-style picker: either a section header or a choice. */
sealed interface TrackRow {
    data class Header(
        val title: String,
    ) : TrackRow

    data class Choice(
        val option: TrackOption,
    ) : TrackRow
}

fun buildTrackRows(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
): List<TrackRow> =
    buildList {
        add(TrackRow.Header("AUDIO"))
        if (audio.isEmpty()) add(TrackRow.Header("No audio tracks yet")) else audio.forEach { add(TrackRow.Choice(it)) }
        add(TrackRow.Header("SUBTITLES"))
        subtitles.forEach { add(TrackRow.Choice(it)) }
    }

/**
 * Infuse-style audio & subtitle picker. [selection] is an index into [rows] and always
 * points at a Choice.
 */
@Composable
fun TrackPanel(
    rows: List<TrackRow>,
    selection: Int,
    onPick: (TrackOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selection) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && (selection <= visible.first().index || selection >= visible.last().index)) {
            listState.scrollToItem((selection - 2).coerceAtLeast(0))
        }
    }
    Column(
        modifier =
            modifier
                .width(380.dp)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF0141518))
                .padding(14.dp),
    ) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is TrackRow.Header -> {
                        Text(
                            row.title,
                            color = AxiomColors.TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }

                    is TrackRow.Choice -> {
                        val highlighted = index == selection
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (highlighted) AxiomColors.Gold else Color.Transparent)
                                    .clickable { onPick(row.option) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.option.label,
                                    color = if (highlighted) AxiomColors.OnGold else AxiomColors.Text,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                row.option.detail?.let {
                                    Text(
                                        it,
                                        color = if (highlighted) AxiomColors.OnGold else AxiomColors.TextMuted,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (row.option.selected) {
                                Text(
                                    "✓",
                                    color = if (highlighted) AxiomColors.OnGold else AxiomColors.Gold,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Moves a selection to the next/previous Choice row, skipping headers. */
fun moveTrackSelection(
    rows: List<TrackRow>,
    current: Int,
    step: Int,
): Int {
    if (rows.none { it is TrackRow.Choice }) return current
    var index = current
    repeat(rows.size) {
        index = (index + step + rows.size) % rows.size
        if (rows[index] is TrackRow.Choice) return index
    }
    return current
}

fun firstSelectedTrackRow(rows: List<TrackRow>): Int {
    val selected = rows.indexOfFirst { it is TrackRow.Choice && it.option.selected }
    if (selected >= 0) return selected
    return rows.indexOfFirst { it is TrackRow.Choice }.coerceAtLeast(0)
}
