package com.vyrk.tv.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView
import com.vyrk.core.playback.PlaybackBuffering
import com.vyrk.tv.data.OkHttpTransport
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File

/** Loopback-only, isolated benchmark used by ci/buffer_probe.py; excluded from release builds. */
@androidx.annotation.OptIn(UnstableApi::class)
class BufferProbeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var http: OkHttpClient? = null
    private var finished = false
    private var played = false
    private var stalls = 0
    private var dropped = 0
    private var peakBytes = 0
    private var peakAhead = 0L
    private var firstPlaying = 0L
    private var start = 0L
    private var runId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent an accidental benchmark from installing over or reconfiguring the user's app.
        check(packageName == "com.vyrk.tv.buffercheck")
        val url = intent.getStringExtra("url") ?: error("loopback URL required")
        check(Uri.parse(url).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        runId = intent.getStringExtra("run") ?: error("run required")
        check(runId.matches(Regex("[a-z0-9_-]+")))
        val baseline = intent.getBooleanExtra("baseline", false)
        val config = PlaybackBuffering.forHeap(Runtime.getRuntime().maxMemory())
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = if (baseline) DefaultLoadControl.Builder().setAllocator(allocator).build()
            else PlaybackLoadControls.create(config, allocator)
        val parent = OkHttpTransport.client("VYRK/buffering-probe")
        val client = if (baseline) parent else PlaybackNetwork.client(parent)
        http = client
        val engine = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
            .setLoadControl(loadControl).build()
        player = engine
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(PlayerView(this).apply { useController = false; player = engine })
        engine.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped += droppedFrames }
        })
        engine.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !played) { played = true; firstPlaying = SystemClock.elapsedRealtime() - start }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING && played) stalls++
                if (state == Player.STATE_ENDED) result("ended")
            }
            override fun onPlayerError(error: PlaybackException) { result(error.errorCodeName) }
        })
        val sample = object : Runnable {
            override fun run() {
                peakBytes = maxOf(peakBytes, allocator.totalBytesAllocated)
                peakAhead = maxOf(peakAhead, (engine.bufferedPosition - engine.currentPosition).coerceAtLeast(0))
                if (!finished) handler.postDelayed(this, 100)
            }
        }
        start = SystemClock.elapsedRealtime()
        handler.post(sample)
        handler.postDelayed({ if (!finished) result("timeout") }, 120_000)
        engine.setMediaItem(MediaItem.fromUri(url)); engine.prepare(); engine.playWhenReady = true
    }

    private fun result(outcome: String) {
        if (finished) return
        finished = true
        val engine = player ?: return
        val json = JSONObject().put("run", runId).put("outcome", outcome).put("stalls", stalls)
            .put("startupMs", firstPlaying).put("elapsedMs", SystemClock.elapsedRealtime() - start)
            .put("positionMs", engine.currentPosition).put("durationMs", engine.duration)
            .put("peakSampleBytes", peakBytes).put("peakAheadMs", peakAhead).put("droppedFrames", dropped)
            .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
        File(filesDir, "buffer-probe-$runId.json").writeText(json.toString(2))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        http?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        super.onDestroy()
    }
}
