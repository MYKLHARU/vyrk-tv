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
 * draws the picture (and embedded subtitles) itself on the PlayerView's surface. mpv calls back on
 * its own thread; everything here runs on the main thread.
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

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) = attach(h.surface)
        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            mpv?.setPropertyString("android-surface-size", "${width}x$height")
        }
        override fun surfaceDestroyed(h: SurfaceHolder) = detach()
    }

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) { main.post { onProperty(property) } }
        override fun eventProperty(property: String, value: Long) { main.post { onProperty(property) } }
        override fun eventProperty(property: String, value: Boolean) { main.post { onFlag(property, value) } }
        override fun eventProperty(property: String, value: String) { main.post { onProperty(property) } }
        override fun eventProperty(property: String, value: Double) { main.post { if (property == "duration") { durationUs = (value * 1_000_000).toLong(); invalidateState() } } }
        override fun eventProperty(property: String, value: MPVNode) { main.post { onProperty(property) } }
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

    private fun positionMs(): Long =
        mpv?.takeIf { loaded }?.getPropertyDouble("time-pos")?.let { (it * 1000).toLong() } ?: startMs

    private fun bufferedMs(): Long {
        val m = mpv?.takeIf { loaded } ?: return startMs
        val pos = positionMs()
        val ahead = m.getPropertyDouble("demuxer-cache-duration") ?: 0.0
        return pos + (ahead * 1000).toLong()
    }

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
        mpv?.setPropertyBoolean("pause", !playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        val m = mpv
        if (m != null && loaded) {
            ended = false; buffering = true
            m.command("seek", String.format(Locale.US, "%.3f", target / 1000.0), "absolute")
        } else startMs = target
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        mpv?.command("stop")
        playbackState = Player.STATE_IDLE
        resetFile()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        holder?.removeCallback(holderCallback)
        holder = null
        detach()
        val m = mpv
        mpv = null
        // mpv's shutdown can take a moment (it joins its threads): off the main thread.
        if (m != null) Thread({ runCatching { m.removeObserver(observer); m.close() } }, "vyrk-mpv-release").start()
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
        val m = mpv ?: return
        val prop = if (type == C.TRACK_TYPE_AUDIO) "aid" else if (type == C.TRACK_TYPE_TEXT) "sid" else return
        m.setPropertyString(prop, formatId?.substringAfter(':') ?: "no")
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
        val m = mpv ?: return
        m.attachSurface(s)
        m.setOptionString("force-window", "yes")
        m.setPropertyString("vo", VO)
        if (pendingLoad) load()
    }

    private fun detach() {
        val m = mpv
        if (m != null && surface != null) {
            m.setPropertyString("vo", "null")
            m.setOptionString("force-window", "no")
            m.detachSurface()
        }
        surface = null
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
                observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
                observeProperty("paused-for-cache", MPV.mpvFormat.MPV_FORMAT_FLAG)
                observeProperty("seeking", MPV.mpvFormat.MPV_FORMAT_FLAG)
                observeProperty("eof-reached", MPV.mpvFormat.MPV_FORMAT_FLAG)
                observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NONE)
                observeProperty("video-params", MPV.mpvFormat.MPV_FORMAT_NONE)
            }.also { mpv = it; surface?.let(::attach) }
        }.onFailure { Diagnostics.log("player", "mpv failed to start: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun load() {
        val m = mpv ?: return
        val url = item?.localConfiguration?.uri?.toString() ?: return
        pendingLoad = false
        // mpv's list option: "Name: value" entries separated by commas (commas inside values escaped).
        m.setPropertyString("http-header-fields", headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" })
        m.setPropertyString("start", if (startMs > 0) String.format(Locale.US, "+%.3f", startMs / 1000.0) else "none")
        m.setPropertyBoolean("pause", !playWhenReady)
        m.command("loadfile", url, "replace")
        Diagnostics.log("player", "mpv: loading")
    }

    private fun resetFile() {
        loaded = false; firstFrameShown = false; firstFramePending = false; buffering = false; ended = false
        error = null; durationUs = C.TIME_UNSET; tracks = Tracks.EMPTY; videoSize = VideoSize.UNKNOWN
    }

    private fun onFlag(property: String, value: Boolean) {
        when (property) {
            "paused-for-cache", "seeking" -> buffering = value || (mpv?.getPropertyBoolean("paused-for-cache") == true)
            "eof-reached" -> ended = value && loaded
            else -> return
        }
        invalidateState()
    }

    private fun onProperty(property: String) {
        val m = mpv ?: return
        when (property) {
            "track-list" -> tracks = buildTracks(m.getPropertyNode("track-list"))
            "video-params" -> {
                val w = m.getPropertyInt("video-params/w") ?: 0
                val h = m.getPropertyInt("video-params/h") ?: 0
                val par = m.getPropertyDouble("video-params/par")?.toFloat()?.takeIf { it > 0 } ?: 1f
                videoSize = if (w > 0 && h > 0) VideoSize(w, h, par) else VideoSize.UNKNOWN
            }
            else -> return
        }
        invalidateState()
    }

    private fun onEvent(id: Int, data: MPVNode) {
        when (id) {
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> { loaded = true; buffering = true; Diagnostics.log("player", "mpv: file loaded") }
            MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                buffering = mpv?.getPropertyBoolean("paused-for-cache") == true
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
