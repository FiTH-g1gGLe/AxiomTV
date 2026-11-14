package com.axiomtv.player

import com.axiomtv.AppSettings
import com.axiomtv.data.AxiomDao
import com.axiomtv.data.EngineMemoryEntity
import javax.inject.Inject
import javax.inject.Singleton

enum class Engine(
    val label: String,
) {
    EXO("ExoPlayer"),
    MPV("mpv"),
    ;

    fun other(): Engine = if (this == EXO) MPV else EXO
}

/** What the user picked in the player's engine menu. */
enum class EngineChoice(
    val label: String,
) {
    AUTO("Auto"),
    EXO("ExoPlayer"),
    MPV("mpv"),
}

/** Something to play: a live channel now, films and episodes later. */
data class MediaRequest(
    /** Stable id used to remember the engine, e.g. "live:1234" or "vod:567" */
    val key: String,
    val url: String,
    val isLive: Boolean,
    val title: String,
    /** Resume point for films/episodes; 0 = from the start */
    val startPositionMs: Long = 0,
)

/** The starting rules, before anything is learned: ExoPlayer for live TV, mpv for films and series (changeable in Settings). */
object EngineRules {
    fun pick(
        request: MediaRequest,
        settings: AppSettings?,
    ): Engine =
        if (request.isLive) {
            settings?.liveEngine ?: Engine.EXO
        } else {
            settings?.vodEngine ?: Engine.MPV
        }
}

data class RememberedEngine(
    val engine: Engine,
    val reason: String?,
    val pinned: Boolean,
)

@Singleton
class EngineMemoryStore
    @Inject
    constructor(
        private val dao: AxiomDao,
        val settings: AppSettings,
    ) {
        suspend fun forgetAll() {
            dao.deleteAllEngineMemory()
        }

        suspend fun get(key: String): RememberedEngine? {
            val entry = dao.getEngineMemory(key) ?: return null
            val engine = Engine.entries.firstOrNull { it.name == entry.engine } ?: return null
            return RememberedEngine(engine, entry.reason, entry.pinned)
        }

        suspend fun remember(
            key: String,
            engine: Engine,
            reason: String?,
            pinned: Boolean,
        ) {
            dao.saveEngineMemory(EngineMemoryEntity(key, engine.name, reason, pinned, System.currentTimeMillis()))
        }

        suspend fun forget(key: String) {
            dao.deleteEngineMemory(key)
        }
    }
