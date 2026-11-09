package com.vyrk.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.vyrk.core.addon.Stream
import com.vyrk.core.lang.Languages
import com.vyrk.core.playback.AudioTrack
import com.vyrk.core.playback.BandwidthMeter
import com.vyrk.core.playback.ErrorClass
import com.vyrk.core.playback.ErrorClassifier
import com.vyrk.core.playback.TextTrack
import okhttp3.OkHttpClient

data class EngineTrack(val groupIndex: Int, val trackIndex: Int, val language: String?, val label: String?, val channels: Int, val forced: Boolean, val selected: Boolean, val codec: String?)

/** ExoPlayer with OkHttp, HLS/DASH, the FFmpeg audio fallback and bandwidth sampling. */
@OptIn(UnstableApi::class)
class PlayerEngine(
    context: Context,
    http: OkHttpClient,
    passthrough: Boolean,
    preferFfmpeg: Boolean,
    private val onBandwidth: (Double) -> Unit,
) {
    val dialogue = DialogueProcessor()
    private val bandwidth = BandwidthMeter()
    private val meter = DefaultBandwidthMeter.Builder(context).build().also {
        it.addEventListener(Handler(Looper.getMainLooper())) { elapsedMs, bytes, _ ->
            bandwidth.onSample(bytes, elapsedMs.toLong())?.let(onBandwidth)
        }
    }
    val trackSelector = DefaultTrackSelector(context)
    private val dataSource = OkHttpDataSource.Factory(http)

    private val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val b = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(dialogue))
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            @Suppress("DEPRECATION")
            if (!passthrough) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            return b.build()
        }
    }.setExtensionRendererMode(if (preferFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setTrackSelector(trackSelector)
        .setBandwidthMeter(meter)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    fun prepare(stream: Stream, startMs: Long, preserveAudio: Boolean = false) {
        val url = stream.url ?: return
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        dataSource.setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                url.contains(".m3u8", true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                url.contains(".mpd", true) -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        val tracks = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        if (!preserveAudio) tracks.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        player.trackSelectionParameters = tracks.build()
        player.setMediaItem(item, startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    fun classify(e: PlaybackException): ErrorClass {
        var cause: Throwable? = e.cause
        var status: Int? = null
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) { status = cause.responseCode; break }
            cause = cause.cause
        }
        return ErrorClassifier.classify(e.errorCode, status)
    }

    fun tracks(type: Int): List<EngineTrack> {
        val out = mutableListOf<EngineTrack>()
        player.currentTracks.groups.forEachIndexed { gi, g: Tracks.Group ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                out += EngineTrack(gi, ti, f.language, f.label, f.channelCount, (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0, g.isTrackSelected(ti), f.sampleMimeType)
            }
        }
        return out
    }

    fun audioTracks(): List<AudioTrack> = tracks(C.TRACK_TYPE_AUDIO).map {
        AudioTrack("${it.groupIndex}:${it.trackIndex}", it.language, it.label ?: it.language?.let { l -> Languages.name(l) }, it.channels, it.codec, it.selected)
    }

    fun embeddedTextTracks(): List<TextTrack> = tracks(C.TRACK_TYPE_TEXT).map {
        TextTrack("emb:${it.groupIndex}:${it.trackIndex}", it.language, it.label, it.forced, "Embedded", it.selected)
    }

    fun select(type: Int, id: String?) {
        val b = trackSelector.parameters.buildUpon()
        if (id == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            val parts = id.removePrefix("emb:").split(':').mapNotNull { it.toIntOrNull() }
            val group = player.currentTracks.groups.getOrNull(parts.getOrNull(0) ?: -1) ?: return
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, parts[1]))
        }
        trackSelector.parameters = b.build()
    }

    fun release() = player.release()
}
