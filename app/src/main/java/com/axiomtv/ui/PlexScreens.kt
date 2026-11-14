package com.axiomtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.axiomtv.plex.PlexItem
import com.axiomtv.plex.PlexViewModel

/** The Plex section: link screen until an account is linked, then server, libraries and posters. */
@Composable
fun PlexSection(
    plex: PlexViewModel,
    onOpen: (PlexItem) -> Unit,
    onPlay: (PlexItem) -> Unit,
) {
    if (!plex.linked) {
        PlexLinkScreen(plex)
        return
    }
    LaunchedEffect(Unit) { plex.ensureLoaded() }
    val session = plex.session
    Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Plex", color = AxiomColors.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            if (plex.tab != PlexViewModel.HOME_TAB && plex.items.isNotEmpty()) {
                Text("${plex.items.size} titles", color = AxiomColors.TextMuted, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            if (plex.loading || plex.itemsLoading) {
                CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (plex.tab != PlexViewModel.HOME_TAB) {
                AxiomPillButton(if (plex.sortRecent) "Recently added" else "A–Z", onClick = { plex.toggleSort() }, icon = Icons.AutoMirrored.Rounded.Sort)
            }
            AxiomPillButton(
                (session?.server?.name ?: plex.servers.firstOrNull { it.id == plex.currentServerId }?.name ?: "Server") +
                    if (plex.servers.size > 1) "  ▾" else "",
                onClick = { if (plex.servers.size > 1) plex.nextServer() else plex.loadServers() },
                icon = Icons.Rounded.Dns,
            )
            AxiomPillButton("", onClick = { if (session == null) plex.loadServers() else plex.refresh() }, icon = Icons.Rounded.Refresh)
        }
        Spacer(Modifier.height(10.dp))
        if (session != null) {
            val tabs = listOf(PlexViewModel.HOME_TAB) + plex.visibleLibraries().map { it.key }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it }) { key ->
                    AxiomPillButton(plex.tabName(key), onClick = { plex.selectTab(key) }, highlighted = plex.tab == key)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            session == null || plex.status != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(plex.status ?: "Connecting…", color = AxiomColors.TextMuted, fontSize = 17.sp)
                        if (!plex.loading) AxiomPillButton("Try again", onClick = { plex.loadServers() }, icon = Icons.Rounded.Refresh)
                    }
                }

            plex.tab == PlexViewModel.HOME_TAB -> PlexHome(plex, onOpen, onPlay)

            else -> {
                val items = plex.items
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (plex.itemsLoading) "Loading…" else "Nothing in this library", color = AxiomColors.TextMuted, fontSize = 16.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.ratingKey }) { item ->
                            val poster = plex.image(item.thumb)
                            PosterCard(
                                item =
                                    PosterItem(
                                        key = item.ratingKey,
                                        title = item.title,
                                        subtitle = item.year?.toString(),
                                        poster = poster,
                                        badge = null,
                                        progress = item.progress,
                                        watched = item.watched,
                                        rating = item.rating,
                                    ),
                                onFocused = { Backdrop.image = plex.image(item.art, 1280, 720) ?: poster },
                                onClick = { onOpen(item) },
                                showTitle = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlexHome(
    plex: PlexViewModel,
    onOpen: (PlexItem) -> Unit,
    onPlay: (PlexItem) -> Unit,
) {
    if (plex.onDeck.isEmpty() && plex.recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (plex.itemsLoading) "Loading…" else "Nothing here yet", color = AxiomColors.TextMuted, fontSize = 16.sp)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (plex.onDeck.isNotEmpty()) {
            item(key = "ondeck") {
                HomeRow("Continue Watching") {
                    items(plex.onDeck, key = { "d" + it.ratingKey }) { item ->
                        val isEpisode = item.type == "episode"
                        PosterTile(
                            title = if (isEpisode) item.grandparentTitle ?: item.title else item.title,
                            subtitle =
                                listOfNotNull(
                                    item.episodeLabel,
                                    if (item.inProgress) "${formatTime(item.durationMs - item.viewOffsetMs)} left" else "Next up",
                                ).joinToString(" · "),
                            image = plex.image(if (isEpisode) item.grandparentThumb ?: item.thumb else item.thumb),
                            progress = item.progress,
                        ) { onPlay(item) }
                    }
                }
            }
        }
        plex.recent.forEach { (library, list) ->
            item(key = "recent" + library.key) {
                HomeRow("Recently added in ${library.title}") {
                    items(list, key = { "r" + library.key + it.ratingKey }) { item ->
                        val isEpisode = item.type == "episode"
                        val isSeason = item.type == "season"
                        PosterTile(
                            title = if (isEpisode || isSeason) item.grandparentTitle ?: item.title else item.title,
                            subtitle = item.episodeLabel ?: if (isSeason) item.title else item.year?.toString(),
                            image = plex.image(if (isEpisode) item.grandparentThumb ?: item.thumb else item.thumb),
                        ) { if (isEpisode) onPlay(item) else onOpen(item) }
                    }
                }
            }
        }
    }
}

/** Shown until a Plex account is linked: a code to type at plex.tv/link. */
@Composable
private fun PlexLinkScreen(plex: PlexViewModel) {
    LaunchedEffect(Unit) { if (plex.linkCode == null) plex.startLink() }
    Row(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("CONNECT PLEX", color = AxiomColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Link your Plex account", color = AxiomColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "1.  On your phone, go to plex.tv/link\n2.  Sign in to Plex if it asks\n3.  Type the code shown here",
                color = Color(0xFFC9CFDA),
                fontSize = 17.sp,
                lineHeight = 28.sp,
            )
            Text(
                "Your Plex password never touches AxiomTV. Your servers and libraries appear by themselves once it's linked.",
                color = AxiomColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            AxiomPillButton("Get a new code", onClick = { plex.startLink() }, icon = Icons.Rounded.Refresh)
        }
        Column(
            modifier =
                Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF121A2B))
                    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp))
                    .padding(vertical = 32.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Your code", color = AxiomColors.TextMuted, fontSize = 15.sp)
            val code = plex.linkCode
            if (code != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    code.forEach { ch ->
                        Box(
                            modifier =
                                Modifier
                                    .size(width = 60.dp, height = 78.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AxiomColors.Background)
                                    .border(1.dp, Color(0x80E5A00D), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text(ch.toString(), color = AxiomColors.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Waiting for you to enter it…", color = Color(0xFFC9CFDA), fontSize = 14.sp)
                }
            } else if (plex.linkError != null) {
                Text(plex.linkError ?: "", color = AxiomColors.Error, fontSize = 15.sp)
            } else {
                CircularProgressIndicator(color = AxiomColors.Gold)
            }
        }
    }
}

/** A Plex film or show: artwork, info, and Play / seasons & episodes. */
@Composable
fun PlexDetailScreen(
    item: PlexItem,
    plex: PlexViewModel,
    onPlay: (PlexItem, Long) -> Unit,
) {
    var meta by remember { mutableStateOf(item) }
    var seasons by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var seasonIndex by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val isShow = item.type == "show" || item.type == "season"
    val showKey = if (item.type == "season") item.parentRatingKey ?: item.ratingKey else item.ratingKey

    LaunchedEffect(item.ratingKey, reload) {
        plex.metadata(item.ratingKey)?.let { meta = it.first }
        if (isShow) {
            val list = plex.children(PlexItemKey(showKey))
            seasons = list
            if (item.type == "season") seasonIndex = list.indexOfFirst { it.ratingKey == item.ratingKey }.coerceAtLeast(0)
            else if (reload == 0) seasonIndex = list.indexOfFirst { !it.watched }.coerceAtLeast(0)
        }
    }
    LaunchedEffect(seasons, seasonIndex, reload) {
        episodes = seasons.getOrNull(seasonIndex)?.let { plex.children(it) }.orEmpty()
    }

    val backdrop = plex.image(meta.art ?: meta.grandparentArt, 1280, 720)
    DetailFrame(backdrop = backdrop) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 820.dp)) {
            Text("PLEX", color = AxiomColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(if (item.type == "season") item.grandparentTitle ?: meta.title else meta.title, color = AxiomColors.Text, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                meta.rating?.let { RatingText(it, 16) }
                meta.year?.let { Text(it.toString(), color = AxiomColors.Text, fontSize = 16.sp) }
                if (!isShow && meta.durationMs > 0) Text(formatTime(meta.durationMs), color = AxiomColors.Text, fontSize = 16.sp)
                if (isShow && seasons.isNotEmpty()) Text("${seasons.size} season" + if (seasons.size == 1) "" else "s", color = AxiomColors.Text, fontSize = 16.sp)
                meta.contentRating?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 15.sp) }
                meta.resolution?.let { Text(if (it.all(Char::isDigit)) "${it}p" else it.uppercase(), color = AxiomColors.TextMuted, fontSize = 15.sp) }
            }
            meta.summary?.let { Text(it, color = Color(0xFFC9CFDA), fontSize = 16.sp, lineHeight = 24.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }

            if (!isShow) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (meta.inProgress) {
                        DetailButton("Resume ${formatTime(meta.viewOffsetMs)}", primary = true, icon = Icons.Rounded.PlayArrow) { onPlay(meta, meta.viewOffsetMs) }
                        DetailButton("From the start", icon = Icons.Rounded.Replay) { onPlay(meta, 0) }
                    } else {
                        DetailButton("Play", primary = true, icon = Icons.Rounded.PlayArrow) { onPlay(meta, 0) }
                    }
                    DetailButton(if (meta.watched) "Mark unwatched" else "Mark watched", icon = Icons.Rounded.TaskAlt) {
                        plex.setWatched(meta, !meta.watched) { reload++ }
                    }
                }
            } else {
                val next = episodes.firstOrNull { it.inProgress } ?: episodes.firstOrNull { !it.watched }
                if (next != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailButton(
                            (if (next.inProgress) "Resume " else "Play ") + (next.episodeLabel ?: next.title),
                            primary = true,
                            icon = Icons.Rounded.PlayArrow,
                        ) { onPlay(next, next.viewOffsetMs) }
                    }
                }
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        items(seasons.size) { i ->
                            AxiomPillButton(seasons[i].title, onClick = { seasonIndex = i }, highlighted = i == seasonIndex)
                        }
                    }
                }
            }
        }
        if (isShow) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp).widthIn(max = 980.dp)) {
                if (episodes.isEmpty()) Text("Loading episodes…", color = AxiomColors.TextMuted, fontSize = 15.sp)
                episodes.forEach { episode ->
                    PlexEpisodeRow(
                        episode = episode,
                        image = plex.image(episode.thumb, 400, 225),
                        onClick = { onPlay(episode, if (episode.watched) 0 else episode.viewOffsetMs) },
                        onToggleWatched = { plex.setWatched(episode, !episode.watched) { reload++ } },
                    )
                }
            }
        }
    }
}

