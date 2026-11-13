package com.github.damontecres.wholphin.axiom.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    val expiry: Long? = null,
    val lastSync: Long = 0,
    /** Custom XMLTV guide link; null = use the provider's own guide */
    val epgUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val lastEpgSync: Long = 0,
)

@Entity(tableName = "categories", primaryKeys = ["accountId", "categoryId"])
data class CategoryEntity(
    val accountId: Long,
    val categoryId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["accountId", "streamId"],
    indices = [Index(value = ["accountId", "categoryId"])],
)
data class ChannelEntity(
    val accountId: Long,
    val streamId: Long,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val hasArchive: Boolean,
    val sortOrder: Int,
)

/** Film/series category. kind = "movie" or "series". */
@Entity(tableName = "media_categories", primaryKeys = ["accountId", "kind", "categoryId"])
data class MediaCategoryEntity(
    val accountId: Long,
    val kind: String,
    val categoryId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "movies",
    primaryKeys = ["accountId", "streamId"],
    indices = [Index(value = ["accountId", "categoryId"])],
)
data class MovieEntity(
    val accountId: Long,
    val streamId: Long,
    /** Cleaned-up title, e.g. "EN | Dune: Part Two (2024) 4K" -> "Dune: Part Two" */
    val name: String,
    val rawName: String,
    val year: Int?,
    val poster: String?,
    val rating: Double?,
    val added: Long,
    val categoryId: String?,
    val extension: String,
    val tmdbId: String?,
    val quality: String?,
    val sortOrder: Int,
)

@Entity(
    tableName = "series",
    primaryKeys = ["accountId", "seriesId"],
    indices = [Index(value = ["accountId", "categoryId"])],
)
data class SeriesEntity(
    val accountId: Long,
    val seriesId: Long,
    val name: String,
    val rawName: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val rating: Double?,
    val categoryId: String?,
    val lastModified: Long,
    val tmdbId: String?,
    val sortOrder: Int,
)

/** Where you got to in a film or episode. key = "movie:<account>:<id>" or "ep:<account>:<id>" */
@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val title: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
    /** film stream id or episode id */
    val mediaId: Long,
    val seriesId: Long?,
    val extension: String?,
)

/** A favourite channel ("channel"), film ("movie") or series ("series"). */
@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val key: String,
    val accountId: Long,
    val kind: String,
    val refId: Long,
    val createdAt: Long,
)

