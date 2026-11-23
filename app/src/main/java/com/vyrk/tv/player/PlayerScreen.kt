package com.vyrk.tv.player

import com.vyrk.tv.ui.components.focusHalo
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.tv.R
import com.vyrk.tv.VyrkApp
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.first
import com.vyrk.tv.ui.tryRequestFocus

// The player's screen: loading and error views, the controls and timeline, Up Next and prompts.

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
            update = { view ->
                view.resizeMode = when (m.pictureSize) {
                    PictureSize.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PictureSize.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    PictureSize.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
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
            Phase.INCOMPLETE -> {
                if (m.panel == Panel.SOURCES) SourcesPanel(m)
                else MessageView(stringResource(R.string.incomplete_stream_title), m.message) {
                    VyrkButton(stringResource(R.string.switch_stream), onClick = { m.switchStream() }, style = ButtonStyle.PRIMARY, modifier = it.traceFocus("player:incomplete_switch"))
                    VyrkButton(stringResource(R.string.retry), onClick = { m.retryPicked() }, modifier = Modifier.traceFocus("player:incomplete_retry"))
                    if (m.nextVideo != null) VyrkButton(stringResource(R.string.next_episode), onClick = { m.playNext(auto = false) }, modifier = Modifier.traceFocus("player:incomplete_next"))
                    VyrkButton(stringResource(R.string.exit), onClick = onExit, modifier = Modifier.traceFocus("player:incomplete_exit"))
                }
            }
            Phase.ERROR, Phase.NO_SOURCES -> MessageView(stringResource(R.string.cant_play_title), m.message) {
                if (m.candidates.size + m.excluded.size > 0) VyrkButton(stringResource(R.string.other_sources), onClick = { m.phase = Phase.PLAYING; m.panel = Panel.SOURCES }, modifier = it.traceFocus("player:err_other"))
                VyrkButton(stringResource(R.string.back), onClick = onExit, style = ButtonStyle.PRIMARY, modifier = (if (m.candidates.isEmpty()) it else Modifier).traceFocus("player:err_back"))
            }
            Phase.PLAYING -> {
                if (m.controlsVisible && m.panel == Panel.NONE) Controls(m)
                val seg = m.skipSegment
                if (seg != null && !m.controlsVisible && m.panel == Panel.NONE) SkipPill(m.skipLabel(seg))
                m.notice?.let { Notice(it) }
                if (m.panel == Panel.AUDIO_SUBS) AudioSubsPanel(m)
                if (m.panel == Panel.SOURCES) SourcesPanel(m)
                if (m.panel == Panel.SLEEP) SleepPanel(m)
                if (m.panel == Panel.EPISODES) EpisodesPanel(m)
                if (m.stallPrompt) Prompt(stringResource(R.string.stall_prompt), stringResource(R.string.lower_quality), { m.lowerQuality() }, stringResource(R.string.keep_trying)) { m.stallPrompt = false; m.showControls() }
                if (m.upNext && m.nextVideo != null && !m.controlsVisible) UpNext(m)
                if (m.stillWatching) Prompt(stringResource(R.string.still_watching), stringResource(R.string.continue_playing), { m.confirmStillWatching() }, stringResource(R.string.exit)) { onExit() }
            }
        }
    }
}

/** Subtitle colour, background and size from profile settings. */
fun PlayerModel.engineSubtitleStyle(): Triple<Color, Color, Float> {
    val s = VyrkApp.graphOrNull()?.session?.value?.settings?.value
    return subtitleStyleOf(s?.subtitleColor, s?.subtitleBackground, s?.subtitleSize)
}

/** The subtitle style for these settings; the player and the Settings preview both draw with it. */
fun subtitleStyleOf(color: String?, background: String?, size: Int?): Triple<Color, Color, Float> {
    val fg = when (color) { "YELLOW" -> Color(0xFFFFE066); "CYAN" -> Color(0xFFA9E1F3); else -> Color.White }
    val bg = when (background) { "BOX" -> Color.Black.copy(alpha = 0.75f); "NONE" -> Color.Transparent; else -> Color.Black.copy(alpha = 0.30f) }
    return Triple(fg, bg, (size ?: 100) / 100f)
}

