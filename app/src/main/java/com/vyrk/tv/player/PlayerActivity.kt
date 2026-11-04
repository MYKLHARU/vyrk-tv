package com.vyrk.tv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.lang.Languages
import com.vyrk.core.stream.Ranked
import com.vyrk.tv.R
import com.vyrk.tv.VyrkApp
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.ui.FocusTrace
import com.vyrk.tv.ui.KeyBus
import com.vyrk.tv.ui.browse.TitleLogo
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.VyrkIconButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.FittedCanvas
import com.vyrk.tv.ui.theme.InterfaceSize
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.theme.VyrkTheme
import com.vyrk.tv.ui.traceFocus
import java.text.DateFormat
import java.util.Date

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private var model: PlayerModel? = null
    private var mediaSession: MediaSession? = null
    private var originalModeId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = VyrkApp.graph(this)
        val req = graph.pendingPlayback
        val session = graph.session.value
        if (req == null || session == null) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalModeId = window.attributes.preferredDisplayModeId
        val dev = graph.deviceSettings.value
        val engine = PlayerEngine(this, graph.http, dev.passthrough, dev.softwareAudio) { bps -> graph.updateDevice { it.copy(measuredBps = bps) } }
        engine.dialogue.clearDialogue = dev.clearDialogue && !dev.passthrough
        engine.dialogue.tvSpeaker = dev.tvSpeakerMode && !dev.passthrough
        val m = PlayerModel(graph, session, lifecycleScope, engine, req) { id, args -> getString(id, *args) }
        model = m
        mediaSession = MediaSession.Builder(this, engine.player).build()
        engine.player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                m.onFirstFrame()
                if (dev.frameRateMatching) matchFrameRate(engine)
            }
            override fun onPlayerError(error: PlaybackException) = m.onError(error)
            override fun onPlaybackStateChanged(playbackState: Int) = m.onPlaybackState(playbackState)
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) m.saveProgress() }
        })
        val accent = runCatching { AccentChoice.valueOf(session.settings.value.accent) }.getOrDefault(AccentChoice.FROST)
        val size = runCatching { InterfaceSize.valueOf(dev.interfaceSize) }.getOrDefault(InterfaceSize.MEDIUM)
        FocusTrace.enter(PLAYER_LAYER)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.vyrk.tv.ui.LocalTraceLayer provides PLAYER_LAYER) {
                VyrkTheme(accent) { FittedCanvas(size) { PlayerScreen(m) { finish() } } }
            }
        }
        // Android 16 delivers Back through the back dispatcher, not as a key event.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                KeyBus.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                when {
                    m.phase == Phase.PLAYING && m.panel != Panel.NONE -> { m.panel = Panel.NONE; m.showControls() }
                    m.phase == Phase.PLAYING && m.scrubTarget != null -> m.scrubCancel()
                    m.phase == Phase.PLAYING && m.stallPrompt -> m.stallPrompt = false
                    m.phase == Phase.PLAYING && m.upNext -> { m.upNext = false; m.upNextCountdown = 10_000 }
                    m.phase == Phase.PLAYING && m.controlsVisible -> { m.controlsVisible = false; FocusTrace.set(PLAYER_LAYER, "player:video") }
                    else -> finish()
                }
            }
        })
        m.start()
    }

    /** Optional frame-rate matching: pick a display mode at the content's rate; restored on exit. */
    private fun matchFrameRate(engine: PlayerEngine) {
        val fps = engine.player.videoFormat?.frameRate?.takeIf { it > 0 } ?: return
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
        if (m.panel != Panel.NONE) return false
        when (code) {
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> return consume { m.panel = Panel.AUDIO_SUBS; m.showControls() }
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

    override fun onDestroy() {
        super.onDestroy()
        FocusTrace.exit(PLAYER_LAYER)
        if (originalModeId != 0 || window.attributes.preferredDisplayModeId != 0) window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
        mediaSession?.release()
        model?.engine?.release()
        model = null
    }
}

private const val PLAYER_LAYER = 1_000_000

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(m: PlayerModel, onExit: () -> Unit) {
    val c = Vyrk.colors
    LaunchedEffect(m.finishRequested) { if (m.finishRequested) onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = m.engine.player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    subtitleView?.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Add-on subtitles rendered by VYRK (supports the sync delay).
        val cue = m.currentCue()
        if (cue != null) {
            val st = m.engineSubtitleStyle()
            Box(Modifier.fillMaxSize().padding(bottom = if (m.controlsVisible) 130.dp else 40.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    cue, textAlign = TextAlign.Center, color = st.first,
                    style = Vyrk.type.headlineS.copy(fontSize = Vyrk.type.headlineS.fontSize * st.third, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
                    modifier = Modifier.background(st.second, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        when (m.phase) {
            Phase.CONNECTING -> ConnectingView(m, onExit)
            Phase.ASK -> MessageView(stringResource(R.string.source_failed_title), m.message) {
                VyrkButton(stringResource(R.string.retry), onClick = { m.retryPicked() }, style = ButtonStyle.PRIMARY, modifier = it.traceFocus("player:ask_retry"))
                VyrkButton(stringResource(R.string.other_sources), onClick = { m.phase = Phase.PLAYING; m.panel = Panel.SOURCES }, modifier = Modifier.traceFocus("player:ask_other"))
                VyrkButton(stringResource(R.string.exit), onClick = onExit, modifier = Modifier.traceFocus("player:ask_exit"))
            }
            Phase.ERROR, Phase.NO_SOURCES -> MessageView(stringResource(R.string.cant_play_title), m.message) {
                if (m.candidates.size + m.excluded.size > 0) VyrkButton(stringResource(R.string.other_sources), onClick = { m.phase = Phase.PLAYING; m.panel = Panel.SOURCES }, modifier = it.traceFocus("player:err_other"))
                VyrkButton(stringResource(R.string.back), onClick = onExit, style = ButtonStyle.PRIMARY, modifier = (if (m.candidates.isEmpty()) it else Modifier).traceFocus("player:err_back"))
            }
            Phase.PLAYING -> {
                if (m.controlsVisible && m.panel == Panel.NONE) Controls(m)
                val seg = m.skipSegment
                if (seg != null && !m.controlsVisible && m.panel == Panel.NONE) SkipPill(seg.kind == com.vyrk.core.playback.SegmentKind.RECAP)
                m.notice?.let { Notice(it) }
                if (m.panel == Panel.AUDIO_SUBS) AudioSubsPanel(m)
                if (m.panel == Panel.SOURCES) SourcesPanel(m)
                if (m.panel == Panel.SLEEP) SleepPanel(m)
                if (m.stallPrompt) Prompt(stringResource(R.string.stall_prompt), stringResource(R.string.lower_quality), { m.lowerQuality() }, stringResource(R.string.keep_trying)) { m.stallPrompt = false }
                if (m.upNext && m.nextVideo != null && !m.controlsVisible) UpNext(m)
                if (m.stillWatching) Prompt(stringResource(R.string.still_watching), stringResource(R.string.continue_playing), { m.confirmStillWatching() }, stringResource(R.string.exit)) { onExit() }
            }
        }
    }
}

/** Subtitle colour, background and size from profile settings. */
fun PlayerModel.engineSubtitleStyle(): Triple<Color, Color, Float> {
    val s = VyrkApp.graphOrNull()?.session?.value?.settings?.value
    val fg = when (s?.subtitleColor) { "YELLOW" -> Color(0xFFFFE066); "CYAN" -> Color(0xFFA9E1F3); else -> Color.White }
    val bg = when (s?.subtitleBackground) { "BOX" -> Color.Black.copy(alpha = 0.75f); "NONE" -> Color.Transparent; else -> Color.Black.copy(alpha = 0.30f) }
    return Triple(fg, bg, (s?.subtitleSize ?: 100) / 100f)
}

@Composable
private fun ConnectingView(m: PlayerModel, onExit: () -> Unit) {
    val c = Vyrk.colors
    val cancelReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelReq.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        val bg = m.meta?.background ?: m.request.meta.background
        if (bg != null) AsyncImage(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(c.background.copy(alpha = 0.78f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            TitleLogo(m.request.meta.copy(logo = m.meta?.logo ?: m.request.meta.logo), 360.dp, 110.dp)
            Spacer(Modifier.height(24.dp))
            Spinner(36.dp, c.accent)
            Spacer(Modifier.height(16.dp))
            Text(m.connecting, style = Vyrk.type.titleM, color = c.textSecondary)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m.candidates.size > 1) VyrkButton(stringResource(R.string.other_sources), onClick = { m.phase = Phase.PLAYING; m.panel = Panel.SOURCES }, modifier = Modifier.traceFocus("player:connecting_other"))
                VyrkButton(stringResource(R.string.cancel), onClick = onExit, modifier = Modifier.focusRequester(cancelReq).traceFocus("player:connecting_cancel"))
            }
        }
    }
}

@Composable
private fun MessageView(title: String, body: String, buttons: @Composable (Modifier) -> Unit) {
    val c = Vyrk.colors
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { req.requestFocus() } }
    Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).background(c.panel, RoundedCornerShape(22.dp)).padding(28.dp)) {
            Text(title, style = Vyrk.type.headlineL, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(body, style = Vyrk.type.bodyL, color = c.textSecondary)
            Spacer(Modifier.height(20.dp))
            WrapRow { buttons(Modifier.focusRequester(req)) }
        }
    }
}

@Composable
private fun Controls(m: PlayerModel) {
    val c = Vyrk.colors
    val playReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playReq.requestFocus() } }
    val title = m.meta?.name ?: m.request.meta.name
    val ep = m.video?.let { v -> if (v.season != null && v.episode != null) "S${v.season}E${v.episode} ${v.displayTitle}" else v.displayTitle }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Column(Modifier.padding(start = 48.dp, top = 30.dp)) {
            Text(title, style = Vyrk.type.headlineM, color = c.text)
            if (!ep.isNullOrBlank()) Text(ep, style = Vyrk.type.bodyM, color = c.textSecondary)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 48.dp, vertical = 26.dp),
        ) {
            Timeline(m)
            Spacer(Modifier.height(8.dp))
            Row {
                Text(fmt(m.scrubTarget ?: m.position), style = Vyrk.type.labelL, color = if (m.scrubTarget != null) c.accent else c.text)
                Spacer(Modifier.weight(1f))
                if (m.duration > 0 && !m.isLive) {
                    val ends = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (m.duration - m.position)))
                    Text("-" + fmt(m.duration - m.position) + "  ·  " + stringResource(R.string.ends_at, ends), style = Vyrk.type.labelL, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusGroup()) {
                VyrkIconButton(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (m.playing) R.string.pause else R.string.play), { m.togglePlay(); m.touch() }, modifier = Modifier.focusRequester(playReq).traceFocus("player:play_pause"))
                VyrkIconButton(R.drawable.ic_back10, stringResource(R.string.back_10), { m.seekBy(-10_000); m.touch() }, modifier = Modifier.traceFocus("player:back10"))
                VyrkIconButton(R.drawable.ic_fwd10, stringResource(R.string.forward_10), { m.seekBy(10_000); m.touch() }, modifier = Modifier.traceFocus("player:fwd10"))
                Spacer(Modifier.weight(1f))
                m.skipSegment?.let { seg ->
                    VyrkButton(stringResource(if (seg.kind == com.vyrk.core.playback.SegmentKind.RECAP) R.string.skip_recap else R.string.skip_intro), onClick = { m.skipNow() }, icon = R.drawable.ic_next, style = ButtonStyle.PRIMARY, modifier = Modifier.traceFocus("player:skip"))
                }
                VyrkButton(stringResource(R.string.audio_subtitles), onClick = { m.panel = Panel.AUDIO_SUBS }, icon = R.drawable.ic_subtitles, modifier = Modifier.traceFocus("player:audio_subs"))
                VyrkButton(stringResource(R.string.other_sources), onClick = { m.panel = Panel.SOURCES }, icon = R.drawable.ic_sources, modifier = Modifier.traceFocus("player:sources"))
                VyrkIconButton(R.drawable.ic_sleep, stringResource(R.string.sleep), { m.panel = Panel.SLEEP }, modifier = Modifier.traceFocus("player:sleep"))
            }
        }
    }
}