/** One TV guide entry. epgChannelId is stored lower-case. */
@Entity(
    tableName = "programmes",
    indices = [Index(value = ["accountId", "epgChannelId", "start"])],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val epgChannelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Which playback engine a channel or title needs. Learned automatically when an engine
 * fails, or pinned by the user from the player menu.
 */
@Entity(tableName = "engine_memory")
data class EngineMemoryEntity(
    @PrimaryKey val key: String,
    val engine: String,
    val reason: String?,
    val pinned: Boolean,
    val updatedAt: Long,
)

@Dao
abstract class AxiomDao {
    // ---- Search ----
    @Query("SELECT * FROM channels WHERE accountId = :accountId AND name LIKE '%' || :query || '%' ORDER BY sortOrder LIMIT 40")
    abstract suspend fun searchChannels(
        accountId: Long,
        query: String,
    ): List<ChannelEntity>

    @Query("SELECT * FROM movies WHERE accountId = :accountId AND name LIKE '%' || :query || '%' ORDER BY added DESC LIMIT 60")
    abstract suspend fun searchMovies(
        accountId: Long,
        query: String,
    ): List<MovieEntity>

    @Query("SELECT * FROM series WHERE accountId = :accountId AND name LIKE '%' || :query || '%' ORDER BY lastModified DESC LIMIT 60")
    abstract suspend fun searchSeries(
        accountId: Long,
        query: String,
    ): List<SeriesEntity>

    // ---- Favourites & Home ----
    @Query("SELECT refId FROM favourites WHERE accountId = :accountId AND kind = :kind")
    abstract fun observeFavouriteIds(
        accountId: Long,
        kind: String,
    ): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addFavourite(entry: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE `key` = :key")
    abstract suspend fun removeFavourite(key: String)

    @Query("SELECT COUNT(*) FROM favourites WHERE `key` = :key")
    abstract suspend fun isFavourite(key: String): Int

    @Query(
        "SELECT c.* FROM channels c INNER JOIN favourites f ON f.refId = c.streamId AND f.accountId = c.accountId " +
            "AND f.kind = 'channel' WHERE c.accountId = :accountId ORDER BY f.createdAt",
    )
    abstract fun observeFavouriteChannels(accountId: Long): Flow<List<ChannelEntity>>

    @Query(
        "SELECT m.* FROM movies m INNER JOIN favourites f ON f.refId = m.streamId AND f.accountId = m.accountId " +
            "AND f.kind = 'movie' WHERE m.accountId = :accountId ORDER BY f.createdAt DESC",
    )
    abstract fun observeFavouriteMovies(accountId: Long): Flow<List<MovieEntity>>

    @Query(
        "SELECT s.* FROM series s INNER JOIN favourites f ON f.refId = s.seriesId AND f.accountId = s.accountId " +
            "AND f.kind = 'series' WHERE s.accountId = :accountId ORDER BY f.createdAt DESC",
    )
    abstract fun observeFavouriteSeries(accountId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM movies WHERE accountId = :accountId ORDER BY added DESC LIMIT :limit")
    abstract fun observeRecentMovies(
        accountId: Long,
        limit: Int,
    ): Flow<List<MovieEntity>>

    @Query("SELECT * FROM series WHERE accountId = :accountId ORDER BY lastModified DESC LIMIT :limit")
    abstract fun observeRecentSeries(
        accountId: Long,
        limit: Int,
    ): Flow<List<SeriesEntity>>

    @Query(
        "SELECT * FROM watch_progress WHERE finished = 0 AND (`key` LIKE :moviePrefix || '%' OR `key` LIKE :episodePrefix || '%') " +
            "ORDER BY updatedAt DESC LIMIT 20",
    )
    abstract fun observeContinueWatching(
        moviePrefix: String,
        episodePrefix: String,
    ): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM series WHERE accountId = :accountId AND seriesId = :seriesId")
    abstract suspend fun getSeries(
        accountId: Long,
        seriesId: Long,
    ): SeriesEntity?

    // ---- Films & series ----
    @Query("SELECT * FROM media_categories WHERE accountId = :accountId AND kind = :kind ORDER BY sortOrder")
    abstract fun observeMediaCategories(
        accountId: Long,
        kind: String,
    ): Flow<List<MediaCategoryEntity>>

    @Query("SELECT * FROM movies WHERE accountId = :accountId")
    abstract fun observeMovies(accountId: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE accountId = :accountId AND categoryId = :categoryId")
    abstract fun observeMoviesIn(
        accountId: Long,
        categoryId: String,
    ): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE accountId = :accountId AND streamId = :streamId")
    abstract suspend fun getMovie(
        accountId: Long,
        streamId: Long,
    ): MovieEntity?

    @Query("SELECT * FROM series WHERE accountId = :accountId")
    abstract fun observeSeries(accountId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE accountId = :accountId AND categoryId = :categoryId")
    abstract fun observeSeriesIn(
        accountId: Long,
        categoryId: String,
    ): Flow<List<SeriesEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE accountId = :accountId")
    abstract suspend fun movieCount(accountId: Long): Int

    @Query("DELETE FROM media_categories WHERE accountId = :accountId AND kind = :kind")
    abstract suspend fun deleteMediaCategories(
        accountId: Long,
        kind: String,
    )

    @Query("DELETE FROM movies WHERE accountId = :accountId")
    abstract suspend fun deleteMovies(accountId: Long)

    @Query("DELETE FROM series WHERE accountId = :accountId")
    abstract suspend fun deleteSeries(accountId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMediaCategories(items: List<MediaCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMovies(items: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSeries(items: List<SeriesEntity>)

    @Transaction
    open suspend fun replaceMovies(
        accountId: Long,
        categories: List<MediaCategoryEntity>,
        movies: List<MovieEntity>,
    ) {
        deleteMediaCategories(accountId, "movie")
        deleteMovies(accountId)
        insertMediaCategories(categories)
        movies.chunked(500).forEach { insertMovies(it) }
    }

    @Transaction
    open suspend fun replaceSeries(
        accountId: Long,
        categories: List<MediaCategoryEntity>,
        series: List<SeriesEntity>,
    ) {
        deleteMediaCategories(accountId, "series")
        deleteSeries(accountId)
        insertMediaCategories(categories)
        series.chunked(500).forEach { insertSeries(it) }
    }

    @Query("SELECT * FROM watch_progress WHERE `key` = :key")
    abstract suspend fun getProgress(key: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE `key` LIKE :prefix || '%'")
    abstract fun observeProgressWithPrefix(prefix: String): Flow<List<WatchProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveProgress(entry: WatchProgressEntity)

    @Query("SELECT * FROM favourites")
    abstract suspend fun allFavourites(): List<FavouriteEntity>

    @Query("DELETE FROM watch_progress WHERE `key` = :key")
    abstract suspend fun deleteProgress(key: String)

    @Query("SELECT * FROM watch_progress")
    abstract suspend fun allProgress(): List<WatchProgressEntity>

    @Query("DELETE FROM media_categories")
    abstract suspend fun deleteAllMediaCategories()

    @Query("DELETE FROM movies")
    abstract suspend fun deleteAllMovies()

    @Query("DELETE FROM series")
    abstract suspend fun deleteAllSeries()

    @Query(
        "SELECT * FROM programmes WHERE accountId = :accountId AND epgChannelId = :epgChannelId " +
            "AND stop > :from AND start < :to ORDER BY start",
    )
    abstract suspend fun programmes(
        accountId: Long,
        epgChannelId: String,
        from: Long,
        to: Long,
    ): List<ProgrammeEntity>

    @Insert
    abstract suspend fun insertProgrammes(items: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE accountId = :accountId")
    abstract suspend fun deleteProgrammes(accountId: Long)

    @Query("DELETE FROM programmes")
    abstract suspend fun deleteAllProgrammes()

    @Query("SELECT DISTINCT epgChannelId FROM channels WHERE accountId = :accountId AND epgChannelId IS NOT NULL")
    abstract suspend fun epgChannelIds(accountId: Long): List<String>

    @Query(
        "SELECT DISTINCT epgChannelId FROM channels WHERE accountId = :accountId AND hasArchive = 1 AND epgChannelId IS NOT NULL",
    )
    abstract suspend fun archiveEpgChannelIds(accountId: Long): List<String>

    @Query("SELECT * FROM channels WHERE accountId = :accountId AND hasArchive = 1 ORDER BY sortOrder")
    abstract fun observeArchiveChannels(accountId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM programmes WHERE accountId = :accountId")
    abstract suspend fun programmeCount(accountId: Long): Int

    @Query("SELECT * FROM engine_memory WHERE `key` = :key")
    abstract suspend fun getEngineMemory(key: String): EngineMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveEngineMemory(entry: EngineMemoryEntity)

    @Query("DELETE FROM engine_memory")
    abstract suspend fun deleteAllEngineMemory()

    @Query("DELETE FROM engine_memory WHERE `key` = :key")
    abstract suspend fun deleteEngineMemory(key: String)

    @Query("SELECT * FROM accounts ORDER BY id LIMIT 1")
    abstract fun observeAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts ORDER BY id LIMIT 1")
    abstract suspend fun getAccount(): AccountEntity?

    @Insert
    abstract suspend fun insertAccount(account: AccountEntity): Long

    @Update
    abstract suspend fun updateAccount(account: AccountEntity)

    @Query("DELETE FROM accounts")
    abstract suspend fun deleteAccounts()

    @Query("SELECT * FROM categories WHERE accountId = :accountId ORDER BY sortOrder")
    abstract fun observeCategories(accountId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM channels WHERE accountId = :accountId ORDER BY sortOrder")
    abstract fun observeAllChannels(accountId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE accountId = :accountId AND categoryId = :categoryId ORDER BY sortOrder")
    abstract fun observeChannels(
        accountId: Long,
        categoryId: String,
    ): Flow<List<ChannelEntity>>

    @Query("DELETE FROM categories")
    abstract suspend fun deleteAllCategories()

    @Query("DELETE FROM channels")
    abstract suspend fun deleteAllChannels()

    @Query("DELETE FROM categories WHERE accountId = :accountId")
    abstract suspend fun deleteCategories(accountId: Long)

    @Query("DELETE FROM channels WHERE accountId = :accountId")
    abstract suspend fun deleteChannels(accountId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChannels(items: List<ChannelEntity>)

    /** Swaps in a freshly downloaded channel list in one go. */
    @Transaction
    open suspend fun replaceLive(
        accountId: Long,
        categories: List<CategoryEntity>,
        channels: List<ChannelEntity>,
    ) {
        deleteCategories(accountId)
        deleteChannels(accountId)
        insertCategories(categories)
        channels.chunked(500).forEach { insertChannels(it) }
    }

    @Transaction
    open suspend fun clearEverything() {
        deleteAllMovies()
        deleteAllSeries()
        deleteAllMediaCategories()
        deleteAllProgrammes()
        deleteAllChannels()
        deleteAllCategories()
        deleteAccounts()
    }
}

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        EngineMemoryEntity::class,
        ProgrammeEntity::class,
        MediaCategoryEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        WatchProgressEntity::class,
        FavouriteEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AxiomDatabase : RoomDatabase() {
    abstract fun dao(): AxiomDao
}

private val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `engine_memory` (" +
                    "`key` TEXT NOT NULL, `engine` TEXT NOT NULL, `reason` TEXT, " +
                    "`pinned` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
        }
    }

private val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `accounts` ADD COLUMN `epgUrl` TEXT")
            db.execSQL("ALTER TABLE `accounts` ADD COLUMN `lastEpgSync` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `programmes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, " +
                    "`epgChannelId` TEXT NOT NULL, `start` INTEGER NOT NULL, `stop` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, `description` TEXT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_programmes_accountId_epgChannelId_start` " +
                    "ON `programmes` (`accountId`, `epgChannelId`, `start`)",
            )
        }
    }

private val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `media_categories` (`accountId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                    "`categoryId` TEXT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `kind`, `categoryId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `movies` (`accountId` INTEGER NOT NULL, `streamId` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, `rawName` TEXT NOT NULL, `year` INTEGER, `poster` TEXT, `rating` REAL, " +
                    "`added` INTEGER NOT NULL, `categoryId` TEXT, `extension` TEXT NOT NULL, `tmdbId` TEXT, " +
                    "`quality` TEXT, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `streamId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_accountId_categoryId` ON `movies` (`accountId`, `categoryId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `series` (`accountId` INTEGER NOT NULL, `seriesId` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, `rawName` TEXT NOT NULL, `year` INTEGER, `poster` TEXT, `backdrop` TEXT, " +
                    "`plot` TEXT, `rating` REAL, `categoryId` TEXT, `lastModified` INTEGER NOT NULL, `tmdbId` TEXT, " +
                    "`sortOrder` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `seriesId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_accountId_categoryId` ON `series` (`accountId`, `categoryId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `watch_progress` (`key` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `poster` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                    "`finished` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, " +
                    "`seriesId` INTEGER, `extension` TEXT, PRIMARY KEY(`key`))",
            )
        }
    }

private val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `favourites` (`key` TEXT NOT NULL, `accountId` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `refId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object AxiomDatabaseModule {
    @Provides
    @Singleton
    fun provideAxiomDatabase(
        @ApplicationContext context: Context,
    ): AxiomDatabase =
        Room
            .databaseBuilder(context, AxiomDatabase::class.java, "axiomtv.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideAxiomDao(database: AxiomDatabase): AxiomDao = database.dao()
}
