package com.axiomtv.ui

import androidx.compose.material.icons.rounded.History
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.axiomtv.data.MediaCategoryEntity

enum class Section(
    val label: String,
) {
    HOME("Home"),
    SEARCH("Search"),
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    CATCHUP("Catch-up"),
    SETTINGS("Settings"),
}

/** Collapsed left dock (Plex-style): icons with short labels, active item in gold. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun NavRail(
    current: Section,
    onSelect: (Section) -> Unit,
    onRefresh: () -> Unit = {},
    refreshing: Boolean = false,
) {
    // Navy frosted-glass bar: the artwork behind tints it. Icons only; the label shows under the current or focused item.
    BoxWithConstraints(
        modifier =
            Modifier
                .width(96.dp)
                .fillMaxHeight()
                .background(RailBackground)
                .drawBehind {
                    drawRect(Color(0x14FFFFFF), topLeft = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
                },
    ) {
        val compact = maxHeight < 460.dp
        val viewport = maxHeight
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(vertical = if (compact) 10.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.axiomtv.R.drawable.axiom_logo),
                    contentDescription = "AxiomTV",
                    modifier = Modifier.width(if (compact) 44.dp else 56.dp).padding(bottom = if (compact) 6.dp else 16.dp),
                )
                RailItem(Icons.Filled.Search, "Search", current == Section.SEARCH, compact) { onSelect(Section.SEARCH) }
                RailItem(Icons.Filled.Home, "Home", current == Section.HOME, compact) { onSelect(Section.HOME) }
                RailItem(AxiomIcons.LiveTv, "Live TV", current == Section.LIVE, compact) { onSelect(Section.LIVE) }
                RailItem(AxiomIcons.Movie, "Movies", current == Section.MOVIES, compact) { onSelect(Section.MOVIES) }
                RailItem(AxiomIcons.Series, "Series", current == Section.SERIES, compact) { onSelect(Section.SERIES) }
            }
            Column(
                modifier = Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
            ) {
                RailItem(Icons.Rounded.History, "Catch-up", current == Section.CATCHUP, compact) { onSelect(Section.CATCHUP) }
                RailItem(Icons.Filled.Settings, "Settings", current == Section.SETTINGS, compact) { onSelect(Section.SETTINGS) }
            }
        }
    }
}

/** Navy glass of the left menu bar (shared with the TV guide's menu). */
val RailBackground =
    Brush.verticalGradient(
        0f to Color(0xD90C1428),
        0.55f to Color(0xC21A1610),
        1f to Color(0xE60A1020),
    )

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick),
    ) { RailItemContent(icon, label, selected, focused, compact) }
}