@Composable
private fun SidePanel(title: String, content: @Composable () -> Unit) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxHeight().width(380.dp).background(c.panel.copy(alpha = 0.97f)).padding(24.dp).focusGroup()) {
            Text(title, style = Vyrk.type.headlineM, color = c.text)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun AudioSubsPanel(m: PlayerModel) {
    var tab by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel(stringResource(R.string.audio_subtitles)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VyrkChip(stringResource(R.string.subtitles), tab == 0, { tab = 0 }, Modifier.focusRequester(first).traceFocus("panel:tab_subtitles"))
            VyrkChip(stringResource(R.string.audio), tab == 1, { tab = 1 }, Modifier.traceFocus("panel:tab_audio"))
            VyrkChip(stringResource(R.string.sync_style), tab == 2, { tab = 2 }, Modifier.traceFocus("panel:tab_sync"))
        }
        Spacer(Modifier.height(14.dp))
        when (tab) {
            0 -> {
                val settings = m.sessionSettingsLanguages()
                val tracks = com.vyrk.core.playback.TrackSelector.sortForDisplay(m.allTextTracks(), settings)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OptionRow(stringResource(R.string.off), m.selectedSub == null, "panel:sub_off") { m.selectSubtitle(null) } }
                    val seen = mutableMapOf<String, Int>()
                    val numbered = tracks.map { t -> val k = (t.language ?: "") + t.source; val n = (seen[k] ?: 0) + 1; seen[k] = n; t to n }
                    items(numbered, key = { it.first.id }) { (t, n) ->
                        val base = t.language?.let { Languages.label(it) } ?: (t.label ?: "—")
                        val lang = if (n > 1 || numbered.count { (o, _) -> o.language == t.language && o.source == t.source } > 1) "$base · $n" else base
                        val src = if (t.source == "Embedded") stringResource(R.string.embedded) else stringResource(R.string.addon_source, t.source)
                        OptionRow("$lang\n$src", m.selectedSub == t.id, "panel:sub:${t.id}") { m.selectSubtitle(t.id) }
                    }
                }
            }
            1 -> {
                val tracks = m.engine.audioTracks()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tracks, key = { it.id }) { t ->
                        val label = listOfNotNull(t.language?.let { Languages.label(it) }, t.label?.takeIf { l -> val lang = t.language; lang == null || !l.equals(Languages.name(lang), true) }, if (t.channels > 0) "${t.channels}ch" else null).joinToString(" · ")
                        OptionRow(label.ifEmpty { "—" }, m.selectedAudio == t.id || (m.selectedAudio == null && t.isDefault), "panel:audio:${t.id}") { m.selectAudio(t.id) }
                    }
                }
            }
            else -> {
                Text(stringResource(R.string.subtitle_delay, "%.1f".format(m.subDelayMs / 1000f)), style = Vyrk.type.titleM, color = Vyrk.colors.text)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VyrkButton("−0.5 s", onClick = { m.subDelayMs = (m.subDelayMs - 500).coerceAtLeast(-10_000) }, modifier = Modifier.traceFocus("panel:delay_minus"))
                    VyrkButton("+0.5 s", onClick = { m.subDelayMs = (m.subDelayMs + 500).coerceAtMost(10_000) }, modifier = Modifier.traceFocus("panel:delay_plus"))
                    VyrkButton(stringResource(R.string.reset), onClick = { m.subDelayMs = 0 })
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.delay_addon_only), style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
            }
        }
    }
}

