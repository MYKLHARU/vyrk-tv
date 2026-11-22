package com.vyrk.tv.ui.preview

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import kotlinx.coroutines.delay

/** Trailer playback state for callers that show artwork until the video actually plays. */
enum class TrailerState { LOADING, PLAYING, REFUSED, NONE }

/**
 * Official YouTube IFrame player (android-youtube-player). It never takes focus; the caller
 * owns all keys. Reports [TrailerState.REFUSED] when embedding is blocked so the caller can
 * offer "Open in YouTube".
 */
@Composable
fun YouTubeTrailer(videoId: String, muted: Boolean, modifier: Modifier = Modifier, onState: (TrailerState) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            YouTubePlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                enableAutomaticInitialization = false
                lifecycle.addObserver(this)
                val opts = IFramePlayerOptions.Builder(ctx).controls(0).rel(0).ivLoadPolicy(3).ccLoadPolicy(0).build()
                initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) {
                        player = youTubePlayer
                        if (muted) youTubePlayer.mute() else youTubePlayer.unMute()
                        youTubePlayer.loadVideo(videoId, 0f)
                    }
                    override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        if (state == PlayerConstants.PlayerState.PLAYING) onState(TrailerState.PLAYING)
                    }
                    override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                        onState(TrailerState.REFUSED)
                    }
                }, true, opts)
            }
        },
        onRelease = { it.release() },
    )
    DisposableEffect(videoId) { onDispose { player?.pause() } }
}

fun openInYouTube(ctx: android.content.Context, videoId: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
    runCatching { ctx.startActivity(app) }.onFailure { runCatching { ctx.startActivity(web) } }
}

/** How long focus rests on a title before its trailer starts in the header. */
private const val HEADER_TRAILER_REST_MS = 2_000L

/**
 * Muted trailer on the right of the title header once focus has rested on a title for 2 s.
 * One player serves every title while the header is up (a YouTube player is a web page, far too
 * heavy to start afresh for each title passed); it's paused and hidden while focus moves on.
 * The header artwork stays visible until the trailer actually plays.
 */
@Composable
fun HeaderTrailer(meta: MetaPreview, full: MetaItem?, sound: Boolean, modifier: Modifier = Modifier) {
    val yt = full?.youtubeTrailerId ?: meta.trailerStreams.firstNotNullOfOrNull { it.ytId }
    var armed by remember(meta.id) { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(meta.id, yt) { armed = false; if (yt != null) { delay(HEADER_TRAILER_REST_MS); armed = true; started = true } }
    if (!started) return
    val want = yt?.takeIf { armed && it !in refused }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(modifier.alpha(if (want != null && playing == want) 1f else 0f).size(352.dp, 198.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
            SharedYouTubeTrailer(want, muted = !sound, modifier = Modifier.fillMaxSize(), onPlaying = { playing = it }, onRefused = { refused = refused + it })
        }
    }
}

/**
 * One YouTube player kept for a run of titles: [videoId] loads in place (null pauses it), so moving
 * between titles doesn't build and tear down a web page each time.
 */
@Composable
private fun SharedYouTubeTrailer(videoId: String?, muted: Boolean, modifier: Modifier, onPlaying: (String?) -> Unit, onRefused: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }
    val current by androidx.compose.runtime.rememberUpdatedState(videoId)
    val playingNow by androidx.compose.runtime.rememberUpdatedState(onPlaying)
    val refusedNow by androidx.compose.runtime.rememberUpdatedState(onRefused)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            YouTubePlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                enableAutomaticInitialization = false
                lifecycle.addObserver(this)
                val opts = IFramePlayerOptions.Builder(ctx).controls(0).rel(0).ivLoadPolicy(3).ccLoadPolicy(0).build()
                initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) { player = youTubePlayer }
                    override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        if (state == PlayerConstants.PlayerState.PLAYING) playingNow(current)
                    }
                    override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                        current?.let(refusedNow)
                    }
                }, true, opts)
            }
        },
        onRelease = { it.release() },
    )
    LaunchedEffect(player, muted) { player?.let { if (muted) it.mute() else it.unMute() } }
    LaunchedEffect(player, videoId) {
        val p = player ?: return@LaunchedEffect
        playingNow(null)
        if (videoId == null) p.pause() else p.loadVideo(videoId, 0f)
    }
    DisposableEffect(Unit) { onDispose { player?.pause() } }
}
