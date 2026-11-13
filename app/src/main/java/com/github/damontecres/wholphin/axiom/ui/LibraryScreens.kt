package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.data.MediaCategoryEntity

enum class Section(
    val label: String,
) {
    HOME("Home"),
    SEARCH("Search"),
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    SETTINGS("Settings"),
}

/** Collapsed left dock (Plex-style): icons with short labels, active item in gold. */
@Composable
fun NavRail(
    current: Section,
    onSelect: (Section) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    Column(
        modifier =
            Modifier
                .width(88.dp)
                .fillMaxHeight()
                .background(Color(0xFF0A0B0D))
                .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("A", color = AxiomColors.Gold, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(12.dp))
        RailItem(Icons.Filled.Search, "Search", current == Section.SEARCH) { onSelect(Section.SEARCH) }
        RailItem(Icons.Filled.Home, "Home", current == Section.HOME) { onSelect(Section.HOME) }
        RailItem(AxiomIcons.LiveTv, "Live TV", current == Section.LIVE) { onSelect(Section.LIVE) }
        RailItem(AxiomIcons.Movie, "Movies", current == Section.MOVIES) { onSelect(Section.MOVIES) }
        RailItem(AxiomIcons.Series, "Series", current == Section.SERIES) { onSelect(Section.SERIES) }
        Spacer(Modifier.weight(1f))
        RailItem(AxiomIcons.Refresh, if (refreshing) "Loading" else "Refresh", false, onClick = onRefresh)
        RailItem(Icons.Filled.Settings, "Settings", current == Section.SETTINGS) { onSelect(Section.SETTINGS) }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background =
        when {
            focused -> AxiomColors.Gold
            selected -> AxiomColors.GoldTint
            else -> Color.Transparent
        }
    val tint =
        when {
            focused -> AxiomColors.OnGold
            selected -> AxiomColors.Gold
            else -> AxiomColors.TextMuted
        }
    Column(
        modifier =
            Modifier
                .width(72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** One poster in a Plex-style grid. */
data class PosterItem(
    val key: Any,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val badge: String?,
    /** 0..1 watch progress, or null */
    val progress: Float?,
    val watched: Boolean,
)

@Composable
fun LibraryGrid(
    title: String,
    items: List<PosterItem>,
    categories: List<MediaCategoryEntity>,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    sortLabel: String,
    onCycleSort: () -> Unit,
    syncing: Boolean,
    syncStatus: String?,
    onOpen: (PosterItem) -> Unit,
    favouritesLabel: String = "★ Favourites",
) {
    var focusedPoster by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(AxiomColors.Background)) {
        // Faint, blurred backdrop of the focused poster (Plex-style crossfade)
        Crossfade(targetState = focusedPoster, label = "backdrop") { poster ->
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(40.dp).alpha(0.22f),
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0x660F1012), AxiomColors.Background))),
        )

        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = AxiomColors.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text("${items.size} titles", color = AxiomColors.TextMuted, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                if (syncing || syncStatus != null) {
                    if (syncing) CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(syncStatus ?: "", color = AxiomColors.TextMuted, fontSize = 14.sp)
                }
                AxiomPillButton("Sort: $sortLabel", onClick = onCycleSort)
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "__all__") {
                    AxiomPillButton("All", onClick = { onSelectCategory(null) }, highlighted = selectedCategory == null)
                }
                item(key = "__favourites__") {
                    val favId = com.github.damontecres.wholphin.axiom.data.AxiomRepository.FAVOURITES_CATEGORY
                    AxiomPillButton(favouritesLabel, onClick = { onSelectCategory(favId) }, highlighted = selectedCategory == favId)
                }
                items(categories, key = { it.categoryId }) { category ->
                    AxiomPillButton(
                        category.name,
                        onClick = { onSelectCategory(category.categoryId) },
                        highlighted = selectedCategory == category.categoryId,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            syncing -> "Loading your library..."
                            selectedCategory == com.github.damontecres.wholphin.axiom.data.AxiomRepository.FAVOURITES_CATEGORY ->
                                "Nothing here yet. Open a title and choose \"Add to $favouritesLabel\"".replace("★ ", "")
                            else -> "Nothing here yet"
                        },
                        color = AxiomColors.TextMuted,
                        fontSize = 16.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp, start = 6.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.key }) { item ->
                        PosterCard(
                            item = item,
                            onFocused = { focusedPoster = item.poster },
                            onClick = { onOpen(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: PosterItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    Column(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(AxiomColors.PanelHigh, AxiomColors.Panel)))
                    .then(
                        if (focused) Modifier.border(3.dp, AxiomColors.Gold, RoundedCornerShape(12.dp)) else Modifier,
                    ),
        ) {
            Text(
                item.title,
                color = AxiomColors.TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(10.dp),
            )
            if (!item.poster.isNullOrBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (item.watched) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AxiomColors.Gold),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = AxiomColors.OnGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            item.progress?.let { fraction ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x66000000)),
                ) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(AxiomColors.Gold))
                }
            }
        }
        Text(
            item.title,
            color = if (focused) AxiomColors.Gold else AxiomColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 12.sp, maxLines = 1) }
    }
}
