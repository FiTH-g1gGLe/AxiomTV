@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.axiom.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import kotlinx.coroutines.delay
import timber.log.Timber
import kotlin.math.abs

/**
 * Phase 1 live player: ExoPlayer only.
 * Remote: Up/Down or Channel +/- to change channel, OK to show info, Back to leave.
 * Touch: tap to show info, swipe up/down to change channel.
 */
@Composable
fun LivePlayerScreen(
    channels: List<ChannelEntity>,
    startIndex: Int,
    urlFor: (ChannelEntity) -> String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(true) }
    var infoPulse by remember { mutableIntStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var videoInfo by remember { mutableStateOf("") }

    val player =
        remember {
            val renderers =
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
            ExoPlayer.Builder(context, renderers).build().apply { playWhenReady = true }
        }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(playbackError: PlaybackException) {
                    Timber.e(playbackError, "Live playback error (%s)", playbackError.errorCodeName)
                    error = "Couldn't play this channel (${playbackError.errorCodeName})"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) error = null
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.height > 0) {
                        videoInfo = "${videoSize.height}p"
                    }
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val channel = channels.getOrNull(index)

    LaunchedEffect(index) {
        val current = channel ?: return@LaunchedEffect
        error = null
        videoInfo = ""
        Timber.i("Playing channel %d %s", current.number, current.name)
        player.setMediaItem(MediaItem.fromUri(urlFor(current)))
        player.prepare()
        infoPulse++
    }

    LaunchedEffect(infoPulse) {
        showInfo = true
        delay(5000)
        showInfo = false
    }

    fun changeChannel(step: Int) {
        if (channels.isEmpty()) return
        index = (index + step + channels.size) % channels.size
    }

    BackHandler(onBack = onBack)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.ChannelUp -> {
                            changeChannel(-1)
                            true
                        }

                        Key.DirectionDown, Key.ChannelDown -> {
                            changeChannel(1)
                            true
                        }

                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Info -> {
                            infoPulse++
                            true
                        }

                        else -> false
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onTap = { infoPulse++ })
                }.pointerInput(channels.size) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > 120f) changeChannel(if (total < 0) 1 else -1)
                        },
                        onVerticalDrag = { _, dragAmount -> total += dragAmount },
                    )
                },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    isFocusable = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Text(
                text = "Loading...",
                color = AxiomColors.TextMuted,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        error?.let { message ->
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xE6141518))
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(message, color = AxiomColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Try another channel with Up/Down (or swipe). Use \"Copy debug log\" on the channel list to send me the details.",
                    color = AxiomColors.TextMuted,
                    fontSize = 15.sp,
                )
            }
        }

        if (showInfo && channel != null) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AxiomColors.PanelHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!channel.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${channel.number}  ${channel.name}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Up/Down or swipe to change channel",
                        color = AxiomColors.TextMuted,
                        fontSize = 14.sp,
                    )
                }
                if (videoInfo.isNotEmpty()) {
                    InfoPill(videoInfo)
                }
                InfoPill("ExoPlayer", gold = true)
            }
        }
    }
}

@Composable
private fun InfoPill(
    text: String,
    gold: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (gold) AxiomColors.GoldTint else Color(0x33FFFFFF))
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (gold) AxiomColors.Gold else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
