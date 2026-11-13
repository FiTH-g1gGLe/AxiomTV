package com.github.damontecres.wholphin.axiom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings → Channel groups: hide groups and change their order. */
@Composable
fun GroupManager(
    viewModel: AxiomViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val all by viewModel.allCategories.collectAsState()
    val prefs by viewModel.groupPrefs.prefsFlow.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(all.isNotEmpty()) { if (all.isNotEmpty()) runCatching { first.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AxiomColors.Background),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Channel groups", color = AxiomColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Hide groups you never watch (their channels leave the guide and All channels). " +
                    "Use ▲ ▼ to put the groups you use most at the top.",
                color = AxiomColors.TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                AxiomPillButton("Back", onClick = onBack)
                AxiomPillButton("Show all & reset order", onClick = { viewModel.groupPrefs.reset() })
            }
        }
        items(all, key = { it.categoryId }) { category ->
            val hidden = category.categoryId in prefs.hidden
            val index = all.indexOf(category)
            Row(
                modifier =
                    Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AxiomColors.Panel)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    category.name,
                    color = if (hidden) AxiomColors.TextMuted else AxiomColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AxiomPillButton(
                    if (hidden) "Hidden" else "Shown",
                    onClick = { viewModel.groupPrefs.toggleHidden(category.categoryId) },
                    highlighted = !hidden,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
                AxiomPillButton("▲", onClick = { viewModel.groupPrefs.move(all, category.categoryId, -1) }, enabled = index > 0)
                AxiomPillButton("▼", onClick = { viewModel.groupPrefs.move(all, category.categoryId, 1) }, enabled = index < all.lastIndex)
            }
        }
    }
}
