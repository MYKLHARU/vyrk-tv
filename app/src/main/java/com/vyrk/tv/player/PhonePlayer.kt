package com.vyrk.tv.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.lang.Languages
import com.vyrk.core.playback.SegmentKind
import com.vyrk.core.playback.TrackSelector
import com.vyrk.tv.R
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.phone.PhoneButton
import com.vyrk.tv.ui.phone.PhoneButtonStyle
import com.vyrk.tv.ui.phone.PhoneIconButton
import com.vyrk.tv.ui.phone.PhoneListRow
import com.vyrk.tv.ui.phone.PhoneSheet
import com.vyrk.tv.ui.phone.SourceCard
import com.vyrk.tv.ui.phone.press
import com.vyrk.tv.ui.theme.Vyrk
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * The player on a phone, in landscape. Tap shows or hides the controls; double-tap on the left or
 * right third goes back or forward 10 seconds; the timeline is dragged to seek. Audio and
 * subtitles, other sources and the sleep timer open as sheets.
 */
@kotlin.OptIn(ExperimentalComposeUiApi::class)
@OptIn(UnstableApi::class)
@Composable
fun PhonePlayerScreen(m: PlayerModel, onExit: () -> Unit) {
    LaunchedEffect(m.finishRequested) { if (m.finishRequested) onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { testTagsAsResourceId = true }) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = m.engine.player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    subtitleView?.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val cue = m.currentCue()
        if (cue != null) {
            val st = m.engineSubtitleStyle()
            Box(Modifier.fillMaxSize().padding(bottom = if (m.controlsVisible) 110.dp else 28.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
                Text(cue, textAlign = TextAlign.Center, color = st.first,
                    style = Vyrk.type.headlineS.copy(fontSize = Vyrk.type.headlineS.fontSize * st.third, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
                    modifier = Modifier.background(st.second, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        when (m.phase) {
            Phase.CONNECTING -> Connecting(m, onExit)
            Phase.ASK -> PhoneMessage(stringResource(R.string.source_failed_title), m.message) {
                PhoneButton(stringResource(R.string.retry), { m.retryPicked() }, style = PhoneButtonStyle.PRIMARY, tag = "player:ask_retry")
                PhoneButton(stringResource(R.string.other_sources), { m.phase = Phase.PLAYING; m.panel = Panel.SOURCES })
                PhoneButton(stringResource(R.string.exit), onExit)
            }
            Phase.INCOMPLETE -> {
                if (m.panel == Panel.SOURCES) PhoneMessage(stringResource(R.string.incomplete_stream_title), m.message) {}
                else PhoneMessage(stringResource(R.string.incomplete_stream_title), m.message) {
                    PhoneButton(stringResource(R.string.switch_stream), { m.switchStream() }, style = PhoneButtonStyle.PRIMARY)
                    PhoneButton(stringResource(R.string.retry), { m.retryPicked() })
                    if (m.nextVideo != null) PhoneButton(stringResource(R.string.next_episode), { m.playNext(auto = false) })
                    PhoneButton(stringResource(R.string.exit), onExit)
                }
            }
            Phase.ERROR, Phase.NO_SOURCES -> PhoneMessage(stringResource(R.string.cant_play_title), m.message) {
                if (m.candidates.size + m.excluded.size > 0) PhoneButton(stringResource(R.string.other_sources), { m.phase = Phase.PLAYING; m.panel = Panel.SOURCES })
                PhoneButton(stringResource(R.string.back), onExit, style = PhoneButtonStyle.PRIMARY, tag = "player:err_back")
            }
            Phase.PLAYING -> Playing(m, onExit)
        }
        // Panels sit over every phase: "Other sources" is also offered when a source fails.
        val panel = m.panel
        PhoneSheet(panel == Panel.AUDIO_SUBS, { m.panel = Panel.NONE }, stringResource(R.string.audio_subtitles)) { AudioSubs(m) }
        PhoneSheet(panel == Panel.SOURCES, { m.panel = Panel.NONE }, stringResource(R.string.other_sources)) { Sources(m) }
        PhoneSheet(panel == Panel.SLEEP, { m.panel = Panel.NONE }, stringResource(R.string.sleep)) {
            PhoneListRow(stringResource(R.string.off), { m.chooseSleep(SleepMode.OFF); m.panel = Panel.NONE }, selected = m.sleep == SleepMode.OFF)
            PhoneListRow(stringResource(R.string.sleep_after_episode), { m.chooseSleep(SleepMode.END_OF_EPISODE); m.panel = Panel.NONE }, selected = m.sleep == SleepMode.END_OF_EPISODE)
            PhoneListRow(stringResource(R.string.sleep_60), { m.chooseSleep(SleepMode.MINUTES_60); m.panel = Panel.NONE }, selected = m.sleep == SleepMode.MINUTES_60)
        }
    }
}

@Composable
private fun Playing(m: PlayerModel, onExit: () -> Unit) {
    val c = Vyrk.colors
    // Which side was double-tapped, shown for a moment ("−10 s" / "+10 s").
    var flash by remember { mutableStateOf<Int?>(null) }
    var flashAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(flashAt) { if (flash != null) { delay(650); flash = null } }
    Box(Modifier.fillMaxSize()) {
        // Gestures under the controls: the controls' own buttons take their taps first.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { if (m.controlsVisible) m.controlsVisible = false else m.showControls() },
                onDoubleTap = { o ->
                    val width = size.width.toFloat()
                    when {
                        o.x < width / 3f -> { m.seekBy(-10_000); flash = -1 }
                        o.x > width * 2f / 3f -> { m.seekBy(10_000); flash = 1 }
                        else -> { m.togglePlay(); flash = 0 }
                    }
                    flashAt = System.nanoTime(); m.touch()
                },
            )
        })
        flash?.let { side ->
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = when (side) { -1 -> Alignment.CenterStart; 1 -> Alignment.CenterEnd; else -> Alignment.Center }) {
                Box(Modifier.size(92.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(when (side) { -1 -> R.drawable.ic_back10; 1 -> R.drawable.ic_fwd10; else -> if (m.playing) R.drawable.ic_play else R.drawable.ic_pause },
                        null, Modifier.size(36.dp), tint = Color.White)
                }
            }
        }
        AnimatedVisibility(m.controlsVisible, enter = fadeIn(), exit = fadeOut()) { Controls(m, onExit) }
        val seg = m.skipSegment
        if (seg != null && !m.controlsVisible) Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(24.dp), contentAlignment = Alignment.BottomEnd) {
            PhoneButton(stringResource(m.skipLabel(seg)), { m.skipNow() },
                icon = R.drawable.ic_next, style = PhoneButtonStyle.PRIMARY, tag = "player:skip")
        }
        m.notice?.let { n ->
            Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
                Text(n, style = Vyrk.type.titleM, color = c.text, modifier = Modifier.background(c.panel.copy(alpha = 0.92f), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
        if (m.stallPrompt) Prompt(stringResource(R.string.stall_prompt), stringResource(R.string.lower_quality), { m.lowerQuality() }, stringResource(R.string.keep_trying)) { m.stallPrompt = false }
        val nv = m.nextVideo
        if (m.upNext && nv != null && !m.controlsVisible) {
            val label = if (nv.season != null && nv.episode != null) "S${nv.season}E${nv.episode} ${nv.displayTitle}" else nv.displayTitle
            Prompt(stringResource(R.string.up_next_in, label, m.upNextCountdown), stringResource(R.string.play_now), { m.playNext(auto = false) }, stringResource(R.string.cancel)) { m.dismissUpNext() }
        }
        if (m.stillWatching) Prompt(stringResource(R.string.still_watching), stringResource(R.string.continue_playing), { m.confirmStillWatching() }, stringResource(R.string.exit), onExit)
    }
}

@Composable
private fun Controls(m: PlayerModel, onExit: () -> Unit) {
    val c = Vyrk.colors
    val meta = m.meta
    val title = meta?.name ?: m.request.meta.name
    val v = m.video
    val episode = v?.let { if (it.season != null && it.episode != null) "S${it.season}E${it.episode} · ${it.displayTitle}" else null }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); delay(20_000) } }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (m.playing) 0.25f else 0.5f)))
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Top: back, the title, and the menus.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PhoneIconButton(R.drawable.ic_left, stringResource(R.string.back), onExit, filled = false, tag = "player:back")
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title, style = Vyrk.type.titleL, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (episode != null) Text(episode, style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PhoneIconButton(R.drawable.ic_subtitles, stringResource(R.string.audio_subtitles), { m.panel = Panel.AUDIO_SUBS; m.touch() }, filled = false, tag = "player:audio_subs")
                PhoneIconButton(R.drawable.ic_sources, stringResource(R.string.other_sources), { m.panel = Panel.SOURCES; m.touch() }, filled = false, tag = "player:sources")
                PhoneIconButton(R.drawable.ic_sleep, stringResource(R.string.sleep), { m.panel = Panel.SLEEP; m.touch() }, filled = false, active = m.sleep != SleepMode.OFF, tag = "player:sleep")
            }
            Spacer(Modifier.weight(1f))
            // Centre: back 10, play/pause, forward 10.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                RoundControl(R.drawable.ic_back10, stringResource(R.string.back_10), 56.dp, "player:back10") { m.seekBy(-10_000); m.touch() }
                RoundControl(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (m.playing) R.string.pause else R.string.play), 76.dp, "player:play_pause", hero = true) { m.togglePlay(); m.touch() }
                RoundControl(R.drawable.ic_fwd10, stringResource(R.string.forward_10), 56.dp, "player:fwd10") { m.seekBy(10_000); m.touch() }
            }
            Spacer(Modifier.weight(1f))
            // Bottom: the timeline, times, Skip intro and Next episode.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val facts = buildList {
                    if (m.duration > 0 && !m.isLive) add(stringResource(R.string.ends_at, timeFmt.format(Date(clock + (m.duration - m.position)))))
                }
                Text(facts.joinToString("  ·  "), style = Vyrk.type.labelL, color = c.textSecondary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                m.skipSegment?.let { seg ->
                    PhoneButton(stringResource(m.skipLabel(seg)), { m.skipNow(); m.touch() },
                        icon = R.drawable.ic_next, style = PhoneButtonStyle.PRIMARY, height = 40.dp, tag = "player:skip_controls")
                }
                if (m.nextVideo != null) PhoneButton(stringResource(R.string.next_episode), { m.playNext(auto = false) }, icon = R.drawable.ic_next, height = 40.dp, tag = "player:next")
            }
            Timeline(m)
        }
    }
}