fun PlayerModel.sessionSettingsLanguages(): List<String> = VyrkApp.graphOrNull()?.session?.value?.settings?.value?.subtitleLanguages.orEmpty()

@Composable
private fun OptionRow(text: String, selected: Boolean, trace: String, onClick: () -> Unit) {
    VyrkChip(text, selected, onClick, Modifier.fillMaxWidth().traceFocus(trace), maxLines = 2)
}

@Composable
private fun SourcesPanel(m: PlayerModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel(stringResource(R.string.other_sources)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(m.candidates, key = { it.candidate.key + it.candidate.addonId }) { r ->
                val idx = m.candidates.indexOf(r)
                SourceLine(r, current = m.current?.candidate?.key == r.candidate.key, modifier = if (idx == 0) Modifier.focusRequester(first) else Modifier) { m.pickSource(r) }
            }
            if (m.excluded.isNotEmpty()) item { Text(stringResource(R.string.not_playable_here), style = Vyrk.type.labelL, color = Vyrk.colors.textMuted, modifier = Modifier.padding(top = 10.dp)) }
            items(m.excluded, key = { "x" + it.candidate.key + it.candidate.addonId }) { r ->
                Text("${r.candidate.addonName} · ${r.info.resolutionLabel} — ${exclusionText(r)}", style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
            }
        }
    }
}

