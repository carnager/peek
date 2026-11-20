package io.github.carnager.peek.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.carnager.peek.ui.bounceClick
import kotlinx.coroutines.delay

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * A video page in the viewer. The player is only created once the page is shown, starts playing
 * then and pauses when you swipe on or leave the app. It uses a TextureView so it scales and
 * fades with the swipe-to-close gesture, and takes no touches itself, so paging still works.
 */
@Composable
fun VideoPage(item: ViewerItem, isCurrent: Boolean, chrome: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.duration) }
    var muted by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(isCurrent) {
        if (isCurrent && player == null) {
            // Server videos need the trunk token; DefaultDataSource still handles content:// for local ones.
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            io.github.carnager.peek.trunk.TrunkAccount.token
                ?.takeIf { io.github.carnager.peek.trunk.TrunkAccount.owns(item.uri.toString()) }
                ?.let { http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $it")) }
            val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, http))
            player = ExoPlayer.Builder(ctx).setMediaSourceFactory(sources).build().apply {
                setMediaItem(MediaItem.fromUri(item.uri))
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlaybackStateChanged(state: Int) {
                        ended = state == Player.STATE_ENDED
                        if (this@apply.duration > 0) duration = this@apply.duration
                    }
                })
                prepare()
            }
        }
        player?.playWhenReady = isCurrent
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player?.pause() }

    // The seek bar follows playback.
    LaunchedEffect(player, playing) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            position = p.currentPosition
            if (!playing) break
            delay(200)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        val poster: @Composable () -> Unit = {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(item.previewUrl ?: if (item.remote) item.uri.toString().replace("/file", "/thumb/preview") else item.uri).placeholderMemoryCacheKey(item.placeholderKey).build(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val p = player
        if (p != null) {
            ContentFrame(
                player = p,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                keepContentOnReset = true,
                shutter = poster,
            )
        } else {
            poster()
        }

        AnimatedVisibility(
            visible = chrome || !playing,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .bounceClick(pressedScale = 0.88f) {
                        val pl = player ?: return@bounceClick
                        when {
                            ended -> { pl.seekTo(0); pl.play() }
                            pl.isPlaying -> pl.pause()
                            else -> pl.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        ended -> Icons.Rounded.Replay
                        playing -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    if (playing) "Pause" else "Abspielen",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        // Seek bar, sitting just above the viewer's floating action bar.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = scrubbing?.let { (it * duration).toLong() } ?: position
                Text(formatDuration(shown), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = scrubbing ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { f -> player?.seekTo((f * duration).toLong()); position = (f * duration).toLong() }
                        scrubbing = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(formatDuration(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = {
                    muted = !muted
                    player?.volume = if (muted) 0f else 1f
                }) {
                    Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Ton an" else "Ton aus", tint = Color.White)
                }
            }
        }
    }
}