/** Minimal stand-in so children() can be asked for a show we only know by key. */
private fun PlexItemKey(ratingKey: String) =
    PlexItem(
        ratingKey = ratingKey, type = "show", title = "", year = null, summary = null, thumb = null, art = null,
        durationMs = 0, viewOffsetMs = 0, viewCount = 0, leafCount = 0, viewedLeafCount = 0, index = null, parentIndex = null,
        parentRatingKey = null, grandparentRatingKey = null, grandparentTitle = null, grandparentThumb = null, grandparentArt = null,
        contentRating = null, rating = null, librarySectionId = null, partKey = null, resolution = null, addedAt = 0,
    )

@Composable
private fun PlexEpisodeRow(
    episode: PlexItem,
    image: String?,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .glowFocus(focused, shape)
                .clip(shape)
                .background(if (focused) Color(0x33E5A00D) else Color(0x1AFFFFFF))
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(onClick = onClick, onLongClick = onToggleWatched)
                .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(176.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2333))) {
            if (image != null) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            episode.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(AxiomColors.Gold))
                }
            }
            if (episode.watched) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Watched", tint = AxiomColors.Gold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(episode.index?.let { "E$it" }, episode.title).joinToString(" · "),
                color = if (focused) AxiomColors.Gold else AxiomColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    episode.durationMs.takeIf { it > 0 }?.let { formatTime(it) },
                    if (episode.inProgress) "${formatTime(episode.durationMs - episode.viewOffsetMs)} left" else null,
                ).joinToString(" · "),
                color = AxiomColors.TextMuted,
                fontSize = 13.sp,
            )
            episode.summary?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