@Composable
fun exclusionText(r: Ranked): String = when (r.exclusion) {
    com.vyrk.core.stream.Exclusion.TORRENT_NO_SERVER -> stringResource(R.string.excl_torrent)
    com.vyrk.core.stream.Exclusion.EXTERNAL, com.vyrk.core.stream.Exclusion.YOUTUBE -> stringResource(R.string.excl_external)
    com.vyrk.core.stream.Exclusion.UNKNOWN_SOURCE -> stringResource(R.string.excl_unknown)
    com.vyrk.core.stream.Exclusion.OVER_MAX_RESOLUTION -> stringResource(R.string.excl_max_res)
    com.vyrk.core.stream.Exclusion.OVER_MAX_SIZE -> stringResource(R.string.excl_max_size)
    com.vyrk.core.stream.Exclusion.UNCACHED -> stringResource(R.string.excl_uncached)
    com.vyrk.core.stream.Exclusion.CODEC_UNSUPPORTED -> stringResource(R.string.excl_codec)
    com.vyrk.core.stream.Exclusion.OVER_DECODER_LIMIT -> stringResource(R.string.excl_decoder)
    null -> ""
}

@Composable
fun SourceLine(r: Ranked, current: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val i = r.info
    val parts = listOf(i.resolutionLabel, i.releaseType.label, i.codec.label, i.hdr.joinToString("/") { it.label }, i.audio.joinToString(" ") { it.label }, i.channels.orEmpty(),
        i.sizeBytes?.let { "%.1f GB".format(it / 1e9) }.orEmpty()).filter { it.isNotBlank() }
    val langs = i.languages.joinToString(", ") { Languages.label(it) }
    val text = buildString {
        append(r.candidate.addonName); append('\n'); append(parts.joinToString(" · ").ifEmpty { "—" })
        if (langs.isNotEmpty()) { append('\n'); append(langs) }
    }
    VyrkChip(text, current, onClick, modifier.fillMaxWidth().traceFocus("source:${r.candidate.addonId}:${r.candidate.index}"), maxLines = 3)
}

