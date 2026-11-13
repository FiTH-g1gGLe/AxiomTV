package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.data.MovieEntity
import com.github.damontecres.wholphin.axiom.data.SeriesEntity
import com.github.damontecres.wholphin.axiom.data.WatchProgressEntity
import com.github.damontecres.wholphin.axiom.library.LibraryViewModel
import com.github.damontecres.wholphin.axiom.library.MediaDetails
import com.github.damontecres.wholphin.axiom.library.SeriesDetails
import com.github.damontecres.wholphin.axiom.library.TmdbCast
import com.github.damontecres.wholphin.axiom.xtream.XtreamEpisode

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(
    movie: MovieEntity,
    viewModel: LibraryViewModel,
    onPlay: (startPositionMs: Long) -> Unit,
) {
    val details by produceState<MediaDetails?>(initialValue = null, movie.streamId) {
        value = viewModel.movieDetails(movie)
    }
    val progressMap by viewModel.movieProgress.collectAsState()
    val progress = progressMap[movie.streamId]?.takeIf { !it.finished && it.positionMs > 0 }
    val favouriteIds by viewModel.favouriteMovieIds.collectAsState()
    val isFavourite = movie.streamId in favouriteIds

    DetailFrame(backdrop = details?.backdrop ?: movie.poster) {
        DetailHeader(
            poster = details?.poster ?: movie.poster,
            title = movie.name,
            details = details,
            quality = movie.quality,
        ) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null) {
                    DetailButton(
                        "Resume from ${formatTime(progress.positionMs)}",
                        icon = Icons.Rounded.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(focus),
                    ) { onPlay(progress.positionMs) }
                    DetailButton("Play from start", icon = Icons.Rounded.Replay) { onPlay(0) }
                } else {
                    DetailButton("Play", icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(focus)) { onPlay(0) }
                }
                DetailButton(
                    if (isFavourite) "In My Films" else "Add to My Films",
                    icon = if (isFavourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                ) { viewModel.toggleFavouriteMovie(movie) }
                details?.trailer?.let { trailer -> TrailerButton(trailer) }
            }
        }
        details?.let { CastRow(it.cast) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SeriesDetailScreen(
    series: SeriesEntity,
    viewModel: LibraryViewModel,
    settings: com.github.damontecres.wholphin.axiom.AppSettings,
    onPlayEpisode: (episode: XtreamEpisode, seasonEpisodes: List<XtreamEpisode>, startPositionMs: Long) -> Unit,
) {
    val loaded by produceState<SeriesDetails?>(initialValue = null, series.seriesId) {
        value = viewModel.seriesDetails(series)
    }
    val progressMap by viewModel.episodeProgress.collectAsState()
    val favouriteIds by viewModel.favouriteSeriesIds.collectAsState()
    val isFavourite = series.seriesId in favouriteIds
    // Some providers list the same episode twice: keep one per episode number
    val seasons =
        remember(loaded) {
            loaded?.seasons.orEmpty().mapValues { (_, list) -> list.distinctBy { it.episode } }
        }
    val style = EpisodeStyle.from(settings.episodeStyle)
    val seasonNumbers = seasons.keys.sorted()

    // Next up: an episode in progress, else the first one not watched
    val allEpisodes = seasonNumbers.flatMap { seasons[it].orEmpty() }
    val nextUp: XtreamEpisode? =
        allEpisodes.firstOrNull { ep -> progressMap[ep.id]?.let { !it.finished && it.positionMs > 0 } == true }
            ?: allEpisodes.firstOrNull { ep -> progressMap[ep.id]?.finished != true }
            ?: allEpisodes.firstOrNull()

    var selectedSeason by remember(seasonNumbers) { mutableIntStateOf(nextUp?.season ?: seasonNumbers.firstOrNull() ?: 1) }

    DetailFrame(backdrop = loaded?.details?.backdrop ?: series.backdrop ?: series.poster) {
        DetailHeader(
            poster = loaded?.details?.poster ?: series.poster,
            title = series.name,
            details = loaded?.details,
            quality = null,
            extra = if (seasonNumbers.isNotEmpty()) "${seasonNumbers.size} season${if (seasonNumbers.size == 1) "" else "s"}" else null,
        ) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(nextUp?.id) { if (nextUp != null) runCatching { focus.requestFocus() } }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (nextUp != null) {
                val inProgress = progressMap[nextUp.id]?.takeIf { !it.finished && it.positionMs > 0 }
                val label =
                    if (inProgress != null) {
                        "Resume S${nextUp.season} E${nextUp.episode} · ${formatTime(inProgress.positionMs)}"
                    } else {
                        "Play S${nextUp.season} E${nextUp.episode}"
                    }
                DetailButton(label, icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(focus)) {
                    onPlayEpisode(nextUp, seasons[nextUp.season].orEmpty(), inProgress?.positionMs ?: 0)
                }
            } else if (loaded == null) {
                CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(28.dp))
            } else {
                Text("No episodes found", color = AxiomColors.TextMuted, fontSize = 16.sp)
            }
            DetailButton(
                if (isFavourite) "In My Series" else "Add to My Series",
                icon = if (isFavourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            ) { viewModel.toggleFavouriteSeries(series) }
            loaded?.details?.trailer?.let { trailer -> TrailerButton(trailer) }
            }
        }

        if (seasonNumbers.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(seasonNumbers) { number ->
                        AxiomPillButton(
                            "Season $number",
                            onClick = { selectedSeason = number },
                            highlighted = number == selectedSeason,
                        )
                    }
                }
                AxiomPillButton(style.label, onClick = { settings.updateEpisodeStyle(style.next().name) }, icon = style.icon)
            }
            Spacer(Modifier.height(12.dp))
            val episodes = seasons[selectedSeason].orEmpty()
            val rowState = rememberLazyListState()
            LaunchedEffect(selectedSeason, nextUp?.id) {
                val index = episodes.indexOfFirst { it.id == nextUp?.id }
                if (index > 0) rowState.scrollToItem(index)
            }
            val fallback = loaded?.details?.backdrop ?: series.backdrop
            val play = { episode: XtreamEpisode ->
                val p = progressMap[episode.id]?.takeIf { !it.finished }
                onPlayEpisode(episode, episodes, p?.positionMs ?: 0)
            }
            val toggle = { episode: XtreamEpisode -> viewModel.toggleEpisodeWatched(series, episode) }
            when (style) {
                EpisodeStyle.CARDS, EpisodeStyle.LARGE ->
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(start = 6.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        items(episodes, key = { it.id }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                progress = progressMap[episode.id],
                                fallbackImage = fallback,
                                width = if (style == EpisodeStyle.LARGE) 380 else 260,
                                onClick = { play(episode) },
                                onLongClick = { toggle(episode) },
                            )
                        }
                    }
                EpisodeStyle.GRID ->
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.padding(6.dp),
                    ) {
                        episodes.forEach { episode ->
                            EpisodeCard(
                                episode = episode,
                                progress = progressMap[episode.id],
                                fallbackImage = fallback,
                                width = 230,
                                onClick = { play(episode) },
                                onLongClick = { toggle(episode) },
                            )
                        }
                    }
                EpisodeStyle.LIST, EpisodeStyle.COMPACT ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        episodes.forEach { episode ->
                            EpisodeListRow(
                                episode = episode,
                                progress = progressMap[episode.id],
                                fallbackImage = fallback,
                                showImage = style == EpisodeStyle.LIST,
                                onClick = { play(episode) },
                                onLongClick = { toggle(episode) },
                            )
                        }
                    }
            }
        }
        loaded?.details?.let { CastRow(it.cast) }
    }
}

