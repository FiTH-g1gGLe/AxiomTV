package com.github.damontecres.wholphin.axiom.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.github.damontecres.wholphin.axiom.data.AxiomRepository
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.axiom.AxiomLog
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.MovieEntity
import com.github.damontecres.wholphin.axiom.data.SeriesEntity
import com.github.damontecres.wholphin.axiom.library.LibraryViewModel
import com.github.damontecres.wholphin.axiom.xtream.XtreamEpisode
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * AxiomTV's own start screen. Replaces Wholphin's Jellyfin flow as the launcher.
 */
@AndroidEntryPoint
class AxiomActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AxiomLog.install()
        Timber.i("AxiomActivity started")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            AxiomTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AxiomColors.Background) {
                    AxiomApp()
                }
            }
        }
    }
}

/** Screens opened on top of a section (detail pages and players). */
private sealed interface Screen {
    data class MovieDetail(
        val movie: MovieEntity,
    ) : Screen

    data class SeriesDetail(
        val series: SeriesEntity,
    ) : Screen

    data class PlayMovie(
        val movie: MovieEntity,
        val startPositionMs: Long,
    ) : Screen

    data class PlayCatchup(
        val channel: com.github.damontecres.wholphin.axiom.data.ChannelEntity,
        val programme: com.github.damontecres.wholphin.axiom.data.ProgrammeEntity,
    ) : Screen

    data class PlayEpisode(
        val series: SeriesEntity,
        val episode: XtreamEpisode,
        val seasonEpisodes: List<XtreamEpisode>,
        val startPositionMs: Long,
    ) : Screen
}

@Composable
private fun AxiomApp(viewModel: AxiomViewModel = hiltViewModel()) {
    val state by viewModel.accountState.collectAsState()

    when (val current = state) {
        AccountState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AxiomColors.Gold)
            }
        }

        AccountState.SignedOut -> {
            LoginScreen(viewModel)
        }

        is AccountState.SignedIn -> {
            AppShell(viewModel = viewModel, account = current.account)
        }
    }
}

