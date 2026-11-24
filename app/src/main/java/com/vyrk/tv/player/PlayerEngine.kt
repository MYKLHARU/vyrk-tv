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
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vyrk.core.playback.PlaybackBuffering
import com.vyrk.core.addon.Stream
import com.vyrk.core.lang.Languages
import com.vyrk.core.playback.AudioTrack
import com.vyrk.core.playback.BandwidthMeter
import com.vyrk.core.playback.ErrorClass
import com.vyrk.core.playback.ErrorClassifier
import com.vyrk.core.playback.TextTrack
import okhttp3.OkHttpClient

data class EngineTrack(val groupIndex: Int, val trackIndex: Int, val language: String?, val label: String?, val channels: Int, val forced: Boolean, val selected: Boolean, val codec: String?)

/**
 * ExoPlayer with OkHttp, HLS/DASH, the FFmpeg audio fallback, Dolby Vision profile 7 handling and
 * bandwidth sampling; plus mpv ([MpvPlayer]) as the backup player for files ExoPlayer can't play.
 * [player] is whichever is in use; listeners added through [addListener] follow it.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(
    context: Context,
    http: OkHttpClient,
    passthrough: Boolean,
    preferFfmpeg: Boolean,
    private val onBandwidth: (Double) -> Unit,
    live: Boolean = false,
    /** Play Dolby Vision files with an HDR10 base through the HEVC decoder ([Hdr10VideoRenderer]). */
    dolbyVisionAsHdr10: Boolean = false,
    /** The screen takes Dolby Vision (null: unknown); decides how profile 7 files play. */
    displayDolbyVision: Boolean? = null,
    /** The backup player may be used. */
    backup: Boolean = true,
    private val userAgent: String = "VYRK",
) {
    private val appContext = context.applicationContext

    val dialogue = DialogueProcessor()
    private val bandwidth = BandwidthMeter()
    private val meter = DefaultBandwidthMeter.Builder(context).build().also {
        it.addEventListener(Handler(Looper.getMainLooper())) { elapsedMs, bytes, _ ->
            bandwidth.onSample(bytes, elapsedMs.toLong())?.let(onBandwidth)
        }
    }
    val trackSelector = DefaultTrackSelector(context)
    private val mediaHttp = PlaybackNetwork.client(http)
    private val dataSource = OkHttpDataSource.Factory(mediaHttp)
    val bufferConfig = PlaybackBuffering.forHeap(Runtime.getRuntime().maxMemory(), live)
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val b = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(dialogue))
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            @Suppress("DEPRECATION")
            if (!passthrough) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            return b.build()
        }

        override fun buildVideoRenderers(
            context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
            enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long, out: ArrayList<androidx.media3.exoplayer.Renderer>,
        ) {
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            if (!dolbyVisionAsHdr10) return
            val i = out.indexOfFirst { it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer }
            if (i < 0) return
            out[i] = Hdr10VideoRenderer(
                androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            )
        }
    }.setExtensionRendererMode(if (preferFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    /** What a Dolby Vision profile 7 file becomes on this device. */
    val dv7Mode = DvDecoders.mode7(displayDolbyVision, dolbyVisionAsHdr10)

    val exo: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setTrackSelector(trackSelector)
        .setBandwidthMeter(meter)
        .setLoadControl(PlaybackLoadControls.create(bufferConfig, allocator))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, Dv7ExtractorsFactory(DefaultExtractorsFactory(), dv7Mode)))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    /** The backup player can be used here (libmpv needs Android 7). */
    val backupAvailable = backup && MpvPlayer.supported
    private var mpv: MpvPlayer? = null
    /** The player in use: [exo], or mpv for a file ExoPlayer couldn't play. Compose state, so the screen follows it. */
    var player: Player by mutableStateOf<Player>(exo)
        private set
    val onBackup: Boolean get() = player !== exo
    private val listeners = mutableListOf<Player.Listener>()
    /** Told when [player] changes (the media session follows it). */
    var onPlayerChanged: ((Player) -> Unit)? = null

    fun addListener(l: Player.Listener) { listeners += l; player.addListener(l) }

    private fun use(next: Player) {
        val old = player
        if (next === old) return
        listeners.forEach { old.removeListener(it) }
        old.stop()
        player = next
        listeners.forEach { next.addListener(it) }
        onPlayerChanged?.invoke(next)
        com.vyrk.tv.data.Diagnostics.log("player", "engine: ${if (next === exo) "exoplayer" else "mpv"}")
    }

    /** Plays [stream] from [startMs]: in mpv when [backup] (and it's available), else in ExoPlayer. */
    fun prepare(stream: Stream, startMs: Long, preserveAudio: Boolean = false, backup: Boolean = false) {
        val url = stream.url ?: return
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                url.contains(".m3u8", true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                url.contains(".mpd", true) -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        val target: Player = if (backup && backupAvailable) mpv ?: MpvPlayer(appContext, userAgent).also { mpv = it } else exo
        use(target)
        if (target is MpvPlayer) target.setHeaders(headers)
        else {
            dataSource.setDefaultRequestProperties(headers)
            val tracks = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            if (!preserveAudio) tracks.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            exo.trackSelectionParameters = tracks.build()
        }
        target.setMediaItem(item, startMs.coerceAtLeast(0))
        target.prepare()
        target.playWhenReady = true
    }

    /** The video's frame rate, for matching the display; null when unknown (or in mpv). */
    fun videoFrameRate(): Float? = if (onBackup) null else exo.videoFormat?.frameRate?.takeIf { it > 0 }

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
        (player as? MpvPlayer)?.let { m ->
            val parts = id?.removePrefix("emb:")?.split(':')?.mapNotNull { it.toIntOrNull() }
            val group = parts?.let { player.currentTracks.groups.getOrNull(it.getOrNull(0) ?: -1) }
            m.selectTrack(type, group?.getTrackFormat(0)?.id)
            return
        }
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

    /** Redacted diagnostics: sample buffer and heap sizes, never URLs, headers or credentials. */
    fun bufferDiagnostics(): String {
        val runtime = Runtime.getRuntime()
        if (onBackup) return "engine=mpv aheadMs=${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0)} heapUsed=${runtime.totalMemory() - runtime.freeMemory()}"
        return "aheadMs=${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0)} " +
            "loading=${player.isLoading} sampleBytes=${allocator.totalBytesAllocated} budgetBytes=${bufferConfig.targetBytes} " +
            "heapUsed=${runtime.totalMemory() - runtime.freeMemory()} heapMax=${runtime.maxMemory()}"
    }

    fun release() {
        exo.release()
        mpv?.release()
        // Closing a TLS connection writes to the network, which Android 11 and older forbid on the
        // main thread (NetworkOnMainThreadException crashed the app on leaving the player).
        val http = mediaHttp
        Thread({
            runCatching { http.dispatcher.cancelAll() }
            runCatching { http.connectionPool.evictAll() }
            runCatching { http.dispatcher.executorService.shutdown() }
        }, "vyrk-player-release").start()
    }
}