@Composable
private fun DetailFrame(
    backdrop: String?,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(AxiomColors.Background)) {
        if (!backdrop.isNullOrBlank()) {
            val drift = rememberInfiniteTransition(label = "detailDrift")
            val zoom by drift.animateFloat(1f, 1.07f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color(0xF50F1012), Color(0xCC0F1012), Color(0x660F1012)))),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x990F1012), AxiomColors.Background))),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 56.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun DetailHeader(
    poster: String?,
    title: String,
    details: MediaDetails?,
    quality: String?,
    extra: String? = null,
    actions: @Composable () -> Unit,
) {
    val glow = rememberArtworkColour(poster)
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Box(
            modifier =
                Modifier
                    .width(200.dp)
                    .aspectRatio(2f / 3f)
                    // Glow in the film's own colour
                    .shadow(36.dp, RoundedCornerShape(16.dp), ambientColor = glow, spotColor = glow)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
                    .background(AxiomColors.PanelHigh),
        ) {
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = AxiomColors.Text, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 42.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                details?.rating?.let {
                    RatingText(it, 16)
                }
                details?.year?.let { Text(it.toString(), color = AxiomColors.Text, fontSize = 16.sp) }
                details?.runtimeMinutes?.let {
                    Text(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m", color = AxiomColors.Text, fontSize = 16.sp)
                }
                extra?.let { Text(it, color = AxiomColors.Text, fontSize = 16.sp) }
                details?.certification?.takeIf { it.isNotBlank() }?.let { MetaPill(it, outlined = true) }
                quality?.let { MetaPill(it) }
            }
            if (details != null && details.genres.isNotEmpty()) {
                Text(details.genres.joinToString(" · "), color = AxiomColors.TextMuted, fontSize = 15.sp)
            }
            details?.tagline?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AxiomColors.Text, fontSize = 16.sp, fontStyle = FontStyle.Italic)
            }
            if (details == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = AxiomColors.Gold, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading details...", color = AxiomColors.TextMuted, fontSize = 15.sp)
                }
            } else {
                details.overview?.let {
                    Text(it, color = AxiomColors.TextMuted, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                details.director?.let { Text("Director: $it", color = AxiomColors.TextMuted, fontSize = 14.sp) }
            }
            Spacer(Modifier.height(6.dp))
            actions()
        }
    }
}

