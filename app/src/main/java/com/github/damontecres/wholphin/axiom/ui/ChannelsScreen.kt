package com.github.damontecres.wholphin.axiom.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.AxiomLog
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.ChannelEntity

@Composable
fun ChannelsScreen(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    onPlay: (channels: List<ChannelEntity>, index: Int) -> Unit,
) {
    val categories by viewModel.categories.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val selected by viewModel.selectedCategory.collectAsState()
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text =
                    buildAnnotatedString {
                        append("Axiom")
                        withStyle(SpanStyle(color = AxiomColors.Gold)) { append("TV") }
                    },
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "Live TV · ${account.name}",
                color = AxiomColors.TextMuted,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            AxiomPillButton("Refresh", onClick = { viewModel.refresh() }, enabled = !viewModel.busy)
            AxiomPillButton("Copy debug log", onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("AxiomTV log", AxiomLog.dump()))
                Toast.makeText(context, "Debug log copied", Toast.LENGTH_SHORT).show()
            })
            AxiomPillButton("Sign out", onClick = { viewModel.signOut() })
        }

        if (viewModel.busy || viewModel.error != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (viewModel.busy) {
                    CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(viewModel.busyMessage, color = AxiomColors.TextMuted, fontSize = 14.sp)
                }
                viewModel.error?.let { Text(it, color = AxiomColors.Error, fontSize = 14.sp) }
            }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Categories
            LazyColumn(
                modifier =
                    Modifier
                        .width(260.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AxiomColors.Panel)
                        .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "__all__") {
                    FocusRow(onClick = { viewModel.selectCategory(null) }, selected = selected == null) {
                        Text("All channels", fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(categories, key = { "cat_" + it.categoryId }) { category ->
                    FocusRow(
                        onClick = { viewModel.selectCategory(category.categoryId) },
                        selected = selected == category.categoryId,
                    ) {
                        Text(category.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Channels
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (channels.isEmpty()) {
                    Text(
                        text = if (viewModel.busy) "Loading channels..." else "No channels here",
                        color = AxiomColors.TextMuted,
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(channels, key = { _, channel -> channel.streamId }) { index, channel ->
                            ChannelRow(channel = channel, onClick = { onPlay(channels, index) })
                        }
                        item { Spacer(Modifier.size(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    onClick: () -> Unit,
) {
    FocusRow(onClick = onClick) {
        Text(
            text = channel.number.toString(),
            fontSize = 15.sp,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.width(52.dp),
        )
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AxiomColors.PanelHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Text(channel.name.take(2).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = channel.name,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (channel.hasArchive) {
            Text("Catch-up", fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.7f))
        }
    }
}
