package com.github.damontecres.wholphin.axiom.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.axiom.data.AccountEntity
import com.github.damontecres.wholphin.axiom.data.AxiomRepository
import com.github.damontecres.wholphin.axiom.data.MediaCategoryEntity
import com.github.damontecres.wholphin.axiom.data.MovieEntity
import com.github.damontecres.wholphin.axiom.data.SeriesEntity
import com.github.damontecres.wholphin.axiom.data.WatchProgressEntity
import com.github.damontecres.wholphin.axiom.player.MediaRequest
import com.github.damontecres.wholphin.axiom.xtream.XtreamEpisode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

enum class LibrarySort(
    val label: String,
) {
    ADDED("Recently added"),
    AZ("A–Z"),
    RATING("Rating"),
    YEAR("Year"),
}

data class MediaDetails(
    val title: String,
    val overview: String?,
    val tagline: String?,
    val backdrop: String?,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val certification: String?,
    val director: String?,
    val cast: List<TmdbCast>,
    /** YouTube key or link */
    val trailer: String? = null,
)

data class SearchResults(
    val query: String,
    val channels: List<ChannelEntity>,
    val movies: List<MovieEntity>,
    val series: List<SeriesEntity>,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    companion object {
        val EMPTY = SearchResults("", emptyList(), emptyList(), emptyList())
    }
}