@Composable
private fun MetaPill(
    text: String,
    outlined: Boolean = false,
) {
    Text(
        text,
        color = AxiomColors.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (outlined) {
                        Modifier.border(1.5.dp, Color(0xFF6B6F76), RoundedCornerShape(6.dp))
                    } else {
                        Modifier.background(Color(0x26FFFFFF))
                    },
                ).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun DetailButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background =
        when {
            focused -> if (primary) AxiomColors.Gold else AxiomColors.Text
            primary -> AxiomColors.Gold
            else -> Color(0x26FFFFFF)
        }
    val textColor = if (focused || primary) AxiomColors.OnGold else AxiomColors.Text
    Row(
        modifier =
            modifier
                .then(
                    if (focused && primary) {
                        Modifier.shadow(20.dp, RoundedCornerShape(12.dp), ambientColor = AxiomColors.Gold, spotColor = AxiomColors.Gold)
                    } else {
                        Modifier
                    },
                ).clip(RoundedCornerShape(12.dp))
                .background(background)
                .then(
                    when {
                        focused -> Modifier.border(3.dp, AxiomColors.Text, RoundedCornerShape(12.dp))
                        !primary -> Modifier.border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(12.dp))
                        else -> Modifier
                    },
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp)) }
        Text(text, color = textColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** Gold star icon + rating, e.g. "8.0" */
@Composable
fun RatingText(
    rating: Double,
    size: Int = 14,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Rounded.Star, contentDescription = "Rating", tint = AxiomColors.Gold, modifier = Modifier.size((size + 2).dp))
        Text("%.1f".format(rating), color = AxiomColors.Gold, fontSize = size.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CastRow(cast: List<TmdbCast>) {
    if (cast.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    Text("Cast", color = AxiomColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) {
        items(cast) { person ->
            // Focusable so the remote can scroll down to the cast
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier =
                    Modifier
                        .width(96.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(84.dp)
                            .glowFocus(focused, RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .background(AxiomColors.PanelHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        person.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
                        color = AxiomColors.TextMuted,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (person.photo != null) {
                        AsyncImage(
                            model = person.photo,
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(person.name, color = AxiomColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                person.character?.let {
                    Text(it, color = AxiomColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun EpisodeCard(
    episode: XtreamEpisode,
    progress: WatchProgressEntity?,
    fallbackImage: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    width: Int = 260,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .width(width.dp)
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .glowFocus(focused, RoundedCornerShape(12.dp))
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AxiomColors.PanelHigh),
        ) {
            val image = episode.image ?: fallbackImage
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            episode.durationSecs?.takeIf { it > 0 }?.let {
                Text(
                    "${it / 60} min",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress?.finished == true) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AxiomColors.Gold),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = "Watched", tint = AxiomColors.OnGold, modifier = Modifier.size(16.dp))
                }
            } else if (progress != null && progress.durationMs > 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x66000000)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(AxiomColors.Gold),
                    )
                }
            }
        }
        Text("EPISODE ${episode.episode}", color = AxiomColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            episode.title,
            color = if (focused) AxiomColors.Gold else AxiomColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.plot?.let {
            Text(it, color = AxiomColors.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Opens the trailer in the YouTube app (or a browser). */
@Composable
private fun TrailerButton(trailer: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DetailButton("Trailer", icon = Icons.Rounded.Movie) {
        val url = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (ex: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "Install the YouTube app to watch trailers", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** How a series' episodes are laid out. */
enum class EpisodeStyle(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    CARDS("Cards", Icons.Rounded.ViewCarousel),
    LARGE("Large cards", Icons.Rounded.ViewDay),
    GRID("Grid", Icons.Rounded.GridView),
    LIST("List", Icons.AutoMirrored.Rounded.ViewList),
    COMPACT("Compact", Icons.Rounded.FormatListNumbered),
    ;

    fun next(): EpisodeStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(name: String): EpisodeStyle = entries.firstOrNull { it.name == name } ?: CARDS
    }
}

/** One episode as a row: thumbnail (List) or just the number (Compact), then title, length and description. */
@Composable
private fun EpisodeListRow(
    episode: XtreamEpisode,
    progress: WatchProgressEntity?,
    fallbackImage: String?,
    showImage: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .glowFocus(focused, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) Color(0xE623262C) else Color(0x8C16181C))
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(if (showImage) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (showImage) {
            Box(
                modifier =
                    Modifier
                        .width(200.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AxiomColors.PanelHigh),
            ) {
                val image = episode.image ?: fallbackImage
                if (!image.isNullOrBlank()) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (progress != null && !progress.finished && progress.durationMs > 0) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                        Box(
                            Modifier
                                .fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(AxiomColors.Gold),
                        )
                    }
                }
            }
        } else {
            Text(
                episode.episode.toString(),
                color = if (focused) AxiomColors.Gold else AxiomColors.TextMuted,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(44.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showImage) Text("E${episode.episode}", color = AxiomColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    episode.title,
                    color = if (focused) AxiomColors.Gold else AxiomColors.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (showImage) {
                episode.plot?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AxiomColors.TextMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        episode.durationSecs?.takeIf { it > 0 }?.let { Text("${it / 60} min", color = AxiomColors.TextMuted, fontSize = 14.sp) }
        when {
            progress?.finished == true ->
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Watched", tint = AxiomColors.Gold, modifier = Modifier.size(22.dp))
            progress != null && progress.positionMs > 0 && !showImage ->
                Text("${formatTime((progress.durationMs - progress.positionMs).coerceAtLeast(0))} left", color = AxiomColors.Gold, fontSize = 13.sp)
        }
    }
}