@Composable
private fun RoundControl(icon: Int, label: String, size: androidx.compose.ui.unit.Dp, tag: String, hero: Boolean = false, onClick: () -> Unit) {
    val c = Vyrk.colors
    Box(
        Modifier.size(size).press(onClick, tag = tag)
            .background(if (hero) c.accent else Color.Black.copy(alpha = 0.35f), CircleShape)
            .border(1.dp, if (hero) Color.Transparent else Color.White.copy(alpha = 0.3f), CircleShape)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.42f), tint = if (hero) c.onAccent else Color.White) }
}

/** The timeline: drag (or tap) anywhere along it to seek; chapters are gaps, intro and recap tinted. */
@Composable
private fun Timeline(m: PlayerModel) {
    val c = Vyrk.colors
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val dur = m.duration.coerceAtLeast(1)
    val shownMs = dragFrac?.let { (it * dur).toLong() } ?: m.position
    Column(Modifier.fillMaxWidth()) {
        if (!m.isLive) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(fmtTime(shownMs), style = Vyrk.type.labelL, color = if (dragFrac != null) c.accent else c.text)
            Spacer(Modifier.weight(1f))
            if (m.duration > 0) Text("−" + fmtTime(m.duration - shownMs), style = Vyrk.type.labelL, color = c.text)
        }
        val h by animateFloatAsState(if (dragFrac != null) 8f else 4f, label = "bar")
        Canvas(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp)
                .semantics { this.contentDescription = fmtTime(shownMs) }
                .pointerInput(m.duration) {
                    detectTapGestures { o -> if (m.duration > 0) { m.seekTo((o.x / size.width).coerceIn(0f, 1f).times(dur).toLong()); m.touch() } }
                }
                .pointerInput(m.duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f); m.touch() },
                        onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / size.width).coerceIn(0f, 1f); m.touch() },
                        onDragEnd = { dragFrac?.let { f -> if (m.duration > 0) m.seekTo((f * dur).toLong()) }; dragFrac = null; m.touch() },
                        onDragCancel = { dragFrac = null },
                    )
                },
        ) {
            val barH = h.dp.toPx()
            val y = (size.height - barH) / 2f
            val r = CornerRadius(barH / 2, barH / 2)
            fun xOf(ms: Long) = size.width * (ms.toFloat() / dur).coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.2f), topLeft = Offset(0f, y), size = Size(size.width, barH), cornerRadius = r)
            drawRoundRect(Color.White.copy(alpha = 0.35f), topLeft = Offset(0f, y), size = Size(xOf(m.buffered), barH), cornerRadius = r)
            m.segments.filter { it.kind == SegmentKind.INTRO || it.kind == SegmentKind.RECAP }.forEach { s ->
                drawRect(c.warning.copy(alpha = 0.55f), topLeft = Offset(xOf(s.startMs), y), size = Size(xOf(s.endMs) - xOf(s.startMs), barH))
            }
            drawRoundRect(c.accent, topLeft = Offset(0f, y), size = Size(xOf(shownMs), barH), cornerRadius = r)
            m.markers.filter { it.startMs > 0 }.forEach { mk -> drawRect(Color.Black.copy(alpha = 0.85f), topLeft = Offset(xOf(mk.startMs) - 1.5f, y), size = Size(3f, barH)) }
            drawCircle(c.text, radius = if (dragFrac != null) 11.dp.toPx() else 7.dp.toPx(), center = Offset(xOf(shownMs), size.height / 2f))
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val mm = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, sec) else "%d:%02d".format(mm, sec)
}

