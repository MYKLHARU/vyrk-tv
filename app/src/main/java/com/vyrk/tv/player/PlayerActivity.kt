package com.vyrk.tv.player

import com.vyrk.tv.data.BackupPlayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.vyrk.core.stream.Ranked
import com.vyrk.tv.R
import com.vyrk.tv.VyrkApp
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.ui.FocusTrace
import com.vyrk.tv.ui.KeyBus
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.FittedCanvas
import com.vyrk.tv.ui.theme.InterfaceSize
import com.vyrk.tv.ui.theme.VyrkTheme

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    companion object {
        const val REQ_EXTERNAL = 4207
        /** Let Android ask which app. */
        const val ASK = "ask"
    }
    private lateinit var graph: AppGraph
    private var model: PlayerModel? = null
    private var mediaSession: MediaSession? = null
    private var originalModeId = 0
    private val trakt by lazy { com.vyrk.tv.data.TraktScrobbler(graph.trakt) }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.vyrk.tv.AppLocale.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = VyrkApp.graph(this)
        val req = graph.pendingPlayback
        val session = graph.session.value
        if (req == null || session == null) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalModeId = window.attributes.preferredDisplayModeId
        val dev = graph.deviceSettings.value
        val engine = PlayerEngine(this, graph.http, dev.passthrough, dev.softwareAudio,
            onBandwidth = { bps -> graph.updateDevice { it.copy(measuredBps = bps) } }, live = com.vyrk.core.addon.isLiveType(req.meta.type),
            dolbyVisionAsHdr10 = dev.dolbyVisionAsHdr10, displayDolbyVision = graph.playbackCaps().displayDolbyVision,
            backup = dev.backupPlayer != BackupPlayer.OFF, userAgent = "VYRK/${com.vyrk.tv.BuildConfig.VERSION_NAME} (Android TV)")
        engine.dialogue.clearDialogue = dev.clearDialogue && !dev.passthrough
        engine.dialogue.tvSpeaker = dev.tvSpeakerMode && !dev.passthrough
        val m = PlayerModel(graph, session, lifecycleScope, engine, req) { id, args -> getString(id, *args) }
        model = m
        // The freeze guard notes each source as it starts and clears it once it settles.
        m.onSourceStarting = { r ->
            graph.freezeGuard.starting(r.candidate.key, com.vyrk.core.stream.StreamRanker.freezeKind(r.info), req.meta.name,
                listOf(r.info.resolutionLabel, if (com.vyrk.core.stream.Hdr.DOLBY_VISION in r.info.hdr) "Dolby Vision" else "", r.info.releaseType.label)
                    .filter { it.isNotBlank() }.joinToString(" · ") + " · " + r.candidate.addonName)
        }
        m.onSourceSettled = { graph.freezeGuard.settled() }
        // Another app as the default player (Settings › Picture & sound).
        m.handOff = { r, pos -> dev.externalPlayer.isNotEmpty() && playExternally(r, dev.externalPlayer, pos) }
        mediaSession = MediaSession.Builder(this, engine.player).build()
        engine.onPlayerChanged = { p -> mediaSession?.player = p }
        engine.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                m.onFirstFrame()
                if (dev.frameRateMatching) matchFrameRate(engine)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                m.onPositionDiscontinuity()
            }
            override fun onPlayerError(error: PlaybackException) = m.onError(error)
            override fun onPlaybackStateChanged(playbackState: Int) = m.onPlaybackState(playbackState)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) m.saveProgress()
                if (!m.isLive) trakt.onPlaying(m.traktItem, isPlaying, engine.player.currentPosition, engine.player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0)
            }
        })
        val accent = runCatching { AccentChoice.valueOf(session.settings.value.accent) }.getOrDefault(AccentChoice.GOLD)
        val size = runCatching { InterfaceSize.valueOf(dev.interfaceSize) }.getOrDefault(InterfaceSize.MEDIUM)
        FocusTrace.enter(PLAYER_LAYER)
        val phone = com.vyrk.tv.ui.phone.FormFactor.isPhone(this)
        if (phone) {
            // Phones: the whole screen, cutout included; the bars come back with a swipe.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            m.showControls()
        }
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.vyrk.tv.ui.LocalTraceLayer provides PLAYER_LAYER) {
                VyrkTheme(accent) {
                    if (phone) PhonePlayerScreen(m) { finish() }
                    else FittedCanvas(size) { PlayerScreen(m) { finish() } }
                }
            }
        }
        // Android 16 delivers Back through the back dispatcher, not as a key event.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                KeyBus.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                if (phone) {
                    // On a phone Back closes an open sheet, and otherwise leaves the player.
                    if (m.panel != Panel.NONE) m.panel = Panel.NONE else finish()
                    return
                }
                when {
                    m.phase == Phase.INCOMPLETE && m.panel != Panel.NONE -> { m.panel = Panel.NONE }
                    m.phase == Phase.PLAYING && m.panel != Panel.NONE -> { m.panel = Panel.NONE; m.showControls() }
                    m.phase == Phase.PLAYING && m.scrubTarget != null -> m.scrubCancel()
                    m.phase == Phase.PLAYING && m.stallPrompt -> { m.stallPrompt = false; m.showControls() }
                    m.phase == Phase.PLAYING && m.upNext -> { m.dismissUpNext() }
                    m.phase == Phase.PLAYING && m.controlsVisible -> { m.controlsVisible = false; FocusTrace.set(PLAYER_LAYER, "player:video") }
                    else -> finish()
                }
            }
        })
        m.start()
    }

    /** Optional frame-rate matching: pick a display mode at the content's rate; restored on exit. */
    private fun matchFrameRate(engine: PlayerEngine) {
        val fps = engine.videoFrameRate() ?: return
        val display = window.decorView.display ?: return
        val cur = display.mode
        val best = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .minByOrNull { mode -> val r = mode.refreshRate / fps; kotlin.math.abs(r - kotlin.math.round(r)) + (if (r < 0.9f) 10f else 0f) } ?: return
        if (best.modeId != cur.modeId) window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyBus.onKey(event)
        val m = model ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(m, event)) return true
        if (event.action == KeyEvent.ACTION_UP && consumedUp.remove(event.keyCode)) return true
        m.touch()
        return super.dispatchKeyEvent(event)
    }

    private val consumedUp = mutableSetOf<Int>()

    private fun handleKey(m: PlayerModel, e: KeyEvent): Boolean {
        val code = e.keyCode
        fun consume(block: () -> Unit): Boolean { block(); consumedUp += code; return true }
        if (code == KeyEvent.KEYCODE_BACK) return false // handled by the back dispatcher
        if (m.phase != Phase.PLAYING) return false
        // Modal prompts own arrows and OK; playback shortcuts must not seek beneath them.
        if (m.panel != Panel.NONE || m.stallPrompt || m.stillWatching) return false
        when (code) {
            KeyEvent.KEYCODE_CAPTIONS -> return consume { m.panel = Panel.AUDIO_SUBS; m.showControls() }
            // The remote's audio button: straight to the next audio track, named over the video.
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> return consume { m.cycleAudio() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> return consume { m.togglePlay(); m.showControls() }
            KeyEvent.KEYCODE_MEDIA_REWIND -> return consume { m.seekBy(-30_000) }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return consume { m.seekBy(30_000) }
            KeyEvent.KEYCODE_MEDIA_NEXT -> return consume { m.playNext(auto = false) }
        }
        if (!m.controlsVisible) {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && m.skipSegment != null) return consume { m.skipNow() }
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> return consume { m.seekBy(-10_000) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return consume { m.seekBy(10_000) }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return consume { m.showControls() }
            }
        }
        return false
    }

    override fun onPause() {
        super.onPause()
        model?.let { it.saveProgress(); it.engine.player.pause(); it.close() }
    }

    /**
     * Hands [r] to another app ([pkg], or "ask" to let Android ask) from [positionMs]; VYRK's
     * player pauses, saves where the other app stopped when it reports it, then closes.
     */
    fun playExternally(r: Ranked, pkg: String, positionMs: Long): Boolean {
        val url = r.candidate.stream.url ?: return false
        val m = model ?: return false
        val headers = r.candidate.stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val title = m.request.meta.name + (m.video?.let { v -> if (v.season != null && v.episode != null) " · S${v.season} E${v.episode}" else "" } ?: "")
        m.engine.player.pause()
        graph.freezeGuard.settled()
        val ok = ExternalPlayers.launch(this, ExternalPlayers.intent(pkg.takeIf { it != ASK }, url, title, positionMs, headers), REQ_EXTERNAL)
        if (ok) {
            m.engine.player.stop()
            android.util.Log.i("VYRK", "external_player $pkg")
        }
        return ok
    }

    /** "Play with …" from the sources panel: the source on screen (or the best one) from where you are. */
    fun playExternallyNow(pkg: String) {
        val m = model ?: return
        val r = m.current ?: m.candidates.firstOrNull() ?: return
        val pos = m.engine.player.currentPosition.takeIf { m.phase == Phase.PLAYING && it > 0 } ?: m.resumePosition()
        if (!playExternally(r, pkg, pos)) m.showNotice(getString(R.string.external_player_missing))
    }

    @Deprecated("Activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXTERNAL) return
        ExternalPlayers.result(data)?.let { (pos, dur) ->
            val m = model ?: return@let
            // Continue Watching, then Trakt (80%+ marks it watched there).
            val total = m.saveExternalProgress(pos, dur) ?: return@let
            trakt.onPlaying(m.traktItem, true, pos, total)
            trakt.close(pos, total)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::graph.isInitialized) graph.freezeGuard.settled()
        FocusTrace.exit(PLAYER_LAYER)
        if (originalModeId != 0 || window.attributes.preferredDisplayModeId != 0) window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
        mediaSession?.release()
        model?.engine?.player?.let { p -> trakt.close(p.currentPosition, p.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0) }
        model?.engine?.release()
        model = null
    }
}

private const val PLAYER_LAYER = 1_000_000
