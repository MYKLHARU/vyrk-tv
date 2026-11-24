package com.vyrk.tv.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vyrk.tv.data.Diagnostics
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.util.Locale

/**
 * mpv (libmpv) behind Media3's [Player] interface: VYRK's backup player for files ExoPlayer can't
 * decode or read. The screen, the media session and the player model use it like ExoPlayer; mpv
 * draws the picture (and embedded subtitles) itself on the PlayerView's surface.
 *
 * The main thread never waits on mpv: libmpv's calls block until its core thread takes them, and
 * that thread can itself be waiting on the surface, which the main thread is tearing down (an
 * ANR on leaving the player). So commands go to one worker thread in order, state comes from
 * observed properties, and detaching the surface waits at most [DETACH_TIMEOUT_MS] (as ExoPlayer
 * does).
 */
@OptIn(UnstableApi::class)
class MpvPlayer(context: Context, private val userAgent: String) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var mpv: MPV? = null
    private var item: MediaItem? = null
    private var headers: Map<String, String> = emptyMap()
    private var startMs = 0L
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var durationUs = C.TIME_UNSET
    private var tracks = Tracks.EMPTY
    private var trackParams = TrackSelectionParameters.DEFAULT
    private var videoSize = VideoSize.UNKNOWN
    private var loaded = false
    private var firstFrameShown = false
    private var firstFramePending = false
    private var buffering = false
    private var ended = false
    private var surface: Surface? = null
    private var holder: SurfaceHolder? = null
    private var pendingLoad = false
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vyrk-mpv") }
    // Written by mpv's event thread, read on the main thread.
    @Volatile private var timePos: Double? = null
    @Volatile private var cacheAhead = 0.0
    private var pausedForCache = false
    private var seeking = false
    private var videoW = 0L
    private var videoH = 0L
    private var videoPar = 1.0

    /** Runs [block] on the worker thread, in order with every other mpv call. */
    private fun send(block: (MPV) -> Unit) {
        val m = mpv ?: return
        runCatching { worker.execute { runCatching { block(m) }.onFailure { Diagnostics.log("player", "mpv: ${it.javaClass.simpleName}") } } }
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) = attach(h.surface)
        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            send { it.setPropertyString("android-surface-size", "${width}x$height") }
        }
        override fun surfaceDestroyed(h: SurfaceHolder) = detach()
    }

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) { main.post { onNumber(property, value.toDouble()) } }
        override fun eventProperty(property: String, value: Boolean) { main.post { onFlag(property, value) } }
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {
            when (property) {
                // Every frame: kept for the position supplier, no main-thread work.
                "time-pos" -> timePos = value
                "demuxer-cache-duration" -> cacheAhead = value
                else -> main.post { onNumber(property, value) }
            }
        }
        override fun eventProperty(property: String, value: MPVNode) { if (property == "track-list") main.post { tracks = buildTracks(value); invalidateState() } }
        override fun event(eventId: Int, data: MPVNode) { main.post { onEvent(eventId, data) } }
    }

    override fun getState(): SimpleBasePlayer.State {
        val b = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setTrackSelectionParameters(trackParams)
            .setVideoSize(videoSize)
        val mi = item ?: return b.setPlaybackState(Player.STATE_IDLE).build()
        val state = when {
            error != null -> Player.STATE_IDLE
            playbackState == Player.STATE_IDLE -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            !loaded || buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        b.setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder(UID).setMediaItem(mi).setDurationUs(durationUs).setTracks(tracks).setIsSeekable(true).build()))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(state)
            .setPlayerError(error)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier { positionMs() })
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier { bufferedMs() })
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier { (bufferedMs() - positionMs()).coerceAtLeast(0) })
        if (firstFramePending) { b.setNewlyRenderedFirstFrame(true); firstFramePending = false }
        return b.build()
    }

    private fun positionMs(): Long = timePos?.takeIf { loaded }?.let { (it * 1000).toLong() } ?: startMs

    private fun bufferedMs(): Long = if (!loaded) startMs else positionMs() + (cacheAhead * 1000).toLong()

    /** Request headers for the next file (from the add-on's proxy headers). */
    fun setHeaders(h: Map<String, String>) { headers = h }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        item = mediaItems.firstOrNull()
        startMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceAtLeast(0)
        resetFile()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (item == null) return Futures.immediateVoidFuture()
        error = null
        playbackState = Player.STATE_BUFFERING
        if (ensureMpv() == null) {
            error = PlaybackException("mpv failed to start", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            return Futures.immediateVoidFuture()
        }
        if (surface != null) load() else pendingLoad = true
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        send { it.setPropertyBoolean("pause", !playWhenReady) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        if (mpv != null && loaded) {
            ended = false; buffering = true; timePos = target / 1000.0
            send { it.command("seek", String.format(Locale.US, "%.3f", target / 1000.0), "absolute") }
        } else startMs = target
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        send { it.command("stop") }
        playbackState = Player.STATE_IDLE
        resetFile()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        holder?.removeCallback(holderCallback)
        holder = null
        detach()
        // mpv's shutdown can take a moment (it joins its threads): on the worker, after everything queued.
        send { it.removeObserver(observer); it.close() }
        mpv = null
        worker.shutdown()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackParams = trackSelectionParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> useHolder(videoOutput.holder)
            is SurfaceHolder -> useHolder(videoOutput)
            is Surface -> attach(videoOutput)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        holder?.removeCallback(holderCallback)
        holder = null
        detach()
        return Futures.immediateVoidFuture()
    }

    /** Selects an mpv track by the id its [Format] carries ("audio:2", "sub:3"); null turns the type off. */
    fun selectTrack(type: Int, formatId: String?) {
        val prop = if (type == C.TRACK_TYPE_AUDIO) "aid" else if (type == C.TRACK_TYPE_TEXT) "sid" else return
        send { it.setPropertyString(prop, formatId?.substringAfter(':') ?: "no") }
    }

    private fun useHolder(h: SurfaceHolder) {
        if (holder === h) return
        holder?.removeCallback(holderCallback)
        holder = h
        h.addCallback(holderCallback)
        if (h.surface?.isValid == true) attach(h.surface)
    }

    private fun attach(s: Surface) {
        surface = s
        if (mpv == null) return
        send { it.attachSurface(s); it.setOptionString("force-window", "yes"); it.setPropertyString("vo", VO) }
        if (pendingLoad) load()
    }

    /** mpv lets go of the surface before it goes; waits at most [DETACH_TIMEOUT_MS], then carries on. */
    private fun detach() {
        val had = surface != null
        surface = null
        if (!had || mpv == null) return
        val done = java.util.concurrent.CountDownLatch(1)
        send { m -> try { m.setPropertyString("vo", "null"); m.setOptionString("force-window", "no"); m.detachSurface() } finally { done.countDown() } }
        if (!done.await(DETACH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) Diagnostics.log("player", "mpv: surface detach timed out")
    }

    private fun ensureMpv(): MPV? {
        mpv?.let { return it }
        return runCatching {
            Utils.copyAssets(app)
            MPV(app).apply {
                setOptionString("vo", "null")
                setOptionString("gpu-context", "android")
                setOptionString("opengl-es", "yes")
                setOptionString("hwdec", "auto")
                setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
                setOptionString("ao", "audiotrack,opensles")
                setOptionString("tls-verify", "yes")
                setOptionString("tls-ca-file", "${app.filesDir.path}/cacert.pem")
                setOptionString("user-agent", userAgent)
                setOptionString("idle", "yes")
                setOptionString("keep-open", "yes")
                setOptionString("cache", "yes")
                setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
                setOptionString("demuxer-max-back-bytes", "${16 * 1024 * 1024}")
                setOptionString("sub-auto", "no")
                setOptionString("audio-file-auto", "no")
                setOptionString("input-default-bindings", "no")
                setOptionString("save-position-on-quit", "no")
                setOptionString("msg-level", "all=warn")
                setOptionString("sid", "no")
                addObserver(observer)
                observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
                observeProperty("demuxer-cache-duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
                observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
                observeProperty("paused-for-cache", MPV.mpvFormat.MPV_FORMAT_FLAG)
                observeProperty("seeking", MPV.mpvFormat.MPV_FORMAT_FLAG)
                observeProperty("eof-reached", MPV.mpvFormat.MPV_FORMAT_FLAG)
                observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NODE)
                observeProperty("video-params/w", MPV.mpvFormat.MPV_FORMAT_INT64)
                observeProperty("video-params/h", MPV.mpvFormat.MPV_FORMAT_INT64)
                observeProperty("video-params/par", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            }.also { mpv = it; surface?.let(::attach) }
        }.onFailure { Diagnostics.log("player", "mpv failed to start: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun load() {
        if (mpv == null) return
        val url = item?.localConfiguration?.uri?.toString() ?: return
        pendingLoad = false
        timePos = null; cacheAhead = 0.0
        // mpv's list option: "Name: value" entries separated by commas (commas inside values escaped).
        val fields = headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }
        val start = if (startMs > 0) String.format(Locale.US, "+%.3f", startMs / 1000.0) else "none"
        val pause = !playWhenReady
        send { m ->
            m.setPropertyString("http-header-fields", fields)
            m.setPropertyString("start", start)
            m.setPropertyBoolean("pause", pause)
            m.command("loadfile", url, "replace")
        }
        Diagnostics.log("player", "mpv: loading")
    }

    private fun resetFile() {
        loaded = false; firstFrameShown = false; firstFramePending = false; buffering = false; ended = false
        error = null; durationUs = C.TIME_UNSET; tracks = Tracks.EMPTY; videoSize = VideoSize.UNKNOWN
        pausedForCache = false; seeking = false; videoW = 0; videoH = 0; videoPar = 1.0
    }

    private fun onFlag(property: String, value: Boolean) {
        when (property) {
            "paused-for-cache" -> { pausedForCache = value; buffering = value || seeking }
            "seeking" -> { seeking = value; buffering = value || pausedForCache }
            "eof-reached" -> ended = value && loaded
            else -> return
        }
        invalidateState()
    }

    private fun onNumber(property: String, value: Double) {
        when (property) {
            "duration" -> durationUs = (value * 1_000_000).toLong()
            "video-params/w" -> videoW = value.toLong()
            "video-params/h" -> videoH = value.toLong()
            "video-params/par" -> videoPar = value.takeIf { it > 0 } ?: 1.0
            else -> return
        }
        if (property.startsWith("video-params")) videoSize = if (videoW > 0 && videoH > 0) VideoSize(videoW.toInt(), videoH.toInt(), videoPar.toFloat()) else VideoSize.UNKNOWN
        invalidateState()
    }

    private fun onEvent(id: Int, data: MPVNode) {
        when (id) {
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> { loaded = true; buffering = true; Diagnostics.log("player", "mpv: file loaded") }
            MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                buffering = pausedForCache
                if (!firstFrameShown && loaded) { firstFrameShown = true; firstFramePending = true }
            }
            MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                if (data["reason"]?.asString() != "error") return
                val why = data["error"]?.asString().orEmpty()
                Diagnostics.log("player", "mpv: failed ($why)")
                error = PlaybackException("mpv: $why", null, errorCode(why))
                playbackState = Player.STATE_IDLE
                loaded = false
            }
            else -> return
        }
        invalidateState()
    }

    companion object {
        /** libmpv needs Android 7; VYRK still runs on 6, without the backup player. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 24
        private const val VO = "gpu"
        private const val DETACH_TIMEOUT_MS = 500L
        private const val UID = "vyrk-mpv"

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_GET_METADATA,
        ).build()

        /** mpv's errors, in the classes VYRK's recovery knows: unreadable files are container errors. */
        private fun errorCode(why: String): Int = when {
            why.contains("unrecognized", true) || why.contains("format", true) -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            why.contains("network", true) || why.contains("http", true) -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            else -> PlaybackException.ERROR_CODE_DECODING_FAILED
        }

        private val AUDIO = mapOf("aac" to MimeTypes.AUDIO_AAC, "ac3" to MimeTypes.AUDIO_AC3, "eac3" to MimeTypes.AUDIO_E_AC3, "dts" to MimeTypes.AUDIO_DTS,
            "truehd" to MimeTypes.AUDIO_TRUEHD, "flac" to MimeTypes.AUDIO_FLAC, "opus" to MimeTypes.AUDIO_OPUS, "vorbis" to MimeTypes.AUDIO_VORBIS,
            "mp3" to MimeTypes.AUDIO_MPEG, "mp2" to MimeTypes.AUDIO_MPEG_L2)
        private val VIDEO = mapOf("h264" to MimeTypes.VIDEO_H264, "hevc" to MimeTypes.VIDEO_H265, "av1" to MimeTypes.VIDEO_AV1, "vp9" to MimeTypes.VIDEO_VP9,
            "vp8" to MimeTypes.VIDEO_VP8, "mpeg2video" to MimeTypes.VIDEO_MPEG2, "vc1" to MimeTypes.VIDEO_VC1, "mpeg4" to MimeTypes.VIDEO_MP4V)
        private val SUBS = mapOf("subrip" to MimeTypes.APPLICATION_SUBRIP, "ass" to MimeTypes.TEXT_SSA, "ssa" to MimeTypes.TEXT_SSA,
            "hdmv_pgs_subtitle" to MimeTypes.APPLICATION_PGS, "dvd_subtitle" to MimeTypes.APPLICATION_VOBSUB, "mov_text" to MimeTypes.APPLICATION_TX3G,
            "webvtt" to MimeTypes.TEXT_VTT, "dvb_subtitle" to MimeTypes.APPLICATION_DVBSUBS)

        /** mpv's track-list as Media3 tracks: one group per track, its id in the format ("audio:2"). */
        internal fun buildTracks(node: MPVNode?): Tracks {
            val groups = node?.asArray().orEmpty().mapNotNull { t ->
                val type = t["type"]?.asString() ?: return@mapNotNull null
                val id = t["id"]?.asInt() ?: return@mapNotNull null
                val codec = t["codec"]?.asString().orEmpty()
                val mime = when (type) {
                    "audio" -> AUDIO[codec] ?: "audio/x-$codec"
                    "video" -> VIDEO[codec] ?: "video/x-$codec"
                    "sub" -> SUBS[codec] ?: "text/x-$codec"
                    else -> return@mapNotNull null
                }
                val flags = (if (t["forced"]?.asBoolean() == true) C.SELECTION_FLAG_FORCED else 0) or (if (t["default"]?.asBoolean() == true) C.SELECTION_FLAG_DEFAULT else 0)
                val format = Format.Builder().setId("$type:$id").setSampleMimeType(mime).setCodecs(codec.ifEmpty { null })
                    .setLanguage(t["lang"]?.asString()).setLabel(t["title"]?.asString()).setSelectionFlags(flags)
                    .setChannelCount((t["demux-channel-count"]?.asInt() ?: Format.NO_VALUE.toLong()).toInt())
                    .build()
                Tracks.Group(TrackGroup("$type:$id", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t["selected"]?.asBoolean() == true))
            }
            return Tracks(ImmutableList.copyOf(groups))
        }
    }
}