@Composable
private fun ConnectingView(m: PlayerModel, onExit: () -> Unit) {
    val c = Vyrk.colors
    // No buttons: Back leaves. Focus sits on an invisible holder, so keys still reach the player
    // and focus is never lost.
    // The player window may not have focus yet when this first composes: ask until the holder has it.
    val holdReq = remember { FocusRequester() }
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (held) return@LaunchedEffect
            holdReq.tryRequestFocus()
            kotlinx.coroutines.delay(150)
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Focus observers go before the focus target they watch.
        Box(Modifier.size(1.dp).onFocusChanged { held = it.isFocused }.traceFocus("player:connecting").focusRequester(holdReq).focusable())
        val bg = m.meta?.background ?: m.request.meta.background
        if (bg != null) AsyncImage(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(c.background.copy(alpha = 0.82f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            // The title's logo is the loading bar: dim, filling left to right as playback gets ready.
            LogoLoader(
                logo = m.meta?.logo ?: m.request.meta.logo,
                title = m.meta?.name ?: m.request.meta.name,
                progress = m.loadProgress,
                description = m.connecting,
            )
        }
    }
}

/**
 * Draws the logo twice: a faint base and a full-brightness copy clipped to [progress] of its
 * width (no bright edge where the fill is). Falls back to the title in large type.
 */
@Composable
private fun LogoLoader(logo: String?, title: String, progress: Float, description: String) {
    val c = Vyrk.colors
    val p by androidx.compose.animation.core.animateFloatAsState(progress, androidx.compose.animation.core.tween(700), label = "load")
    val box = Modifier.width(460.dp).height(150.dp).semantics { contentDescription = description }
    val painter = coil3.compose.rememberAsyncImagePainter(logo)
    val state by painter.state.collectAsState()
    val hasLogo = logo != null && state !is coil3.compose.AsyncImagePainter.State.Error
    if (hasLogo) {
        androidx.compose.foundation.Canvas(box) {
            val intr = painter.intrinsicSize
            if (state !is coil3.compose.AsyncImagePainter.State.Success || intr == androidx.compose.ui.geometry.Size.Unspecified || intr.width <= 0f || intr.height <= 0f) return@Canvas
            val scale = minOf(size.width / intr.width, size.height / intr.height)
            val w = intr.width * scale
            val h = intr.height * scale
            translate((size.width - w) / 2, (size.height - h) / 2) {
                with(painter) { draw(androidx.compose.ui.geometry.Size(w, h), alpha = 0.18f) }
                clipRect(right = w * p) { with(painter) { draw(androidx.compose.ui.geometry.Size(w, h)) } }
            }
        }
    } else {
        Box(box, contentAlignment = Alignment.Center) {
            val style = Vyrk.type.displayM
            Text(title, style = style, color = c.text.copy(alpha = 0.18f), textAlign = TextAlign.Center, maxLines = 2)
            Text(title, style = style, color = c.text, textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.drawWithContent { clipRect(right = size.width * p) { this@drawWithContent.drawContent() } })
        }
    }
}
@Composable
private fun MessageView(title: String, body: String, buttons: @Composable (Modifier) -> Unit) {
    val c = Vyrk.colors
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { req.tryRequestFocus() }
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

/** "1 h 39 min" / "39 min" from milliseconds. */
@Composable
private fun timeLeft(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return if (min >= 60) stringResource(R.string.time_hm, (min / 60).toInt(), (min % 60).toInt()) else stringResource(R.string.time_m, min.toInt())
}

/** Round button; its label shows underneath while it has focus (space is reserved so nothing jumps). */
@Composable
private fun RoundButton(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    trace: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 46.dp,
    hero: Boolean = false,
) {
    val c = Vyrk.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            interactionSource = interaction,
            modifier = modifier.focusHalo().size(size).traceFocus(trace).semantics { contentDescription = label },
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = if (hero) c.text else Color.White.copy(alpha = 0.10f),
                contentColor = if (hero) c.background else c.text,
                focusedContainerColor = if (hero) c.accent else c.text,
                focusedContentColor = c.background,
            ),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if (hero) 0f else 0.22f))),
                focusedBorder = androidx.tv.material3.Border.None,
            ),
            glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
        ) {
            com.vyrk.tv.ui.components.Icon(icon, null, Modifier.size(size * 0.42f).align(Alignment.Center), tint = androidx.tv.material3.LocalContentColor.current)
        }
        // The label overflows its slot instead of widening the column, so the row never shifts.
        Box(Modifier.width(size).height(22.dp).padding(top = 5.dp), contentAlignment = Alignment.TopCenter) {
            if (focused) Text(label, style = Vyrk.type.labelL, color = c.text, maxLines = 1, softWrap = false,
                textAlign = TextAlign.Center, modifier = Modifier.requiredWidth(260.dp))
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(horizontal = 6.dp, vertical = 0.dp).width(1.dp).height(26.dp).background(Color.White.copy(alpha = 0.22f)))