@Composable
private fun SleepPanel(m: PlayerModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel(stringResource(R.string.sleep)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VyrkChip(stringResource(R.string.off), m.sleep == SleepMode.OFF, { m.chooseSleep(SleepMode.OFF) }, Modifier.fillMaxWidth().focusRequester(first).traceFocus("sleep:off"))
            VyrkChip(stringResource(R.string.sleep_after_episode), m.sleep == SleepMode.END_OF_EPISODE, { m.chooseSleep(SleepMode.END_OF_EPISODE) }, Modifier.fillMaxWidth().traceFocus("sleep:episode"))
            VyrkChip(stringResource(R.string.sleep_60), m.sleep == SleepMode.MINUTES_60, { m.chooseSleep(SleepMode.MINUTES_60) }, Modifier.fillMaxWidth().traceFocus("sleep:60"))
        }
    }
}

@Composable
private fun Prompt(text: String, yes: String, onYes: () -> Unit, no: String, onNo: () -> Unit) {
    val c = Vyrk.colors
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { req.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.width(380.dp).background(c.panel, RoundedCornerShape(18.dp)).padding(20.dp).focusGroup()) {
            Text(text, style = Vyrk.type.titleL, color = c.text)
            Spacer(Modifier.height(12.dp))
            WrapRow {
                VyrkButton(yes, onClick = onYes, style = ButtonStyle.PRIMARY, modifier = Modifier.focusRequester(req).traceFocus("prompt:yes"))
                VyrkButton(no, onClick = onNo, modifier = Modifier.traceFocus("prompt:no"))
            }
        }
    }
}