@Composable
private fun AppShell(
    viewModel: AxiomViewModel,
    account: AccountEntity,
    library: LibraryViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf(if (viewModel.settings.startScreen == "live") Section.LIVE else Section.HOME) }
    val stack = remember { mutableStateListOf<Screen>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lastBackPress by remember { mutableLongStateOf(0L) }
    val favouriteChannels by viewModel.favouriteChannels.collectAsState()
    val recentChannels by viewModel.recentChannels.collectAsState()

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    LaunchedEffect(Unit) { viewModel.checkForUpdate() }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    BackHandler(enabled = stack.isEmpty() && section != Section.LIVE && section != Section.HOME) { section = Section.HOME }
    BackHandler(enabled = stack.isEmpty() && section == Section.HOME) {
        if (System.currentTimeMillis() - lastBackPress < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackPress = System.currentTimeMillis()
            Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    when (val top = stack.lastOrNull()) {
        null -> {
            when (section) {
                Section.LIVE ->
                    LiveScreen(
                        viewModel = viewModel,
                        account = account,
                        onNavigate = { section = it },
                        onPlayCatchup = { channel, programme -> stack.add(Screen.PlayCatchup(channel, programme)) },
                    )
                Section.HOME, Section.SEARCH, Section.MOVIES, Section.SERIES, Section.SETTINGS, Section.CATCHUP -> {
                    AmbientBackdrop()
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavRail(
                            current = section,
                            onSelect = { section = it },
                            onRefresh = { library.sync() },
                            refreshing = library.syncing,
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            when (section) {
                                Section.MOVIES -> MoviesSection(library, viewModel.settings) { stack.add(Screen.MovieDetail(it)) }
                                Section.SERIES -> SeriesSection(library, viewModel.settings) { stack.add(Screen.SeriesDetail(it)) }
                                Section.SETTINGS -> SettingsScreen(viewModel = viewModel, library = library, account = account)
                                Section.CATCHUP ->
                                    CatchupScreen(viewModel = viewModel, account = account) { channel, programme ->
                                        stack.add(Screen.PlayCatchup(channel, programme))
                                    }
                                Section.SEARCH ->
                                    SearchScreen(
                                        library = library,
                                        onPlayChannel = { channel ->
                                            viewModel.selectCategory(null)
                                            viewModel.lastChannelId = channel.streamId
                                            section = Section.LIVE
                                        },
                                        onOpenMovie = { stack.add(Screen.MovieDetail(it)) },
                                        onOpenSeries = { stack.add(Screen.SeriesDetail(it)) },
                                    )
                                else ->
                                    HomeScreen(
                                        library = library,
                                        favouriteChannels = favouriteChannels,
                                        recentChannels = recentChannels,
                                        onRemoveRecentChannel = { viewModel.removeRecentChannel(it.streamId) },
                                        onClearRecentChannels = { viewModel.clearRecentChannels() },
                                        onPlayRecentChannel = { channel ->
                                            viewModel.selectCategory(null)
                                            viewModel.lastChannelId = channel.streamId
                                            section = Section.LIVE
                                        },
                                        onPlayChannel = { channel ->
                                            viewModel.selectCategory(AxiomRepository.FAVOURITES_CATEGORY)
                                            viewModel.lastChannelId = channel.streamId
                                            section = Section.LIVE
                                        },
                                        onOpenMovie = { stack.add(Screen.MovieDetail(it)) },
                                        onPlayMovie = { movie, start -> stack.add(Screen.PlayMovie(movie, start)) },
                                        onOpenSeries = { stack.add(Screen.SeriesDetail(it)) },
                                        onContinue = { entry ->
                                            scope.launch {
                                                if (entry.kind == "movie") {
                                                    library.findMovie(entry.mediaId)?.let { stack.add(Screen.PlayMovie(it, entry.positionMs)) }
                                                } else {
                                                    val series = entry.seriesId?.let { library.findSeries(it) }
                                                    if (series != null) {
                                                        // Play the episode straight away; fall back to the series page
                                                        val season =
                                                            library.seasonsFor(series).values.firstOrNull { list ->
                                                                list.any { it.id == entry.mediaId }
                                                            }
                                                        val episode = season?.firstOrNull { it.id == entry.mediaId }
                                                        if (season != null && episode != null) {
                                                            stack.add(Screen.PlayEpisode(series, episode, season, entry.positionMs))
                                                        } else {
                                                            stack.add(Screen.SeriesDetail(series))
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    )
                            }
                        }
                    }
                }
            }
        }

        is Screen.MovieDetail -> {
            key(top) {
                MovieDetailScreen(top.movie, library) { start -> stack.add(Screen.PlayMovie(top.movie, start)) }
            }
        }

        is Screen.SeriesDetail -> {
            key(top) {
                SeriesDetailScreen(top.series, library, viewModel.settings) { episode, seasonEpisodes, start ->
                    stack.add(Screen.PlayEpisode(top.series, episode, seasonEpisodes, start))
                }
            }
        }

        is Screen.PlayMovie -> {
            key(top) {
                val request = remember { library.movieRequest(top.movie, top.startPositionMs) }
                if (request == null) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    VodPlayerScreen(
                        request = request,
                        subtitle = top.movie.year?.toString(),
                        artwork = top.movie.poster,
                        engineMemory = viewModel.engineMemory,
                        onProgress = { position, duration -> library.saveMovieProgress(top.movie, position, duration) },
                        onEnded = { pop() },
                        onBack = { pop() },
                    )
                }
            }
        }

        is Screen.PlayCatchup -> {
            key(top) {
                val request by androidx.compose.runtime.produceState<com.github.damontecres.wholphin.axiom.player.MediaRequest?>(null) {
                    value = viewModel.catchupRequest(account, top.channel, top.programme)
                }
                request?.let { ready ->
                    VodPlayerScreen(
                        request = ready,
                        subtitle = "Catch-up · ${top.channel.name}",
                        artwork = top.channel.logo,
                        artworkSquare = true,
                        engineMemory = viewModel.engineMemory,
                        onProgress = { _, _ -> },
                        onEnded = { pop() },
                        onBack = { pop() },
                    )
                } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AxiomColors.Gold)
                }
            }
        }

        is Screen.PlayEpisode -> {
            key(top) {
                val request = remember { library.episodeRequest(top.series, top.episode, top.startPositionMs) }
                if (request == null) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    VodPlayerScreen(
                        request = request,
                        subtitle = top.episode.title,
                        artwork = top.series.poster,
                        engineMemory = viewModel.engineMemory,
                        onProgress = { position, duration ->
                            val index = top.seasonEpisodes.indexOfFirst { it.id == top.episode.id }
                            library.saveEpisodeProgress(
                                top.series,
                                top.episode,
                                position,
                                duration,
                                next = top.seasonEpisodes.getOrNull(index + 1),
                            )
                        },
                        onEnded = {
                            // Carry on to the next episode in the season
                            val index = top.seasonEpisodes.indexOfFirst { it.id == top.episode.id }
                            val next = top.seasonEpisodes.getOrNull(index + 1)
                            pop()
                            if (next != null) {
                                stack.add(Screen.PlayEpisode(top.series, next, top.seasonEpisodes, 0))
                            }
                        },
                        onBack = { pop() },
                        nextUpLabel =
                            top.seasonEpisodes
                                .getOrNull(top.seasonEpisodes.indexOfFirst { it.id == top.episode.id } + 1)
                                ?.let { "S${it.season} E${it.episode} · ${it.title}" },
                    )
                }
            }
        }
    }

    viewModel.availableUpdate?.let { release ->
        UpdateCard(
            version = release.version.toString(),
            progress = viewModel.updateProgress,
            onInstall = {
                if (!viewModel.installUpdate()) {
                    Toast
                        .makeText(context, "Update with Downloader: github.com/FiTH-g1gGLe/AxiomTV/releases", Toast.LENGTH_LONG)
                        .show()
                }
            },
            onLater = { viewModel.dismissUpdate() },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
    }
}

@Composable
private fun MoviesSection(
    library: LibraryViewModel,
    settings: com.github.damontecres.wholphin.axiom.AppSettings,
    onOpen: (MovieEntity) -> Unit,
) {
    val movies by library.movies.collectAsState()
    val categories by library.movieCategories.collectAsState()
    val selected by library.movieCategory.collectAsState()
    val sort by library.movieSort.collectAsState()
    val progress by library.movieProgress.collectAsState()

    val items =
        remember(movies, progress) {
            movies.map { movie ->
                val p = progress[movie.streamId]
                PosterItem(
                    key = movie.streamId,
                    title = movie.name,
                    subtitle = movie.year?.toString(),
                    poster = movie.poster,
                    badge = movie.quality,
                    progress = p?.takeIf { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
                    watched = p?.finished == true,
                    rating = movie.rating,
                )
            }
        }

    LibraryGrid(
        title = "Movies",
        favouritesLabel = "My Films",
        items = items,
        categories = categories,
        selectedCategory = selected,
        onSelectCategory = { library.selectMovieCategory(it) },
        sortLabel = sort.label,
        onCycleSort = { library.cycleMovieSort() },
        syncing = library.syncing,
        syncStatus = library.syncStatus,
        onOpen = { item -> movies.firstOrNull { it.streamId == item.key }?.let(onOpen) },
        style = LibraryStyle.from(settings.movieStyle),
        onCycleStyle = { settings.updateMovieStyle(LibraryStyle.from(settings.movieStyle).next().name) },
    )
}

@Composable
private fun SeriesSection(
    library: LibraryViewModel,
    settings: com.github.damontecres.wholphin.axiom.AppSettings,
    onOpen: (SeriesEntity) -> Unit,
) {
    val series by library.series.collectAsState()
    val categories by library.seriesCategories.collectAsState()
    val selected by library.seriesCategory.collectAsState()
    val sort by library.seriesSort.collectAsState()

    val items =
        remember(series) {
            series.map { show ->
                PosterItem(
                    key = show.seriesId,
                    title = show.name,
                    subtitle = show.year?.toString(),
                    poster = show.poster,
                    badge = null,
                    progress = null,
                    watched = false,
                    rating = show.rating,
                    backdrop = show.backdrop,
                    overview = show.plot,
                )
            }
        }

    LibraryGrid(
        title = "Series",
        favouritesLabel = "My Series",
        items = items,
        categories = categories,
        selectedCategory = selected,
        onSelectCategory = { library.selectSeriesCategory(it) },
        sortLabel = sort.label,
        onCycleSort = { library.cycleSeriesSort() },
        syncing = library.syncing,
        syncStatus = library.syncStatus,
        onOpen = { item -> series.firstOrNull { it.seriesId == item.key }?.let(onOpen) },
        style = LibraryStyle.from(settings.seriesStyle),
        onCycleStyle = { settings.updateSeriesStyle(LibraryStyle.from(settings.seriesStyle).next().name) },
    )
}

@Composable
private fun UpdateCard(
    version: String,
    progress: String?,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier =
            modifier
                .padding(24.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color(0xF0141518))
                .padding(18.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Text("UPDATE AVAILABLE", color = AxiomColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        androidx.compose.material3.Text("AxiomTV $version", color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (progress != null) {
            androidx.compose.material3.Text(progress, color = AxiomColors.TextMuted, fontSize = 14.sp)
        } else {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                DetailButton("Install", primary = true, onClick = onInstall)
                DetailButton("Later", onClick = onLater)
            }
        }
    }
}