data class SeriesDetails(
    val details: MediaDetails,
    val seasons: Map<Int, List<XtreamEpisode>>,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
        private val favourites: FavouritesStore,
        private val dao: com.github.damontecres.wholphin.axiom.data.AxiomDao,
        axiomRepository: AxiomRepository,
    ) : ViewModel() {
        val account: StateFlow<AccountEntity?> =
            axiomRepository.account.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val movieCategory = MutableStateFlow<String?>(null)
        val seriesCategory = MutableStateFlow<String?>(null)
        val movieSort = MutableStateFlow(LibrarySort.ADDED)
        val seriesSort = MutableStateFlow(LibrarySort.ADDED)

        val movieCategories: StateFlow<List<MediaCategoryEntity>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptyList()) else repository.movieCategories(a.id) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val seriesCategories: StateFlow<List<MediaCategoryEntity>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptyList()) else repository.seriesCategories(a.id) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val movies: StateFlow<List<MovieEntity>> =
            combine(account, movieCategory, movieSort) { a, c, s -> Triple(a, c, s) }
                .flatMapLatest { (a, c, s) ->
                    if (a == null) flowOf(emptyList()) else repository.movies(a.id, c).map { sortMovies(it, s) }
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val series: StateFlow<List<SeriesEntity>> =
            combine(account, seriesCategory, seriesSort) { a, c, s -> Triple(a, c, s) }
                .flatMapLatest { (a, c, s) ->
                    if (a == null) flowOf(emptyList()) else repository.series(a.id, c).map { sortSeries(it, s) }
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Film watch progress by stream id */
        val movieProgress: StateFlow<Map<Long, WatchProgressEntity>> =
            account
                .flatMapLatest { a ->
                    if (a == null) flowOf(emptyMap()) else repository.progress("movie:${a.id}:").map { list -> list.associateBy { it.mediaId } }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

        /** Episode watch progress by episode id */
        val episodeProgress: StateFlow<Map<Long, WatchProgressEntity>> =
            account
                .flatMapLatest { a ->
                    if (a == null) flowOf(emptyMap()) else repository.progress("ep:${a.id}:").map { list -> list.associateBy { it.mediaId } }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

        val favouriteMovieIds: StateFlow<Set<Long>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptySet()) else favourites.ids(a.id, "movie") }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        val favouriteSeriesIds: StateFlow<Set<Long>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptySet()) else favourites.ids(a.id, "series") }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        // ---- Home rows ----
        val recentMovies: StateFlow<List<MovieEntity>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptyList()) else dao.observeRecentMovies(a.id, 30) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val recentSeries: StateFlow<List<SeriesEntity>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptyList()) else dao.observeRecentSeries(a.id, 30) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val favouriteMovies: StateFlow<List<MovieEntity>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptyList()) else dao.observeFavouriteMovies(a.id) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val favouriteSeries: StateFlow<List<SeriesEntity>> =
            account
                .flatMapLatest { a -> if (a == null) flowOf(emptyList()) else dao.observeFavouriteSeries(a.id) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val continueWatching: StateFlow<List<WatchProgressEntity>> =
            account
                .flatMapLatest { a ->
                    if (a == null) flowOf(emptyList()) else dao.observeContinueWatching("movie:${a.id}:", "ep:${a.id}:")
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        // ---- Search ----
        val searchQuery = MutableStateFlow("")

        val searchResults: StateFlow<SearchResults> =
            searchQuery
                .debounce(300)
                .mapLatest { raw ->
                    val query = raw.trim()
                    val a = account.value
                    if (query.length < 2 || a == null) {
                        SearchResults.EMPTY
                    } else {
                        SearchResults(
                            query = query,
                            channels = dao.searchChannels(a.id, query),
                            movies = dao.searchMovies(a.id, query),
                            series = dao.searchSeries(a.id, query),
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults.EMPTY)

        fun toggleFavouriteMovie(movie: MovieEntity) {
            viewModelScope.launch { favourites.toggle(movie.accountId, "movie", movie.streamId) }
        }

        fun toggleFavouriteSeries(series: SeriesEntity) {
            viewModelScope.launch { favourites.toggle(series.accountId, "series", series.seriesId) }
        }

        suspend fun findMovie(streamId: Long): MovieEntity? = account.value?.let { dao.getMovie(it.id, streamId) }

        suspend fun findSeries(seriesId: Long): SeriesEntity? = account.value?.let { dao.getSeries(it.id, seriesId) }

        var syncing by mutableStateOf(false)
            private set
        var syncStatus by mutableStateOf<String?>(null)
            private set

        val tmdbEnabled: Boolean get() = repository.tmdb.enabled

        init {
            viewModelScope.launch {
                account.filterNotNull().distinctUntilChangedBy { it.id }.collect { a ->
                    if (repository.needsSync(a)) sync()
                }
            }
        }

        fun sync() {
            val a = account.value ?: return
            if (syncing) return
            viewModelScope.launch {
                syncing = true
                try {
                    repository.sync(a) { status -> withContext(Dispatchers.Main) { syncStatus = status } }
                    syncStatus = null
                } catch (ex: Exception) {
                    Timber.e(ex, "Library sync failed")
                    syncStatus = "Couldn't load the library"
                } finally {
                    syncing = false
                }
            }
        }

        fun selectMovieCategory(id: String?) {
            movieCategory.value = id
        }

        fun selectSeriesCategory(id: String?) {
            seriesCategory.value = id
        }

        fun cycleMovieSort() {
            movieSort.value = LibrarySort.entries[(movieSort.value.ordinal + 1) % LibrarySort.entries.size]
        }

        fun cycleSeriesSort() {
            seriesSort.value = LibrarySort.entries[(seriesSort.value.ordinal + 1) % LibrarySort.entries.size]
        }

        suspend fun movieDetails(movie: MovieEntity): MediaDetails {
            val a = account.value
            val info = if (a != null) repository.movieInfo(a, movie) else null
            val year = movie.year ?: info?.releaseDate?.take(4)?.toIntOrNull()
            val tmdb = repository.tmdb.movie(info?.tmdbId ?: movie.tmdbId, movie.name, year)
            return MediaDetails(
                title = movie.name,
                overview = tmdb?.overview ?: info?.plot,
                tagline = tmdb?.tagline,
                backdrop = tmdb?.backdrop ?: info?.backdrop,
                poster = movie.poster ?: tmdb?.poster,
                rating = tmdb?.rating ?: info?.rating ?: movie.rating,
                year = year ?: tmdb?.year,
                runtimeMinutes = tmdb?.runtimeMinutes ?: info?.durationSecs?.let { (it / 60).toInt() },
                genres = tmdb?.genres?.takeIf { it.isNotEmpty() } ?: info?.genre?.split(",", "/")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                certification = tmdb?.certification,
                director = info?.director,
                cast = tmdb?.cast?.takeIf { it.isNotEmpty() } ?: castFromText(info?.cast),
                trailer = tmdb?.trailer ?: info?.trailer?.takeIf { it.isNotBlank() },
            )
        }

        suspend fun seriesDetails(series: SeriesEntity): SeriesDetails {
            val a = account.value
            val info = if (a != null) repository.seriesInfo(a, series) else null
            val year = series.year ?: info?.releaseDate?.take(4)?.toIntOrNull()
            val tmdb = repository.tmdb.tv(info?.tmdbId ?: series.tmdbId, series.name, year)
            val details =
                MediaDetails(
                    title = series.name,
                    overview = tmdb?.overview ?: info?.plot ?: series.plot,
                    tagline = tmdb?.tagline,
                    backdrop = tmdb?.backdrop ?: info?.backdrop ?: series.backdrop,
                    poster = series.poster ?: tmdb?.poster,
                    rating = tmdb?.rating ?: series.rating,
                    year = year ?: tmdb?.year,
                    runtimeMinutes = tmdb?.runtimeMinutes,
                    genres = tmdb?.genres?.takeIf { it.isNotEmpty() } ?: info?.genre?.split(",", "/")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    certification = tmdb?.certification,
                    director = null,
                    cast = tmdb?.cast?.takeIf { it.isNotEmpty() } ?: castFromText(info?.cast),
                    trailer = tmdb?.trailer,
                )
            return SeriesDetails(details, info?.seasons.orEmpty())
        }

        private fun castFromText(text: String?): List<TmdbCast> =
            text
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(12)
                ?.map { TmdbCast(it, null, null) }
                .orEmpty()

        fun movieRequest(
            movie: MovieEntity,
            startPositionMs: Long,
        ): MediaRequest? {
            val a = account.value ?: return null
            return MediaRequest(
                key = LibraryRepository.movieKey(a.id, movie.streamId),
                url = repository.movieUrl(a, movie),
                isLive = false,
                title = movie.name,
                startPositionMs = startPositionMs,
            )
        }

        fun episodeRequest(
            series: SeriesEntity,
            episode: XtreamEpisode,
            startPositionMs: Long,
        ): MediaRequest? {
            val a = account.value ?: return null
            return MediaRequest(
                key = LibraryRepository.episodeKey(a.id, episode.id),
                url = repository.episodeUrl(a, episode),
                isLive = false,
                title = "${series.name} · S${episode.season} E${episode.episode}",
                startPositionMs = startPositionMs,
            )
        }

        fun saveMovieProgress(
            movie: MovieEntity,
            positionMs: Long,
            durationMs: Long,
        ) {
            val a = account.value ?: return
            save(
                WatchProgressEntity(
                    key = LibraryRepository.movieKey(a.id, movie.streamId),
                    kind = "movie",
                    title = movie.name,
                    poster = movie.poster,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    finished = isFinished(positionMs, durationMs),
                    updatedAt = System.currentTimeMillis(),
                    mediaId = movie.streamId,
                    seriesId = null,
                    extension = movie.extension,
                ),
            )
        }

        fun saveEpisodeProgress(
            series: SeriesEntity,
            episode: XtreamEpisode,
            positionMs: Long,
            durationMs: Long,
            /** the following episode: queued in Continue Watching once this one is finished */
            next: XtreamEpisode? = null,
        ) {
            val a = account.value ?: return
            if (next != null && isFinished(positionMs, durationMs)) queueNextEpisode(series, next)
            save(
                WatchProgressEntity(
                    key = LibraryRepository.episodeKey(a.id, episode.id),
                    kind = "episode",
                    title = "${series.name} · S${episode.season} E${episode.episode} · ${episode.title}",
                    poster = series.poster,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    finished = isFinished(positionMs, durationMs),
                    updatedAt = System.currentTimeMillis(),
                    mediaId = episode.id,
                    seriesId = series.seriesId,
                    extension = episode.extension,
                ),
            )
        }

        private fun episodeEntry(
            accountId: Long,
            series: SeriesEntity,
            episode: XtreamEpisode,
            positionMs: Long,
            durationMs: Long,
            finished: Boolean,
        ) = WatchProgressEntity(
            key = LibraryRepository.episodeKey(accountId, episode.id),
            kind = "episode",
            title = "${series.name} · S${episode.season} E${episode.episode} · ${episode.title}",
            poster = series.poster,
            positionMs = positionMs,
            durationMs = durationMs,
            finished = finished,
            updatedAt = System.currentTimeMillis(),
            mediaId = episode.id,
            seriesId = series.seriesId,
            extension = episode.extension,
        )

        /** Puts the next episode at the front of Continue Watching (unless it's already started or watched). */
        private fun queueNextEpisode(
            series: SeriesEntity,
            next: XtreamEpisode,
        ) {
            val a = account.value ?: return
            viewModelScope.launch {
                val key = LibraryRepository.episodeKey(a.id, next.id)
                if (dao.getProgress(key) == null) {
                    dao.saveProgress(episodeEntry(a.id, series, next, 0, (next.durationSecs ?: 0) * 1000, finished = false))
                }
            }
        }

        /** Hold OK on an episode: mark it watched, or clear it back to unwatched. */
        fun toggleEpisodeWatched(
            series: SeriesEntity,
            episode: XtreamEpisode,
        ) {
            val a = account.value ?: return
            viewModelScope.launch {
                val key = LibraryRepository.episodeKey(a.id, episode.id)
                if (dao.getProgress(key)?.finished == true) {
                    dao.deleteProgress(key)
                } else {
                    val duration = (episode.durationSecs ?: 0) * 1000
                    dao.saveProgress(episodeEntry(a.id, series, episode, duration, duration, finished = true))
                }
            }
        }

        /** Seasons of a series (no TMDB lookup), for resuming an episode straight from Home. */
        suspend fun seasonsFor(series: SeriesEntity): Map<Int, List<XtreamEpisode>> {
            val a = account.value ?: return emptyMap()
            return runCatching { repository.seriesInfo(a, series)?.seasons.orEmpty() }.getOrDefault(emptyMap())
        }

        private fun save(entry: WatchProgressEntity) {
            if (entry.durationMs <= 0 || entry.positionMs < 10_000) return
            viewModelScope.launch { repository.saveProgress(entry) }
        }

        private fun isFinished(
            positionMs: Long,
            durationMs: Long,
        ) = durationMs > 0 && positionMs >= durationMs * 0.92

        private fun sortMovies(
            list: List<MovieEntity>,
            sort: LibrarySort,
        ): List<MovieEntity> =
            when (sort) {
                LibrarySort.ADDED -> list.sortedWith(compareByDescending<MovieEntity> { it.added }.thenBy { it.sortOrder })
                LibrarySort.AZ -> list.sortedBy { it.name.lowercase() }
                LibrarySort.RATING -> list.sortedByDescending { it.rating ?: 0.0 }
                LibrarySort.YEAR -> list.sortedByDescending { it.year ?: 0 }
            }

        private fun sortSeries(
            list: List<SeriesEntity>,
            sort: LibrarySort,
        ): List<SeriesEntity> =
            when (sort) {
                LibrarySort.ADDED -> list.sortedWith(compareByDescending<SeriesEntity> { it.lastModified }.thenBy { it.sortOrder })
                LibrarySort.AZ -> list.sortedBy { it.name.lowercase() }
                LibrarySort.RATING -> list.sortedByDescending { it.rating ?: 0.0 }
                LibrarySort.YEAR -> list.sortedByDescending { it.year ?: 0 }
            }
    }