/** How one menu-bar item looks: [highlighted] = the remote is on it. */
@Composable
fun RailItemContent(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    highlighted: Boolean,
    compact: Boolean = false,
) {
    val focused = highlighted
    val tint =
        when {
            focused -> Color.White
            selected -> AxiomColors.Gold
            else -> Color(0xFFB4B9C2)
        }
    Box(modifier = Modifier.fillMaxWidth()) {
        // Gold edge marker for the current section
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(if (compact) 34.dp else 46.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(AxiomColors.Gold),
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .width(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            focused -> Brush.verticalGradient(listOf(Color(0x66E5A00D), Color(0x33E5A00D)))
                            selected -> Brush.verticalGradient(listOf(Color(0x33E5A00D), Color(0x14E5A00D)))
                            else -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                        },
                    ).then(if (focused) Modifier.border(1.5.dp, Color(0x99E5A00D), RoundedCornerShape(14.dp)) else Modifier)
                    .padding(vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
            androidx.compose.animation.AnimatedVisibility(visible = selected || focused) {
                Text(
                    label.uppercase(),
                    color = tint,
                    fontSize = if (compact) 8.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** How the Movies and Series pages are laid out. */
enum class LibraryStyle(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    POSTERS("Posters", Icons.Rounded.GridView),
    LARGE("Large posters", Icons.Rounded.ViewModule),
    WALL("Poster wall", Icons.Rounded.Apps),
    WIDE("Wide cards", Icons.Rounded.ViewCarousel),
    LIST("List", Icons.AutoMirrored.Rounded.ViewList),
    ;

    fun next(): LibraryStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(name: String): LibraryStyle = entries.firstOrNull { it.name == name } ?: POSTERS
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
    val rating: Double? = null,
    val backdrop: String? = null,
    val overview: String? = null,
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
    favouritesLabel: String = "Favourites",
    style: LibraryStyle = LibraryStyle.POSTERS,
    onCycleStyle: () -> Unit = {},
) {
    var focusedPoster by remember { mutableStateOf<String?>(null) }

    // The shell's ambient backdrop follows the focused poster
    LaunchedEffect(focusedPoster) { focusedPoster?.let { Backdrop.image = it } }
    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = AxiomColors.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text("${items.size} titles", color = AxiomColors.TextMuted, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                if (syncing || syncStatus != null) {
                    if (syncing) CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(syncStatus ?: "", color = AxiomColors.TextMuted, fontSize = 14.sp)
                }
                AxiomPillButton(style.label, onClick = onCycleStyle, icon = style.icon)
                AxiomPillButton(sortLabel, onClick = onCycleSort, icon = Icons.AutoMirrored.Rounded.Sort)
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "__all__") {
                    AxiomPillButton("All", onClick = { onSelectCategory(null) }, highlighted = selectedCategory == null)
                }
                item(key = "__favourites__") {
                    val favId = com.axiomtv.data.AxiomRepository.FAVOURITES_CATEGORY
                    AxiomPillButton(favouritesLabel, onClick = { onSelectCategory(favId) }, highlighted = selectedCategory == favId, icon = Icons.Rounded.Star)
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
                            selectedCategory == com.axiomtv.data.AxiomRepository.FAVOURITES_CATEGORY ->
                                "Nothing here yet. Open a title and choose \"Add to $favouritesLabel\"".replace("★ ", "")
                            else -> "Nothing here yet"
                        },
                        color = AxiomColors.TextMuted,
                        fontSize = 16.sp,
                    )
                }
            } else {
                val (minSize, gap) =
                    when (style) {
                        LibraryStyle.POSTERS -> 148.dp to 18.dp
                        LibraryStyle.LARGE -> 210.dp to 22.dp
                        LibraryStyle.WALL -> 104.dp to 8.dp
                        LibraryStyle.WIDE -> 300.dp to 18.dp
                        LibraryStyle.LIST -> 480.dp to 14.dp
                    }
                // key() resets scroll position when the layout changes
                androidx.compose.runtime.key(style) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = minSize),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(if (style == LibraryStyle.WALL) 8.dp else 20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.key }) { item ->
                            val focusedArt = { focusedPoster = item.backdrop ?: item.poster }
                            when (style) {
                                LibraryStyle.WIDE -> WideCard(item, onFocused = focusedArt, onClick = { onOpen(item) })
                                LibraryStyle.LIST -> ListCard(item, onFocused = focusedArt, onClick = { onOpen(item) })
                                else ->
                                    PosterCard(
                                        item = item,
                                        onFocused = focusedArt,
                                        onClick = { onOpen(item) },
                                        showTitle = style != LibraryStyle.WALL,
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
private fun PosterCard(
    item: PosterItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    showTitle: Boolean = true,
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
                    .glowFocus(focused, RoundedCornerShape(12.dp))
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(AxiomColors.PanelHigh, AxiomColors.Panel))),
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
                    Icon(Icons.Rounded.Check, contentDescription = "Watched", tint = AxiomColors.OnGold, modifier = Modifier.size(15.dp))
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
        if (showTitle) {
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
}

/** Small badges shared by the wide and list layouts. */
@Composable
private fun MetaLine(item: PosterItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item.subtitle?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 12.sp, maxLines = 1) }
        item.rating?.takeIf { it > 0 }?.let { RatingText(it, 12) }
        item.badge?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 12.sp, maxLines = 1) }
        if (item.watched) Icon(Icons.Rounded.CheckCircle, contentDescription = "Watched", tint = AxiomColors.Gold, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ProgressStrip(
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    fraction ?: return
    Box(modifier = modifier.fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(AxiomColors.Gold))
    }
}

/** 16:9 card: fanart when there is some, otherwise the poster beside a blurred copy of itself. */
@Composable
private fun WideCard(
    item: PosterItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "wideScale")
    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(AxiomColors.Panel)
                .glowFocus(focused, RoundedCornerShape(12.dp))
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }.clickable(onClick = onClick),
    ) {
        if (!item.backdrop.isNullOrBlank()) {
            AsyncImage(item.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (!item.poster.isNullOrBlank()) {
            AsyncImage(
                item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp).alpha(0.6f),
            )
            AsyncImage(
                item.poster,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).align(Alignment.CenterEnd).padding(10.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                item.title,
                color = if (focused) AxiomColors.Gold else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(item)
        }
        ProgressStrip(item.progress, Modifier.align(Alignment.BottomCenter))
    }
}

/** Row with a small poster and details (description for series). */
@Composable
private fun ListCard(
    item: PosterItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) Color(0xFF23262C) else AxiomColors.Panel)
                .then(if (focused) Modifier.border(2.dp, AxiomColors.Gold, RoundedCornerShape(12.dp)) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }.clickable(onClick = onClick)
                .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AxiomColors.PanelHigh),
        ) {
            if (!item.poster.isNullOrBlank()) {
                AsyncImage(item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            ProgressStrip(item.progress, Modifier.align(Alignment.BottomCenter))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title,
                color = if (focused) AxiomColors.Gold else AxiomColors.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(item)
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AxiomColors.TextMuted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
