package com.axiomtv

import com.axiomtv.data.AxiomDao
import com.axiomtv.data.FavouriteEntity
import com.axiomtv.data.WatchProgressEntity
import com.axiomtv.player.Engine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupFavourite(
    val kind: String,
    val refId: Long,
    val createdAt: Long,
)

@Serializable
data class BackupProgress(
    val key: String,
    val kind: String,
    val title: String,
    val poster: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
    val mediaId: Long,
    val seriesId: Long? = null,
    val extension: String? = null,
)

/** Everything worth keeping when moving to a new box. Logins are never included. */
@Serializable
data class BackupFile(
    val app: String = "AxiomTV",
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val favourites: List<BackupFavourite> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val hiddenGroups: List<String> = emptyList(),
    val groupOrder: List<String> = emptyList(),
    val recentChannels: List<Long> = emptyList(),
    val liveEngine: String? = null,
    val vodEngine: String? = null,
    val startScreen: String? = null,
    val autoFallback: Boolean? = null,
    val switchDelaySeconds: Int? = null,
    val guideUrl: String? = null,
)

@Singleton
class BackupManager
    @Inject
    constructor(
        private val dao: AxiomDao,
        private val settings: AppSettings,
        private val groups: GroupPrefsStore,
    ) {
        private val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                encodeDefaults = true
            }

        suspend fun export(
            accountId: Long,
            recentChannels: List<Long>,
            guideUrl: String?,
        ): String {
            val favourites =
                dao.allFavourites().filter { it.accountId == accountId }.map { BackupFavourite(it.kind, it.refId, it.createdAt) }
            val progress =
                dao.allProgress().filter { it.key.split(":").getOrNull(1) == accountId.toString() }.map {
                    BackupProgress(
                        // stored without the account number so it fits any account
                        key = it.key.substringBefore(":"),
                        kind = it.kind,
                        title = it.title,
                        poster = it.poster,
                        positionMs = it.positionMs,
                        durationMs = it.durationMs,
                        finished = it.finished,
                        updatedAt = it.updatedAt,
                        mediaId = it.mediaId,
                        seriesId = it.seriesId,
                        extension = it.extension,
                    )
                }
            val prefs = groups.current
            return json.encodeToString(
                BackupFile.serializer(),
                BackupFile(
                    favourites = favourites,
                    progress = progress,
                    hiddenGroups = prefs.hidden.toList(),
                    groupOrder = prefs.order,
                    recentChannels = recentChannels,
                    liveEngine = settings.liveEngine.name,
                    vodEngine = settings.vodEngine.name,
                    startScreen = settings.startScreen,
                    autoFallback = settings.autoFallback,
                    switchDelaySeconds = settings.switchDelaySeconds,
                    guideUrl = guideUrl,
                ),
            )
        }

        /** @return the backup, already applied to the database and settings */
        suspend fun restore(
            accountId: Long,
            text: String,
        ): BackupFile {
            val backup = json.decodeFromString(BackupFile.serializer(), text)
            require(backup.app == "AxiomTV") { "Not an AxiomTV backup" }
            backup.favourites.forEach {
                dao.addFavourite(FavouriteEntity("${it.kind}:$accountId:${it.refId}", accountId, it.kind, it.refId, it.createdAt))
            }
            backup.progress.forEach {
                dao.saveProgress(
                    WatchProgressEntity(
                        key = "${it.key}:$accountId:${it.mediaId}",
                        kind = it.kind,
                        title = it.title,
                        poster = it.poster,
                        positionMs = it.positionMs,
                        durationMs = it.durationMs,
                        finished = it.finished,
                        updatedAt = it.updatedAt,
                        mediaId = it.mediaId,
                        seriesId = it.seriesId,
                        extension = it.extension,
                    ),
                )
            }
            groups.set(GroupPrefs(hidden = backup.hiddenGroups.toSet(), order = backup.groupOrder))
            backup.liveEngine?.let { name -> Engine.entries.firstOrNull { it.name == name }?.let { settings.updateLiveEngine(it) } }
            backup.vodEngine?.let { name -> Engine.entries.firstOrNull { it.name == name }?.let { settings.updateVodEngine(it) } }
            backup.startScreen?.let { settings.updateStartScreen(it) }
            backup.autoFallback?.let { settings.updateAutoFallback(it) }
            backup.switchDelaySeconds?.let { settings.updateSwitchDelay(it) }
            return backup
        }
    }