/** Getting ready: the title's artwork dimmed, its logo or name, and a bar that fills. */
@Composable
private fun Connecting(m: PlayerModel, onExit: () -> Unit) {
    val c = Vyrk.colors
    val p by animateFloatAsState(m.loadProgress, androidx.compose.animation.core.tween(700), label = "load")
    Box(Modifier.fillMaxSize()) {
        val bg = m.meta?.background ?: m.request.meta.background
        if (bg != null) AsyncImage(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(c.background.copy(alpha = 0.84f)))
        Column(Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val logo = m.meta?.logo ?: m.request.meta.logo
            val name = m.meta?.name ?: m.request.meta.name
            if (logo != null) coil3.compose.SubcomposeAsyncImage(logo, name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(90.dp),
                error = { Text(name, style = Vyrk.type.displayS, color = c.text, textAlign = TextAlign.Center, maxLines = 2) })
            else Text(name, style = Vyrk.type.displayS, color = c.text, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f), CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().background(c.accent, CircleShape))
            }
            Spacer(Modifier.height(10.dp))
            Text(m.connecting, style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 2, textAlign = TextAlign.Center)
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp)) {
            PhoneIconButton(R.drawable.ic_left, stringResource(R.string.back), onExit, filled = false, tag = "player:back")
        }
    }
}

