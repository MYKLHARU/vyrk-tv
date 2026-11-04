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

/**
 * Muted trailer on the right of the title header after focus has rested for 850 ms.
 * The header artwork stays visible until the trailer actually plays.
 */
@Composable
fun HeaderTrailer(meta: MetaPreview, full: MetaItem?, sound: Boolean, modifier: Modifier = Modifier) {
    val yt = full?.youtubeTrailerId ?: meta.trailerStreams.firstNotNullOfOrNull { it.ytId }
    var armed by remember(meta.id) { mutableStateOf(false) }
    var state by remember(meta.id) { mutableStateOf(TrailerState.LOADING) }
    LaunchedEffect(meta.id, yt) { armed = false; if (yt != null) { delay(850); armed = true } }
    if (yt == null || !armed || state == TrailerState.REFUSED) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(modifier.alpha(if (state == TrailerState.PLAYING) 1f else 0f).size(352.dp, 198.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
            YouTubeTrailer(yt, muted = !sound, modifier = Modifier.fillMaxSize()) { state = it }
        }
    }
}