@Composable
private fun UpNext(m: PlayerModel) {
    val nv = m.nextVideo ?: return
    val label = if (nv.season != null && nv.episode != null) "S${nv.season}E${nv.episode} ${nv.displayTitle}" else nv.displayTitle
    Prompt(stringResource(R.string.up_next_in, label, m.upNextCountdown), stringResource(R.string.play_now), { m.playNext(auto = false) }, stringResource(R.string.cancel)) { m.upNext = false; m.upNextCountdown = 10_000 }
}

@Composable
fun Spinner(size: androidx.compose.ui.unit.Dp, color: Color) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawArc(color, startAngle = angle, sweepAngle = 270f, useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
@Composable
private fun SkipPill(recap: Boolean) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().padding(end = 48.dp, bottom = 56.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.background(c.accent, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.vyrk.tv.ui.components.Icon(R.drawable.ic_next, null, Modifier.size(16.dp), tint = c.onAccent)
            Text(stringResource(if (recap) R.string.skip_recap else R.string.skip_intro), style = Vyrk.type.titleM, color = c.onAccent)
            Text(stringResource(R.string.press_ok), style = Vyrk.type.labelM, color = c.onAccent.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 36.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = Vyrk.type.titleM, color = Vyrk.colors.text,
            modifier = Modifier.background(Vyrk.colors.panel.copy(alpha = 0.92f), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

/**
 * Focusable timeline. Left/Right scrub a preview position (accelerating, snapping to chapters);
 * Up switches to chapter mode (Left/Right jump between chapters); Down leaves chapter mode;
 * OK seeks (or plays/pauses when not scrubbing). Ticks mark chapters; intro and recap are tinted.
 */
@Composable
private fun Timeline(m: PlayerModel) {
    val c = Vyrk.colors
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val dur = m.duration.coerceAtLeast(1)
    val shown = m.scrubTarget ?: m.position
    val chapter = m.chapterTitleAt(shown)
    val chapterLabel = when (chapter?.kind) {
        com.vyrk.core.playback.SegmentKind.INTRO -> stringResource(R.string.chapter_intro)
        com.vyrk.core.playback.SegmentKind.RECAP -> stringResource(R.string.chapter_recap)
        com.vyrk.core.playback.SegmentKind.OUTRO, com.vyrk.core.playback.SegmentKind.POST_CREDITS -> stringResource(R.string.chapter_credits)
        else -> chapter?.title ?: chapter?.let { stringResource(R.string.chapter_n, m.markers.indexOf(it) + 1) }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        val frac = (shown.toFloat() / dur).coerceIn(0f, 1f)
        Column {
            // Chapter pill above the knob, clamped inside the bar.
            Box(Modifier.fillMaxWidth().height(26.dp)) {
                if ((focused || m.scrubTarget != null) && chapterLabel != null) {
                    val pillW = 180.dp
                    val x = (w * frac - pillW / 2).coerceIn(0.dp, w - pillW)
                    Box(Modifier.padding(start = x).width(pillW), contentAlignment = Alignment.Center) {
                        Text(chapterLabel, style = Vyrk.type.labelM, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (m.chapterMode) c.onAccent else c.text,
                            modifier = Modifier.background(if (m.chapterMode) c.accent else c.raised.copy(alpha = 0.9f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(22.dp)
                    .traceFocus("player:timeline") // must come before focusable() to see its focus
                    .androidx_focus(onFocus = { focused = it }, m = m),
                contentAlignment = Alignment.CenterStart,
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(if (focused) 8.dp else 5.dp)) {
                    val h = size.height
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
                    fun xOf(ms: Long) = size.width * (ms.toFloat() / dur).coerceIn(0f, 1f)
                    drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
                    drawRoundRect(Color.White.copy(alpha = 0.35f), size = androidx.compose.ui.geometry.Size(xOf(m.buffered), h), cornerRadius = r)
                    // Intro and recap spans, tinted so you can see where they are without scrubbing.
                    m.segments.filter { it.kind == com.vyrk.core.playback.SegmentKind.INTRO || it.kind == com.vyrk.core.playback.SegmentKind.RECAP }.forEach { s ->
                        drawRect(c.warning.copy(alpha = 0.55f), topLeft = androidx.compose.ui.geometry.Offset(xOf(s.startMs), 0f), size = androidx.compose.ui.geometry.Size(xOf(s.endMs) - xOf(s.startMs), h))
                    }
                    drawRoundRect(c.accent, size = androidx.compose.ui.geometry.Size(xOf(m.position), h), cornerRadius = r)
                    // Chapter boundaries as small gaps in the bar.
                    m.markers.filter { it.startMs > 0 }.forEach { mk ->
                        val x = xOf(mk.startMs)
                        drawRect(Color.Black.copy(alpha = 0.85f), topLeft = androidx.compose.ui.geometry.Offset(x - 1.5f, 0f), size = androidx.compose.ui.geometry.Size(3f, h))
                    }
                    m.scrubTarget?.let { t -> drawCircle(c.text, radius = h * 1.1f, center = androidx.compose.ui.geometry.Offset(xOf(t), h / 2)) }
                    if (focused && m.scrubTarget == null) drawCircle(c.text, radius = h * 0.95f, center = androidx.compose.ui.geometry.Offset(xOf(m.position), h / 2))
                }
            }
            if (focused) Text(stringResource(R.string.timeline_hint), style = Vyrk.type.labelS, color = c.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun Modifier.androidx_focus(onFocus: (Boolean) -> Unit, m: PlayerModel): Modifier = this
    .onFocusChanged { onFocus(it.isFocused); if (!it.isFocused && m.chapterMode) m.scrubExitChapters() }
    .onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { m.scrubStep(false); true }
            Key.DirectionRight -> { m.scrubStep(true); true }
            Key.DirectionUp -> { if (!m.chapterMode) m.scrubEnterChapters(); true }
            Key.DirectionDown -> if (m.chapterMode) { m.scrubExitChapters(); true } else { if (m.scrubTarget != null) m.scrubCancel(); false }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (m.scrubTarget != null) m.commitScrub() else m.togglePlay(); m.touch(); true }
            else -> false
        }
    }
    .focusable()