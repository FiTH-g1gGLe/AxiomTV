@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.axiom.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.damontecres.wholphin.mpv.MpvPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * AxiomTV's hybrid engine. Picks ExoPlayer or mpv for each stream, and switches
 * automatically when the chosen engine can't play it.
 *
 * Order of choice: user's pinned engine > remembered engine > rules (live = ExoPlayer, VOD = mpv).
 * Switches when: a playback error, nothing playing within [START_TIMEOUT_MS], or (ExoPlayer)
 * the stream has audio/video but none of it can be decoded (the "quiet failure").
 *
 * All methods must be called on the main thread.
 */
class HybridPlayer(
    private val context: Context,
    private val memory: EngineMemoryStore,
    private val scope: CoroutineScope,
) {
    /** The current Media3 player, for the video surface. Changes when the engine switches. */
    var player: Player? by mutableStateOf(null)
        private set
    var engine: Engine by mutableStateOf(Engine.EXO)
        private set
    var choice: EngineChoice by mutableStateOf(EngineChoice.AUTO)
        private set
    var buffering by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var videoInfo by mutableStateOf("")
        private set

    /** True once a film or episode has played to the end. */
    var ended by mutableStateOf(false)
        private set

    /** Short message such as "Switched to mpv - audio format not supported". */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Increments every time [notice] changes, so the UI can re-show it. */
    var noticeCount by mutableIntStateOf(0)
        private set

    private var request: MediaRequest? = null

    /** Where playback had got to, so a fallback to the other engine resumes there. */
    private fun resumePoint(): Long {
        val p = player ?: return 0
        return if (p.currentPosition > 0) p.currentPosition else 0
    }
    private val triedEngines = mutableSetOf<Engine>()
    private var pinned = false
    private var startJob: Job? = null
    private var timeoutJob: Job? = null
    private var failureHandled = false

    private val listener =
        object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                Timber.e(playbackError, "%s error (%s)", engine.label, playbackError.errorCodeName)
                fail(describe(playbackError))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_ENDED) ended = true
                if (playbackState == Player.STATE_READY) {
                    timeoutJob?.cancel()
                    error = null
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) videoInfo = "${videoSize.height}p"
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (engine != Engine.EXO) return
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                when {
                    audio.isNotEmpty() && audio.none { it.isSupported } -> fail("audio format not supported")
                    video.isNotEmpty() && video.none { it.isSupported } -> fail("video format not supported")
                }
            }
        }

    /** Start playing something new. */
    fun play(newRequest: MediaRequest) {
        startJob?.cancel()
        startJob =
            scope.launch {
                request = newRequest
                triedEngines.clear()
                val remembered = memory.get(newRequest.key)
                pinned = remembered?.pinned == true
                choice =
                    when {
                        remembered?.pinned == true && remembered.engine == Engine.EXO -> EngineChoice.EXO
                        remembered?.pinned == true && remembered.engine == Engine.MPV -> EngineChoice.MPV
                        else -> EngineChoice.AUTO
                    }
                val chosen = remembered?.engine ?: EngineRules.pick(newRequest)
                Timber.i(
                    "Play %s on %s (%s)",
                    newRequest.title,
                    chosen.label,
                    if (remembered != null) "remembered: ${remembered.reason}" else "rules",
                )
                start(chosen)
            }
    }

    /** The user picked an engine from the menu. */
    fun chooseEngine(newChoice: EngineChoice) {
        val current = request ?: return
        scope.launch {
            choice = newChoice
            when (newChoice) {
                EngineChoice.AUTO -> {
                    pinned = false
                    memory.forget(current.key)
                }

                EngineChoice.EXO, EngineChoice.MPV -> {
                    pinned = true
                    val pick = if (newChoice == EngineChoice.EXO) Engine.EXO else Engine.MPV
                    memory.remember(current.key, pick, "chosen by you", pinned = true)
                }
            }
            triedEngines.clear()
            val target =
                when (newChoice) {
                    EngineChoice.EXO -> Engine.EXO
                    EngineChoice.MPV -> Engine.MPV
                    EngineChoice.AUTO -> EngineRules.pick(current)
                }
            start(target)
        }
    }

    fun release() {
        startJob?.cancel()
        timeoutJob?.cancel()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
    }

    private suspend fun start(target: Engine) {
        val current = request ?: return
        timeoutJob?.cancel()
        failureHandled = false
        ended = false
        triedEngines += target
        error = null
        videoInfo = ""
        buffering = true
        val active = ensurePlayer(target)
        if (target == Engine.MPV) {
            // mpv starts up in the background; wait until it accepts media
            withTimeoutOrNull(MPV_READY_TIMEOUT_MS) {
                while (!active.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) delay(50)
            }
        }
        if (!current.isLive && current.startPositionMs > 0) {
            active.setMediaItem(MediaItem.fromUri(current.url), current.startPositionMs)
        } else {
            active.setMediaItem(MediaItem.fromUri(current.url))
        }
        active.prepare()
        active.playWhenReady = true
        timeoutJob =
            scope.launch {
                delay(START_TIMEOUT_MS)
                if (active.playbackState != Player.STATE_READY) {
                    Timber.w("%s did not start within %d ms", target.label, START_TIMEOUT_MS)
                    fail("took too long to start")
                }
            }
    }

    private fun ensurePlayer(target: Engine): Player {
        val existing = player
        if (existing != null && engine == target) return existing
        existing?.let {
            it.removeListener(listener)
            it.release()
        }
        val created =
            when (target) {
                Engine.EXO -> createExoPlayer()
                Engine.MPV -> MpvPlayer(context, true, false)
            }
        created.addListener(listener)
        engine = target
        player = created
        return created
    }

    private fun fail(reason: String) {
        if (failureHandled) return
        failureHandled = true
        timeoutJob?.cancel()
        val current = request ?: return
        val other = engine.other()
        if (!pinned && other !in triedEngines) {
            Timber.w("Switching %s -> %s: %s", engine.label, other.label, reason)
            showNotice("Switched to ${other.label} - $reason")
            if (!current.isLive) {
                val position = resumePoint()
                if (position > 0) request = current.copy(startPositionMs = position)
            }
            scope.launch {
                memory.remember(current.key, other, reason, pinned = false)
                start(other)
            }
        } else {
            error = "Couldn't play this ($reason)"
            if (!pinned) {
                scope.launch { memory.forget(current.key) }
            }
        }
    }

    private fun showNotice(message: String) {
        notice = message
        noticeCount++
    }

    private fun createExoPlayer(): ExoPlayer {
        val renderers =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
        // Xtream servers usually redirect (302) each stream, sometimes between http and https.
        val httpClient =
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        val httpFactory = OkHttpDataSource.Factory(httpClient).setUserAgent(USER_AGENT)
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        return ExoPlayer
            .Builder(context, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
            }
    }

    private fun describe(error: PlaybackException): String =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> "format not supported"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            -> "stream format problem"

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "server refused the stream"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "network problem"

            else -> error.errorCodeName.lowercase().removePrefix("error_code_").replace('_', ' ')
        }

    companion object {
        const val START_TIMEOUT_MS = 15_000L
        private const val MPV_READY_TIMEOUT_MS = 3_000L
        private const val USER_AGENT = "AxiomTV"
    }
}
