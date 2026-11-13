package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.data.MovieEntity
import com.github.damontecres.wholphin.axiom.data.SeriesEntity
import com.github.damontecres.wholphin.axiom.data.WatchProgressEntity
import com.github.damontecres.wholphin.axiom.library.LibraryViewModel
import com.github.damontecres.wholphin.axiom.library.MediaDetails
import kotlinx.coroutines.delay

/**
 * Home: Netflix/Apple TV style. A fanart hero cycling through the newest films, then rows:
 * Continue Watching, Favourite channels, Recently added films and series, favourites.
 */
@Composable
fun HomeScreen(
    library: LibraryViewModel,
    favouriteChannels: List<ChannelEntity>,
    onPlayChannel: (ChannelEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onPlayMovie: (MovieEntity, Long) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
    onContinue: (WatchProgressEntity) -> Unit,
    recentChannels: List<ChannelEntity> = emptyList(),
    onPlayRecentChannel: (ChannelEntity) -> Unit = onPlayChannel,
    onRemoveRecentChannel: (ChannelEntity) -> Unit = {},
    onClearRecentChannels: () -> Unit = {},
) {
    var removeEntry by remember { mutableStateOf<WatchProgressEntity?>(null) }
    var removeChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    removeEntry?.let { entry ->
        RemoveDialog(
            title = entry.title,
            message = "Remove this from Continue Watching?",
            actions =
                listOf(
                    "Remove" to { library.removeFromContinue(entry) },
                    "Mark as watched" to { library.markWatched(entry) },
                    "Clear all of Continue Watching" to { library.clearContinueWatching() },
                ),
            onDismiss = { removeEntry = null },
        )
    }
    removeChannel?.let { channel ->
        RemoveDialog(
            title = channel.name,
            message = "Remove this from Recently Watched Channels?",
            actions =
                listOf(
                    "Remove" to { onRemoveRecentChannel(channel) },
                    "Clear all recent channels" to onClearRecentChannels,
                ),
            onDismiss = { removeChannel = null },
        )
    }
    val recentMovies by library.recentMovies.collectAsState()
    val recentSeries by library.recentSeries.collectAsState()
    val favouriteMovies by library.favouriteMovies.collectAsState()
    val favouriteSeries by library.favouriteSeries.collectAsState()
    val continueWatching by library.continueWatching.collectAsState()
    val movieProgress by library.movieProgress.collectAsState()

    val heroMovies = remember(recentMovies) { recentMovies.take(6) }
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    // The banner moves on by itself every 9 s, pausing while you're pressing buttons on it
    var lastInteraction by remember { mutableStateOf(0L) }
    LaunchedEffect(heroMovies.size) {
        if (heroMovies.size < 2) return@LaunchedEffect
        while (true) {
            delay(1000)
            if (System.currentTimeMillis() - lastInteraction > 9000) {
                heroIndex = (heroIndex + 1) % heroMovies.size
                lastInteraction = System.currentTimeMillis()
            }
        }
    }
    val hero = heroMovies.getOrNull(heroIndex % heroMovies.size.coerceAtLeast(1))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item(key = "hero") {
            if (hero != null) {
                Box {
                HeroBanner(
                    library = library,
                    movie = hero,
                    resumeMs = movieProgress[hero.streamId]?.takeIf { !it.finished }?.positionMs ?: 0,
                    onPlay = { onPlayMovie(hero, it) },
                    onMore = { onOpenMovie(hero) },
                    onFocusChange = { heroFocused = it },
                    index = heroIndex % heroMovies.size.coerceAtLeast(1),
                    count = heroMovies.size,
                    onInteract = { lastInteraction = System.currentTimeMillis() },
                )
                // Small brand name in the top corner, beside the menu bar
                AxiomWordmark(20.sp, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 26.dp))
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().height(260.dp).padding(40.dp)) {
                    AxiomWordmark(36.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (library.syncing) library.syncStatus ?: "Loading your library..." else "Your films and series will show here",
                        color = AxiomColors.TextMuted,
                        fontSize = 16.sp,
                    )
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                HomeRow("Continue Watching  ·  hold OK to remove") {
                    items(continueWatching, key = { it.key }) { entry ->
                        PosterTile(
                            title = entry.title,
                            subtitle =
                                if (entry.positionMs == 0L) {
                                    "Next episode"
                                } else {
                                    "${formatTime((entry.durationMs - entry.positionMs).coerceAtLeast(0))} left"
                                },
                            image = entry.poster,
                            progress = if (entry.durationMs > 0 && entry.positionMs > 0) entry.positionMs.toFloat() / entry.durationMs else null,
                            onLongClick = { removeEntry = entry },
                            onClick = { onContinue(entry) },
                        )
                    }
                }
            }
        }

        if (recentChannels.isNotEmpty()) {
            item(key = "recentChannels") {
                HomeRow("Recently Watched Channels  ·  hold OK to remove") {
                    items(recentChannels, key = { it.streamId }) { channel ->
                        ChannelTile(channel, onLongClick = { removeChannel = channel }) { onPlayRecentChannel(channel) }
                    }
                }
            }
        }

        if (favouriteChannels.isNotEmpty()) {
            item(key = "channels") {
                HomeRow("Favourite Channels") {
                    items(favouriteChannels, key = { it.streamId }) { channel ->
                        ChannelTile(channel) { onPlayChannel(channel) }
                    }
                }
            }
        }

        if (recentMovies.isNotEmpty()) {
            item(key = "recentMovies") {
                HomeRow("Recently Added Films") {
                    items(recentMovies, key = { it.streamId }) { movie ->
                        PosterTile(
                            title = movie.name,
                            subtitle = movie.year?.toString(),
                            image = movie.poster,
                            badge = movie.quality,
                            onClick = { onOpenMovie(movie) },
                        )
                    }
                }
            }
        }

        if (recentSeries.isNotEmpty()) {
            item(key = "recentSeries") {
                HomeRow("Recently Updated Series") {
                    items(recentSeries, key = { it.seriesId }) { series ->
                        PosterTile(
                            title = series.name,
                            subtitle = series.year?.toString(),
                            image = series.poster,
                            onClick = { onOpenSeries(series) },
                        )
                    }
                }
            }
        }

        if (favouriteMovies.isNotEmpty()) {
            item(key = "favMovies") {
                HomeRow("My Films") {
                    items(favouriteMovies, key = { it.streamId }) { movie ->
                        PosterTile(title = movie.name, subtitle = movie.year?.toString(), image = movie.poster, onClick = { onOpenMovie(movie) })
                    }
                }
            }
        }

        if (favouriteSeries.isNotEmpty()) {
            item(key = "favSeries") {
                HomeRow("My Series") {
                    items(favouriteSeries, key = { it.seriesId }) { series ->
                        PosterTile(title = series.name, subtitle = series.year?.toString(), image = series.poster, onClick = { onOpenSeries(series) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(
    library: LibraryViewModel,
    movie: MovieEntity,
    resumeMs: Long,
    onPlay: (Long) -> Unit,
    onMore: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    index: Int = 0,
    count: Int = 1,
    onInteract: () -> Unit = {},
) {
    val details by produceState<MediaDetails?>(initialValue = null, movie.streamId) {
        value = library.movieDetails(movie)
    }
    var buttonsFocused by remember { mutableStateOf(false) }
    val art = details?.backdrop ?: movie.poster
    LaunchedEffect(art, buttonsFocused) { if (buttonsFocused || Backdrop.image == null) Backdrop.image = art }
    // Slow drift on the artwork (Ken Burns)
    val drift = rememberInfiniteTransition(label = "drift")
    val zoom by drift.animateFloat(1f, 1.08f, infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
        Crossfade(
            targetState = art,
            animationSpec = tween(900),
            label = "hero",
            modifier =
                Modifier
                    .fillMaxSize()
                    // Fade the artwork out at the bottom so it melts into the backdrop
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
        ) { image ->
            if (!image.isNullOrBlank()) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color(0xF20F1012), Color(0xB30F1012), Color.Transparent))),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.55f)
                    .padding(start = 40.dp, top = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("NEW · FILM", color = AxiomColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                movie.name,
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 46.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                details?.rating?.let { RatingText(it, 16) }
                (details?.year ?: movie.year)?.let { Text(it.toString(), color = AxiomColors.Text, fontSize = 16.sp) }
                details?.runtimeMinutes?.let {
                    Text(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m", color = AxiomColors.Text, fontSize = 16.sp)
                }
                movie.quality?.let { GlassPill(it) }
            }
            details?.overview?.let {
                Text(it, color = AxiomColors.TextMuted, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .onFocusChanged {
                            buttonsFocused = it.hasFocus
                            onFocusChange(it.hasFocus)
                        }.onPreviewKeyEvent {
                            onInteract()
                            false
                        },
            ) {
                DetailButton(
                    if (resumeMs > 0) "Resume" else "Play",
                    icon = Icons.Rounded.PlayArrow,
                    primary = true,
                    modifier = Modifier.focusRequester(focus),
                ) { onPlay(resumeMs) }
                DetailButton("More info", icon = Icons.Rounded.Info) { onMore() }
            }
            if (count > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    repeat(count) { i ->
                        val active = i == index
                        val width by animateDpAsState(if (active) 26.dp else 8.dp, label = "dot")
                        Box(
                            Modifier
                                .width(width)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (active) AxiomColors.Gold else Color(0x4DFFFFFF)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeRow(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(top = 18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
        ) {
            Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(AxiomColors.Gold))
            Text(title, color = AxiomColors.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
fun PosterTile(
    title: String,
    subtitle: String?,
    image: String?,
    badge: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "tileScale")
    Column(
        modifier =
            Modifier
                .width(132.dp)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused && !image.isNullOrBlank()) Backdrop.image = image
                }.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }.glowFocus(focused, RoundedCornerShape(12.dp))
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AxiomColors.PanelHigh),
        ) {
            Text(title, color = AxiomColors.TextMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            progress?.let { fraction ->
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
            title,
            color = if (focused) AxiomColors.Gold else AxiomColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let { Text(it, color = AxiomColors.TextMuted, fontSize = 12.sp, maxLines = 1) }
    }
}

@Composable
fun ChannelTile(
    channel: ChannelEntity,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .width(150.dp)
                .glowFocus(focused, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) Color(0xE623262C) else Color(0xB316181C))
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelLogo(channel, 64)
        Text(
            channel.name,
            color = if (focused) AxiomColors.Gold else AxiomColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(0.dp))
    }
}

/** Small TV-friendly menu for removing things from Home rows. */
@Composable
private fun RemoveDialog(
    title: String,
    message: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AxiomColors.Panel,
        title = { Text(title, color = AxiomColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, color = AxiomColors.TextMuted, fontSize = 15.sp)
                actions.forEachIndexed { index, (label, action) ->
                    AxiomPillButton(
                        label,
                        onClick = {
                            action()
                            onDismiss()
                        },
                        highlighted = index == 0,
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    )
                }
                AxiomPillButton("Cancel", onClick = onDismiss)
            }
        },
        confirmButton = {},
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