@Composable
private fun PhoneMessage(title: String, body: String, buttons: @Composable () -> Unit) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(24.dp).background(c.panel, RoundedCornerShape(22.dp)).padding(24.dp)) {
            Text(title, style = Vyrk.type.headlineM, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(body, style = Vyrk.type.bodyM, color = c.textSecondary)
            Spacer(Modifier.height(18.dp))
            com.vyrk.tv.ui.phone.ChipFlow(spacing = 10.dp) { buttons() }
        }
    }
}

@Composable
private fun Prompt(text: String, yes: String, onYes: () -> Unit, no: String, onNo: () -> Unit) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(20.dp), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.width(340.dp).background(c.panel.copy(alpha = 0.96f), RoundedCornerShape(18.dp)).padding(18.dp)) {
            Text(text, style = Vyrk.type.titleM, color = c.text)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhoneButton(yes, onYes, style = PhoneButtonStyle.PRIMARY, height = 42.dp, tag = "prompt:yes")
                PhoneButton(no, onNo, height = 42.dp, tag = "prompt:no")
            }
        }
    }
}

private fun codecLabel(mime: String?): String? {
    val c = mime?.lowercase()?.replace("-", "") ?: return null
    return when {
        "truehd" in c -> "TrueHD"; "dts" in c -> "DTS"; "eac3" in c || "ec3" in c -> "Dolby Digital+"; "ac3" in c -> "Dolby Digital"
        "mp4a" in c || "aac" in c -> "AAC"; "opus" in c -> "Opus"; "flac" in c -> "FLAC"; "mpeg" in c -> "MP3"; else -> null
    }
}