@Composable
private fun KeyHint(key: String, text: String) {
    val c = Vyrk.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(key, style = Vyrk.type.labelM, color = c.background,
            modifier = Modifier.background(c.textSecondary, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        Text(text, style = Vyrk.type.labelL, color = c.textSecondary)
    }
}

@Composable
private fun Controls(m: PlayerModel) {
    val c = Vyrk.colors
    val playReq = remember { FocusRequester() }
    LaunchedEffect(m.stallPrompt, m.stillWatching) {
        if (!m.stallPrompt && !m.stillWatching) playReq.tryRequestFocus()
    }
    val meta = m.meta
    val title = meta?.name ?: m.request.meta.name
    val v = m.video
    val episode = v?.let { if (it.season != null && it.episode != null) "S${it.season}E${it.episode} · ${it.displayTitle}" else null }
    val year = (meta?.releaseInfo ?: m.request.meta.releaseInfo)?.take(4)
    val paused = !m.playing
    var clock by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); kotlinx.coroutines.delay(20_000) } }
    val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    Box(Modifier.fillMaxSize()) {
        // Paused: the whole frame dims, so the title block reads like a paused film.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (paused) 0.55f else 0f)))
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))

        // Top: the title's logo (or its name in spaced capitals) and the time of day.
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            val logo = meta?.logo ?: m.request.meta.logo
            if (logo != null) {
                coil3.compose.SubcomposeAsyncImage(logo, title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    modifier = Modifier.height(30.dp).width(220.dp),
                    error = { Text(title.uppercase(), style = Vyrk.type.labelL.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text) })
            } else {
                Text(title.uppercase(), style = Vyrk.type.labelL.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(timeFmt.format(Date(clock)), style = Vyrk.type.titleL, color = c.text)
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 18.dp)) {
            Text(
                if (paused) stringResource(R.string.paused).uppercase() else (episode ?: ""),
                style = Vyrk.type.labelL.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(if (paused) 2f else 0.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = c.accent, maxLines = 1,
            )
            Text(title, style = Vyrk.type.displayS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = buildList {
                if (paused && episode != null) add(episode)
                year?.let { add(it) }
                if (m.duration > 0 && !m.isLive) {
                    add(timeLeft(m.duration - m.position).let { stringResource(R.string.time_left, it) })
                    add(stringResource(R.string.ends_at, timeFmt.format(Date(clock + (m.duration - m.position)))))
                }
            }
            Text(facts.joinToString("  ·  "), style = Vyrk.type.bodyL, color = c.textSecondary, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Timeline(m)
            Row(Modifier.padding(top = 4.dp)) {
                Text(fmt(m.scrubTarget ?: m.position), style = Vyrk.type.labelL, color = if (m.scrubTarget != null) c.accent else c.text)
                Spacer(Modifier.weight(1f))
                if (m.duration > 0 && !m.isLive) Text("−" + fmt(m.duration - (m.scrubTarget ?: m.position)), style = Vyrk.type.labelL, color = c.text)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.TopCenter).focusGroup(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RoundButton(R.drawable.ic_subtitles, stringResource(R.string.audio_subtitles), { m.panel = Panel.AUDIO_SUBS }, "player:audio_subs")
                    RoundButton(R.drawable.ic_cc, stringResource(R.string.quick_subtitles), { m.toggleSubtitles(); m.touch() }, "player:cc")
                    RoundButton(R.drawable.ic_sources, stringResource(R.string.other_sources), { m.panel = Panel.SOURCES }, "player:sources")
                    Box(Modifier.padding(top = 10.dp)) { Divider() }
                    RoundButton(R.drawable.ic_back10, stringResource(R.string.back_10), { m.seekBy(-10_000); m.touch() }, "player:back10")
                    RoundButton(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (m.playing) R.string.pause else R.string.play),
                        { m.togglePlay(); m.touch() }, "player:play_pause", Modifier.focusRequester(playReq), size = 64.dp, hero = true)
                    RoundButton(R.drawable.ic_fwd10, stringResource(R.string.forward_10), { m.seekBy(10_000); m.touch() }, "player:fwd10")
                    Box(Modifier.padding(top = 10.dp)) { Divider() }
                    RoundButton(R.drawable.ic_crop, stringResource(R.string.picture_size), { m.cyclePictureSize(); m.touch() }, "player:picture")
                    RoundButton(R.drawable.ic_sleep, stringResource(R.string.sleep), { m.panel = Panel.SLEEP }, "player:sleep")
                    if (m.video != null && m.meta?.videos.orEmpty().size > 1) RoundButton(R.drawable.ic_rows, stringResource(R.string.episodes), { m.panel = Panel.EPISODES }, "player:episodes")
                    if (m.nextVideo != null) RoundButton(R.drawable.ic_next, stringResource(R.string.next_episode), { m.playNext(auto = false) }, "player:next")
                    m.skipSegment?.let { seg ->
                        Box(Modifier.padding(top = 4.dp)) {
                            VyrkButton(stringResource(m.skipLabel(seg)), onClick = { m.skipNow() }, icon = R.drawable.ic_next, style = ButtonStyle.PRIMARY, modifier = Modifier.traceFocus("player:skip"))
                        }
                    }
                }
                Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyHint("OK", stringResource(R.string.hint_play_pause))
                    KeyHint("◀ ▶", stringResource(R.string.hint_seek))
                }
            }
        }
    }
}

