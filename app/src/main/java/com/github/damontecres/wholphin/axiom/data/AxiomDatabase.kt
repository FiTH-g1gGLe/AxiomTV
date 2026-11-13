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

    @Query("SELECT COUNT(*) FROM programmes WHERE accountId = :accountId")
    abstract suspend fun programmeCount(accountId: Long): Int

    @Query("SELECT * FROM engine_memory WHERE `key` = :key")
    abstract suspend fun getEngineMemory(key: String): EngineMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveEngineMemory(entry: EngineMemoryEntity)

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
    ],
    version = 3,
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideAxiomDao(database: AxiomDatabase): AxiomDao = database.dao()
}