@Composable
private fun AudioSubs(m: PlayerModel) {
    val c = Vyrk.colors
    val audio = m.engine.audioTracks()
    val subs = TrackSelector.sortForDisplay(m.allTextTracks(), m.sessionSettingsLanguages())
    val inFile = stringResource(R.string.tag_in_file)
    val forced = stringResource(R.string.tag_forced)
    val commentary = stringResource(R.string.tag_commentary)
    val mono = stringResource(R.string.mono)
    val stereo = stringResource(R.string.stereo)
    Text(stringResource(R.string.audio).uppercase(), style = Vyrk.type.eyebrow, color = c.gold, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    audio.forEachIndexed { i, t ->
        val lang = t.language?.takeUnless { it.lowercase() in setOf("und", "mul", "zxx", "mis") }?.let { Languages.name(it) }
        // A label that only repeats the language code ("UND") says nothing: the track gets a number.
        val label = t.label?.takeUnless { it.isBlank() || it.equals(t.language, true) }
        val extra = label?.takeIf { l -> lang == null || !l.equals(lang, true) }
        val channels = when (t.channels) { 1 -> mono; 2 -> stereo; 6 -> "5.1"; 8 -> "7.1"; 0 -> null; else -> "${t.channels}ch" }
        val tag = listOfNotNull(extra, if (TrackSelector.isCommentary(t) && extra?.contains(commentary, true) != true) commentary else null, codecLabel(t.codec), channels).joinToString(" · ")
        val sel = m.selectedAudio == t.id || (m.selectedAudio == null && t.isDefault)
        PhoneListRow(lang ?: label ?: stringResource(R.string.audio_track_n, i + 1), { m.selectAudio(t.id) }, hint = tag.ifBlank { null }, selected = sel, tag = "panel:audio#$i")
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.subtitles).uppercase(), style = Vyrk.type.eyebrow, color = c.gold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    PhoneListRow(stringResource(R.string.off), { m.selectSubtitle(null) }, selected = m.selectedSub == null, tag = "panel:sub_off")
    val counts = subs.groupingBy { (it.language ?: "") + "|" + it.source }.eachCount()
    val seen = mutableMapOf<String, Int>()
    subs.forEachIndexed { i, t ->
        val k = (t.language ?: "") + "|" + t.source
        val n = (seen[k] ?: 0) + 1; seen[k] = n
        val name = t.language?.let { Languages.name(it) } ?: t.label ?: "—"
        val where = if (t.source == "Embedded") inFile else t.source
        val tag = listOfNotNull(if (t.forced) forced else null, where, if ((counts[k] ?: 1) > 1) "#$n" else null).joinToString(" · ")
        PhoneListRow(name, { m.selectSubtitle(t.id) }, hint = tag, selected = m.selectedSub == t.id, tag = "panel:sub#$i")
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.subtitle_delay, "%.1f".format(m.subDelayMs / 1000f)), style = Vyrk.type.titleM, color = c.text)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneButton("−0.5 s", { m.subDelayMs = (m.subDelayMs - 500).coerceAtLeast(-10_000) }, height = 40.dp)
        PhoneButton("+0.5 s", { m.subDelayMs = (m.subDelayMs + 500).coerceAtMost(10_000) }, height = 40.dp)
        PhoneButton(stringResource(R.string.reset), { m.subDelayMs = 0 }, style = PhoneButtonStyle.GHOST, height = 40.dp)
    }
    Text(stringResource(R.string.delay_addon_only), style = Vyrk.type.bodyS, color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Sources(m: PlayerModel) {
    val c = Vyrk.colors
    var expanded by remember { mutableStateOf<String?>(null) }
    m.sourceChoices.forEachIndexed { i, r ->
        if (m.suspectSource(r)) Text(stringResource(R.string.stream_may_be_incomplete), style = Vyrk.type.bodyS, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
        val k = r.candidate.key + r.candidate.addonId
        SourceCard(r, recommended = false, expanded = expanded == k, tag = "player:source#$i", current = m.current?.candidate?.key == r.candidate.key,
            onClick = { m.pickSource(r); m.panel = Panel.NONE }, onLongClick = { expanded = if (expanded == k) null else k })
        Spacer(Modifier.height(8.dp))
    }
    if (m.excluded.isNotEmpty()) Text(stringResource(R.string.not_playable_here), style = Vyrk.type.labelL, color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
    m.excluded.forEach { r -> Text("${r.candidate.addonName} · ${r.info.resolutionLabel} — ${exclusionText(r)}", style = Vyrk.type.bodyS, color = c.textMuted) }
}