@Composable
private fun Prompt(text: String, yes: String, onYes: () -> Unit, no: String, onNo: () -> Unit) {
    val c = Vyrk.colors
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { req.tryRequestFocus() }
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.width(380.dp).background(c.panel, RoundedCornerShape(18.dp)).padding(20.dp)
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
            Text(text, style = Vyrk.type.titleL, color = c.text)
            Spacer(Modifier.height(12.dp))
            WrapRow {
                VyrkButton(yes, onClick = onYes, style = ButtonStyle.PRIMARY, modifier = Modifier.focusRequester(req).traceFocus("prompt:yes"))
                VyrkButton(no, onClick = onNo, modifier = Modifier.traceFocus("prompt:no"))
            }
        }
    }
}

/**
 * Up next, when the credits start: the next episode's still, its number and title, a bar that
 * empties with the countdown, then Play now and Cancel. OK plays it; Back or Cancel keeps watching.
 */
@Composable
private fun UpNext(m: PlayerModel) {
    val nv = m.nextVideo ?: return
    val c = Vyrk.colors
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { req.tryRequestFocus() }
    val left by androidx.compose.animation.core.animateFloatAsState((m.upNextCountdown / 10f).coerceIn(0f, 1f),
        androidx.compose.animation.core.tween(950, easing = androidx.compose.animation.core.LinearEasing), label = "countdown")
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.width(440.dp).background(c.panel.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
                .border(1.dp, c.gold.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
        ) {
            Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.raised)) {
                val still = nv.thumbnail ?: m.meta?.background ?: m.request.meta.background
                if (still != null) AsyncImage(still, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to Color.Transparent, 1f to c.panel)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(left).fillMaxHeight().background(c.accent))
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp)) {
                Text(stringResource(R.string.up_next).uppercase() + "  ·  " + m.upNextCountdown + " s",
                    style = Vyrk.type.labelL.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.gold)
                val number = if (nv.season != null && nv.episode != null) "S${nv.season}E${nv.episode}  " else ""
                Text(number + nv.displayTitle, style = Vyrk.type.headlineS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                WrapRow {
                    VyrkButton(stringResource(R.string.play_now), onClick = { m.playNext(auto = false) }, icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                        modifier = Modifier.focusRequester(req).traceFocus("prompt:yes"))
                    VyrkButton(stringResource(R.string.cancel), onClick = { m.dismissUpNext() }, modifier = Modifier.traceFocus("prompt:no"))
                }
            }
        }
    }
}

@Composable
private fun SkipPill(label: Int) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().padding(end = 48.dp, bottom = 56.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.background(c.accent, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.vyrk.tv.ui.components.Icon(R.drawable.ic_next, null, Modifier.size(16.dp), tint = c.onAccent)
            Text(stringResource(label), style = Vyrk.type.titleM, color = c.onAccent)
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
