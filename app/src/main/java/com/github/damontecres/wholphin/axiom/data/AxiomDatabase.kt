package com.github.damontecres.wholphin.axiom.data

import android.content.Context
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

@Dao
abstract class AxiomDao {
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
        deleteAllChannels()
        deleteAllCategories()
        deleteAccounts()
    }
}

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, ChannelEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AxiomDatabase : RoomDatabase() {
    abstract fun dao(): AxiomDao
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
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideAxiomDao(database: AxiomDatabase): AxiomDao = database.dao()
}
