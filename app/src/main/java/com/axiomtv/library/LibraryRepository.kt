package com.axiomtv.library

import android.content.Context
import com.axiomtv.data.AccountEntity
import com.axiomtv.data.AxiomDao
import com.axiomtv.data.MediaCategoryEntity
import com.axiomtv.data.MovieEntity
import com.axiomtv.data.SeriesEntity
import com.axiomtv.data.WatchProgressEntity
import com.axiomtv.xtream.XtreamClient
import com.axiomtv.xtream.XtreamEpisode
import com.axiomtv.xtream.XtreamLogin
import com.axiomtv.xtream.XtreamSeriesInfo
import com.axiomtv.xtream.XtreamVodInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository
    @Inject
    constructor(
        private val dao: AxiomDao,
        private val xtream: XtreamClient,
        val tmdb: TmdbClient,
        @param:ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("axiomtv", Context.MODE_PRIVATE)

        fun movieCategories(accountId: Long): Flow<List<MediaCategoryEntity>> = dao.observeMediaCategories(accountId, "movie")

        fun seriesCategories(accountId: Long): Flow<List<MediaCategoryEntity>> = dao.observeMediaCategories(accountId, "series")

        fun movies(
            accountId: Long,
            categoryId: String?,
        ): Flow<List<MovieEntity>> =
            when (categoryId) {
                null -> dao.observeMovies(accountId)
                com.axiomtv.data.AxiomRepository.FAVOURITES_CATEGORY -> dao.observeFavouriteMovies(accountId)
                else -> dao.observeMoviesIn(accountId, categoryId)
            }

        fun series(
            accountId: Long,
            categoryId: String?,
        ): Flow<List<SeriesEntity>> =
            when (categoryId) {
                null -> dao.observeSeries(accountId)
                com.axiomtv.data.AxiomRepository.FAVOURITES_CATEGORY -> dao.observeFavouriteSeries(accountId)
                else -> dao.observeSeriesIn(accountId, categoryId)
            }

        fun progress(prefix: String): Flow<List<WatchProgressEntity>> = dao.observeProgressWithPrefix(prefix)

        fun needsSync(account: AccountEntity): Boolean =
            System.currentTimeMillis() - prefs.getLong("library_sync_${account.id}", 0L) > DAY_MS

        /** Downloads the full film and series lists. */
        suspend fun sync(
            account: AccountEntity,
            onStatus: suspend (String) -> Unit,
        ) {
            val login = account.toLogin()
            val started = System.currentTimeMillis()

            onStatus("Loading films...")
            val movieCategories =
                xtream.vodCategories(login).mapIndexed { index, category ->
                    MediaCategoryEntity(account.id, "movie", category.id, NameCleaner.category(category.name), index)
                }
            val movies =
                xtream.vodStreams(login).mapIndexed { index, vod ->
                    val cleaned = NameCleaner.clean(vod.name)
                    MovieEntity(
                        accountId = account.id,
                        streamId = vod.streamId,
                        name = cleaned.name,
                        rawName = vod.name,
                        year = cleaned.year,
                        poster = vod.icon,
                        rating = vod.rating,
                        added = vod.added,
                        categoryId = vod.categoryId,
                        extension = vod.extension,
                        tmdbId = vod.tmdbId,
                        quality = cleaned.quality,
                        sortOrder = index,
                    )
                }
            dao.replaceMovies(account.id, movieCategories, movies)

            onStatus("Loading series...")
            val seriesCategories =
                xtream.seriesCategories(login).mapIndexed { index, category ->
                    MediaCategoryEntity(account.id, "series", category.id, NameCleaner.category(category.name), index)
                }
            val series =
                xtream.seriesList(login).mapIndexed { index, item ->
                    val cleaned = NameCleaner.clean(item.name)
                    SeriesEntity(
                        accountId = account.id,
                        seriesId = item.seriesId,
                        name = cleaned.name,
                        rawName = item.name,
                        year = item.year ?: cleaned.year,
                        poster = item.cover,
                        backdrop = item.backdrop,
                        plot = item.plot,
                        rating = item.rating,
                        categoryId = item.categoryId,
                        lastModified = item.lastModified,
                        tmdbId = item.tmdbId,
                        sortOrder = index,
                    )
                }
            dao.replaceSeries(account.id, seriesCategories, series)

            prefs.edit().putLong("library_sync_${account.id}", System.currentTimeMillis()).apply()
            Timber.i(
                "Library synced: %d films, %d series in %d ms",
                movies.size,
                series.size,
                System.currentTimeMillis() - started,
            )
        }

        suspend fun movieInfo(
            account: AccountEntity,
            movie: MovieEntity,
        ): XtreamVodInfo? =
            try {
                xtream.vodInfo(account.toLogin(), movie.streamId)
            } catch (ex: Exception) {
                Timber.w(ex, "Film info failed")
                null
            }

        suspend fun seriesInfo(
            account: AccountEntity,
            series: SeriesEntity,
        ): XtreamSeriesInfo? =
            try {
                xtream.seriesInfo(account.toLogin(), series.seriesId)
            } catch (ex: Exception) {
                Timber.w(ex, "Series info failed")
                null
            }

        fun movieUrl(
            account: AccountEntity,
            movie: MovieEntity,
        ): String = xtream.movieUrl(account.toLogin(), movie.streamId, movie.extension)

        fun episodeUrl(
            account: AccountEntity,
            episode: XtreamEpisode,
        ): String = xtream.episodeUrl(account.toLogin(), episode.id, episode.extension)

        suspend fun getProgress(key: String): WatchProgressEntity? = dao.getProgress(key)

        suspend fun saveProgress(entry: WatchProgressEntity) = dao.saveProgress(entry)

        private fun AccountEntity.toLogin() = XtreamLogin(server, username, password)

        companion object {
            private const val DAY_MS = 24L * 60 * 60 * 1000

            fun movieKey(
                accountId: Long,
                streamId: Long,
            ) = "movie:$accountId:$streamId"

            fun episodeKey(
                accountId: Long,
                episodeId: Long,
            ) = "ep:$accountId:$episodeId"
        }
    }
