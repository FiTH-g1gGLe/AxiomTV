@file:OptIn(markerClass = [UnstableApi::class])

package com.axiomtv.player

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.axiomtv.player.mpv.MpvPlayer
import com.axiomtv.player.mpv.MpvPlayerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Locale

/**
 * AxiomTV's hybrid engine. Picks ExoPlayer or mpv for each stream, and switches
 * automatically when the chosen engine can't play it.
 *
 * Order of choice: user's pinned engine > remembered engine > rules (live = ExoPlayer, VOD = mpv).
 * Switches when: a playback error, nothing playing within the switch delay (Settings), or (ExoPlayer)
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

    /** Current audio/video/subtitle tracks (both engines). */
    var tracks: Tracks by mutableStateOf(Tracks.EMPTY)
        private set

    /** Subtitle lines to draw (ExoPlayer only; mpv draws its own). */
    var cues: List<Cue> by mutableStateOf(emptyList())
        private set

    /** True once a film or episode has played to the end. */
    /** Chapters of the current file (mpv only), used for Skip intro and Next episode. */
    var chapters by mutableStateOf<List<PlaybackChapter>>(emptyList())
        private set

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

    /** Playback reached READY at least once for the current attempt (so a later error is a drop, not a bad stream). */
    private var hasStarted = false

    private fun loadChapters() {
        val mpv = player as? MpvPlayer ?: return
        scope.launch(Dispatchers.IO) {
            val list = runCatching { mpv.readChapters() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (player === mpv) {
                    chapters = list.map { PlaybackChapter(it.first, it.second) }
                    if (list.isNotEmpty()) Timber.i("Chapters: %s", list.joinToString { it.first })
                }
            }
        }
    }

    /** Reconnect attempts on the same engine after a mid-playback drop. */
    private var reconnects = 0

    /** mpv starts up in the background; if it wasn't ready to prepare yet, do it as soon as it is. */
    private var pendingPrepare = false

    private val listener =
        object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                Timber.e(playbackError, "%s error (%s)", engine.label, playbackError.errorCodeName)
                val active = player
                // Live stream fell behind the provider's window: jump back to the live edge
                if (playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && active != null) {
                    Timber.i("Behind live window, rejoining the live edge")
                    active.seekToDefaultPosition()
                    active.prepare()
                    return
                }
                // The stream dropped after it had been playing: reconnect on the same engine first
                if (hasStarted && reconnects < MAX_RECONNECTS && isNetworkError(playbackError)) {
                    reconnects++
                    val attempt = reconnects
                    Timber.w("Stream dropped, reconnecting (%d/%d)", attempt, MAX_RECONNECTS)
                    showNotice("Connection dropped, reconnecting...")
                    val current = request
                    if (current != null && !current.isLive) {
                        val position = resumePoint()
                        if (position > 0) request = current.copy(startPositionMs = position)
                    }
                    scope.launch {
                        delay(1000L * attempt)
                        triedEngines -= engine
                        start(engine)
                    }
                    return
                }
                fail(describe(playbackError))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_ENDED) ended = true
                if (playbackState == Player.STATE_READY) {
                    timeoutJob?.cancel()
                    error = null
                    if (!hasStarted) loadChapters()
                    hasStarted = true
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) videoInfo = "${videoSize.height}p"
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                val p = player ?: return
                if (pendingPrepare && availableCommands.contains(Player.COMMAND_PREPARE)) {
                    pendingPrepare = false
                    Timber.i("%s ready, starting playback", engine.label)
                    p.prepare()
                    p.playWhenReady = true
                    startTimeout(p, engine)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onTracksChanged(tracks: Tracks) {
                this@HybridPlayer.tracks = tracks
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
                reconnects = 0
                val remembered = memory.get(newRequest.key)
                pinned = remembered?.pinned == true
                choice =
                    when {
                        remembered?.pinned == true && remembered.engine == Engine.EXO -> EngineChoice.EXO
                        remembered?.pinned == true && remembered.engine == Engine.MPV -> EngineChoice.MPV
                        else -> EngineChoice.AUTO
                    }
                val chosen = remembered?.engine ?: EngineRules.pick(newRequest, memory.settings)
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
                    EngineChoice.AUTO -> EngineRules.pick(current, memory.settings)
                }
            start(target)
        }
    }

    /** Audio tracks to choose from. */
    fun audioOptions(): List<TrackOption> = options(C.TRACK_TYPE_AUDIO)

    /** Subtitle tracks, with "Off" first. */
    fun subtitleOptions(): List<TrackOption> {
        val list = options(C.TRACK_TYPE_TEXT)
        val off = TrackOption("Off", null, selected = list.none { it.selected }, group = null, index = -1, type = C.TRACK_TYPE_TEXT)
        return listOf(off) + list
    }

    fun selectTrack(option: TrackOption) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null) {
            builder.setTrackTypeDisabled(option.type, true)
        } else {
            builder
                .setTrackTypeDisabled(option.type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        p.trackSelectionParameters = builder.build()
        Timber.i("Selected %s track: %s", if (option.type == C.TRACK_TYPE_AUDIO) "audio" else "subtitle", option.label)
    }

    private fun options(type: Int): List<TrackOption> =
        tracks.groups
            .filter { it.type == type }
            .flatMap { group ->
                (0 until group.length).map { i ->
                    val format = group.getTrackFormat(i)
                    TrackOption(
                        label = trackName(format, i),
                        detail = trackDetail(format, type),
                        selected = group.isTrackSelected(i),
                        group = group,
                        index = i,
                        type = type,
                    )
                }
            }

    private fun trackName(
        format: Format,
        index: Int,
    ): String {
        val language =
            format.language
                ?.takeIf { it.isNotBlank() && it != "und" }
                ?.let { code -> Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() } ?: code.uppercase() }
        val label = format.label?.takeIf { it.isNotBlank() }
        return when {
            label != null && language != null && !label.contains(language, ignoreCase = true) -> "$language · $label"
            label != null -> label
            language != null -> language
            else -> "Track ${index + 1}"
        }
    }

    private fun trackDetail(
        format: Format,
        type: Int,
    ): String? {
        val parts = mutableListOf<String>()
        if (type == C.TRACK_TYPE_AUDIO) {
            when (format.sampleMimeType) {
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                else -> null
            }?.let { parts += it }
            when (format.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> null
            }?.let { parts += it }
        } else {
            when (format.sampleMimeType ?: format.codecs) {
                MimeTypes.TEXT_SSA -> "ASS"
                MimeTypes.APPLICATION_SUBRIP -> "SRT"
                MimeTypes.APPLICATION_PGS -> "PGS"
                MimeTypes.TEXT_VTT -> "WebVTT"
                else -> null
            }?.let { parts += it }
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) parts += "SDH"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
        hasStarted = false
        chapters = emptyList()
        ended = false
        tracks = Tracks.EMPTY
        cues = emptyList()
        triedEngines += target
        error = null
        videoInfo = ""
        buffering = true
        val active = ensurePlayer(target)
        pendingPrepare = false
        if (target == Engine.MPV) {
            // mpv starts up in the background (slowly the first time on a TV box);
            // wait until it is actually ready instead of sending commands it would ignore
            val started = System.currentTimeMillis()
            withTimeoutOrNull(MPV_READY_TIMEOUT_MS) {
                while (!active.isCommandAvailable(Player.COMMAND_PREPARE) &&
                    !active.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)
                ) {
                    delay(50)
                }
            }
            Timber.i("mpv accepted media after %d ms", System.currentTimeMillis() - started)
        }
        if (!current.isLive && current.startPositionMs > 0) {
            active.setMediaItem(MediaItem.fromUri(current.url), current.startPositionMs)
        } else {
            active.setMediaItem(MediaItem.fromUri(current.url))
        }
        if (active.isCommandAvailable(Player.COMMAND_PREPARE)) {
            active.prepare()
            active.playWhenReady = true
            startTimeout(active, target)
        } else {
            Timber.i("%s not ready to prepare yet, waiting", target.label)
            pendingPrepare = true
            active.playWhenReady = true
        }
    }

    /** Counted from the moment the engine actually starts loading the stream. */
    private fun startTimeout(
        active: Player,
        target: Engine,
    ) {
        timeoutJob?.cancel()
        val limit = memory.settings.switchDelaySeconds.coerceAtLeast(2) * 1000L
        timeoutJob =
            scope.launch {
                delay(limit)
                if (player === active && active.playbackState != Player.STATE_READY) {
                    Timber.w("%s did not start within %d ms", target.label, limit)
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
                Engine.MPV -> MpvPlayer(context, MpvPlayerOptions(enableHardwareDecoding = true, useGpuNext = false, userAgent = USER_AGENT))
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
        if (!pinned && memory.settings.autoFallback && other !in triedEngines) {
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
        val httpFactory = OkHttpDataSource.Factory(sharedHttpClient).setUserAgent(USER_AGENT)
        // MPEG-TS (most IPTV live channels): start on the first decodable frame instead of waiting
        // for a full keyframe, and allow seeking in streams without an index (some IPTV films)
        val extractors =
            DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                ).setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory), extractors)
                // Providers drop segments now and then: retry harder before giving up
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        val loadControl =
            if (request?.isLive == true) {
                // Live TV: start showing the picture after ~1 s of video instead of the default 2.5 s
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                    .build()
            } else {
                // Films: keep a bigger cushion so a slow provider doesn't cause buffering pauses
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
                    .build()
            }
        return ExoPlayer
            .Builder(context, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
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
        /** One shared HTTP client keeps connections warm between channel changes. */
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
        private const val MAX_RECONNECTS = 3

        private fun isNetworkError(error: PlaybackException) =
            error.errorCode in
                setOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                    PlaybackException.ERROR_CODE_TIMEOUT,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
        private const val MPV_READY_TIMEOUT_MS = 15_000L
        private const val USER_AGENT = "AxiomTV"
    }
}

/** One choice in the audio or subtitle picker. group == null means "Off". */
data class TrackOption(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val group: Tracks.Group?,
    val index: Int,
    val type: Int,
)
