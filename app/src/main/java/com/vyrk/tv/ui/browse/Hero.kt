package com.vyrk.tv.ui.browse

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import com.vyrk.tv.ui.components.glassPanel
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vyrk.tv.ui.theme.motionSpec
import com.vyrk.tv.ui.theme.Motion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.AsyncImage
import coil3.request.transformations
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.tv.R
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkIconButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus

/** Artwork candidates for one title, best first; the next one is tried when one fails to load. */
@Immutable
data class TitleArt(val logos: List<String>, val backdrops: List<String>) {
    companion object { val NONE = TitleArt(emptyList(), emptyList()) }
}

/**
 * Full-screen wallpaper behind the hero, the title header and the rows: the title's artwork across
 * the whole screen, never fading out into the page colour. Darkened on the left under the logo and
 * text and towards the bottom under the rows (the picture still shows through), with VYRK's teal
 * and violet light. Cross-fades from one title to the next.
 */
@Composable
fun Wallpaper(urls: List<String>, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    val shade = c.shade
    var attempt by remember(urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(attempt)
    Box(modifier.background(c.background)) {
        Crossfade(url, animationSpec = com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.SLOW), label = "wallpaper") { u ->
            if (u != null) AsyncImage(
                u, null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
                onError = { if (urls.getOrNull(attempt) == u) attempt += 1 },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Left: under the logo, facts and synopsis.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to shade.copy(alpha = 0.92f), 0.30f to shade.copy(alpha = 0.78f), 0.50f to shade.copy(alpha = 0.50f), 0.70f to shade.copy(alpha = 0.22f), 1f to shade.copy(alpha = 0.12f),
        )))
        // VYRK's light: a teal haze where the picture meets the text, violet low on the left.
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(c.ambientTeal.copy(alpha = 0.18f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.32f, size.height * 0.30f), radius = size.width * 0.40f))
            drawRect(Brush.radialGradient(listOf(c.ambientViolet.copy(alpha = 0.26f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, size.height * 0.88f), radius = size.width * 0.36f))
        })
        // Top under the bar, then darker towards the bottom under the rows; never solid.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to shade.copy(alpha = 0.30f), 0.14f to Color.Transparent, 0.48f to shade.copy(alpha = 0.10f), 0.72f to shade.copy(alpha = 0.50f), 1f to shade.copy(alpha = 0.80f),
        )))
    }
}

/**
 * The logo each title showed first, by title id. The candidates change while a title loads (the
 * row's logo, then the one in its full details, then TMDB's), and following them made the logo
 * swap to a different picture moments after it appeared. Once a logo has loaded it stays, here
 * and wherever else the title is shown (hero, header, info page).
 */
private val shownLogos = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * Title logo for the hero, header and info page: fitted into [maxWidth] × the height it is given
 * (up to [maxHeight]), so small logo files are scaled up instead of drawn at their own pixel size;
 * aspect ratio kept, anchored bottom-left. Falls back through [logos], then to the title in large
 * bold type. The first logo that loads for [titleId] is kept (see [shownLogos]).
 */
@Composable
fun HeroLogo(titleId: String, name: String, logos: List<String>, maxWidth: Dp, maxHeight: Dp, textStyle: TextStyle, modifier: Modifier = Modifier) {
    val kept = shownLogos[titleId]
    val candidates = remember(titleId, logos, kept) { (listOfNotNull(kept) + logos).distinct() }
    var failed by remember(titleId) { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }
    val url = if (kept != null && kept !in failed) kept else candidates.firstOrNull { it !in failed }
    if (url == null) {
        Box(modifier.widthIn(max = maxWidth), contentAlignment = Alignment.BottomStart) {
            Text(name.ifBlank { " " }, style = textStyle, color = Vyrk.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) {
        coil3.request.ImageRequest.Builder(context).data(url)
            .transformations(com.vyrk.tv.ui.components.TrimTransparent()).build()
    }
    AsyncImage(
        model = request, contentDescription = name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
        onSuccess = { shownLogos.putIfAbsent(titleId, url) },
        onError = { if (shownLogos[titleId] == url) shownLogos.remove(titleId); failed = failed + url },
        modifier = modifier.width(maxWidth).heightIn(max = maxHeight).fillMaxHeight(),
    )
}

/** Logo artwork if the title has one, else the title as text. */
@Composable
fun TitleLogo(meta: MetaPreview, maxWidth: Dp, maxHeight: Dp) {
    val logo = meta.logo
    val text: @Composable () -> Unit = {
        Text(meta.name, style = Vyrk.type.displayM, color = Vyrk.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxWidth))
    }
    if (logo == null) text() else SubcomposeAsyncImage(
        model = logo, contentDescription = meta.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight),
        error = { text() }, loading = { Box(Modifier.height(maxHeight)) },
    )
}

/** Height of the facts line and of the genre line when they are [reserve]d. */
private val FACTS_H = 24.dp

/**
 * Year · type · runtime · IMDb rating, then genre chips. With [reserve], both lines keep their
 * height before the title's details arrive (genres and rating come later), so text below and the
 * logo above don't move when they do.
 */
@Composable
fun MetaFacts(meta: MetaPreview, full: MetaItem?, reserve: Boolean = false) {
    val c = Vyrk.colors
    val parts = listOfNotNull(
        (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() },
        when (meta.type) { "movie" -> str(R.string.type_movie); "series" -> str(R.string.type_series); "tv" -> str(R.string.type_live); else -> meta.type.replaceFirstChar { it.uppercase() } },
        com.vyrk.core.addon.RuntimeText.format(full?.runtime ?: meta.runtime),
    )
    Row(if (reserve) Modifier.height(FACTS_H) else Modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(parts.joinToString("  ·  "), style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1)
        val rating = full?.imdbRating ?: meta.imdbRating
        if (!rating.isNullOrBlank()) Tag("IMDb $rating", color = c.background, background = c.warning)
    }
    val genres = (full?.allGenres ?: meta.genres).take(4)
    if (reserve) {
        Box(Modifier.height(6.dp))
        // One line, always there: genres fill it when they arrive.
        Row(Modifier.height(FACTS_H), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { genres.forEach { Tag(it) } }
    } else if (genres.isNotEmpty()) {
        Box(Modifier.height(8.dp))
        WrapRow(spacing = 6.dp) { genres.forEach { Tag(it) } }
    }
}

/** Starring and Director lines, with small icons in the hero. With [reserve], both keep their height while empty. */
@Composable
fun CreditLines(full: MetaItem?, meta: MetaPreview, reserve: Boolean = false, icons: Boolean = false) {
    val cast = (full?.castNames ?: meta.cast).take(5)
    val director = (full?.directorNames ?: meta.director).take(2)
    val style = Vyrk.type.bodyS
    val color = Vyrk.colors.textSecondary
    @Composable fun line(icon: Int, text: String?) {
        if (text == null && !reserve) return
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icons) com.vyrk.tv.ui.components.Icon(icon, null, Modifier.padding(end = 7.dp).size(13.dp), tint = Vyrk.colors.textMuted.copy(alpha = if (text == null) 0f else 1f))
            Text(text ?: " ", style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    line(R.drawable.ic_people, cast.takeIf { it.isNotEmpty() }?.let { str(R.string.starring, it.joinToString(", ")) })
    line(R.drawable.ic_clapper, director.takeIf { it.isNotEmpty() }?.let { str(R.string.director_line, it.joinToString(", ")) })
}

/**
 * Logo box shared by the hero and the title header, so a title's logo keeps its size as focus
 * moves between them. Logos are trimmed of empty margins and scaled to fit; 360 dp wide leaves
 * the artwork room, as in the reference layout.
 */
private val HERO_LOGO_W = 360.dp
private val HERO_LOGO_H = 150.dp
private val HERO_TEXT_W = 520.dp

@Composable
fun Hero(
    items: List<MetaPreview>,
    index: Int,
    full: MetaItem?,
    art: TitleArt,
    height: Dp,
    hasProgress: Boolean,
    watchReq: FocusRequester,
    onWatch: () -> Unit,
    onMenu: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val meta = items.getOrNull(index) ?: return
    val c = Vyrk.colors
    val carousel = items.size > 1
    // Which arrow to light up briefly after Left (-1) or Right (+1) on Watch now.
    var pressed by remember { mutableIntStateOf(0) }
    LaunchedEffect(pressed, index) { if (pressed != 0) { kotlinx.coroutines.delay(260); pressed = 0 } }
    Box(
        Modifier.fillMaxWidth().height(height).onFocusChanged { onFocusChanged(it.hasFocus) }
            // Coming in from the tabs or the rows always lands on Watch now, not on whichever
            // button (library, info, arrows) happens to sit nearest.
            .focusProperties { onEnter = { runCatching { watchReq.requestFocus() } } }
            .focusGroup()
    ) {
        // Bottom-anchored. Every line below the logo keeps a fixed height, so the logo's share (all
        // that's left) never changes and nothing moves when the title's details arrive.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxHeight().padding(start = Dimens.screenMargin, top = 6.dp, bottom = 22.dp).width(HERO_TEXT_W),
            verticalArrangement = Arrangement.Bottom,
        ) {
            HeroLogo(meta.id, full?.name?.takeIf { it.isNotBlank() } ?: meta.name, art.logos, HERO_LOGO_W, HERO_LOGO_H, Vyrk.type.displayL, Modifier.weight(1f))
            Box(Modifier.height(12.dp))
            MetaFacts(meta, full, reserve = true)
            Box(Modifier.height(10.dp))
            Text(full?.description ?: meta.description ?: "", style = Vyrk.type.bodyM, color = c.text.copy(alpha = 0.88f), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = HERO_TEXT_W))
            Box(Modifier.height(8.dp))
            Column(Modifier.widthIn(max = HERO_TEXT_W), verticalArrangement = Arrangement.spacedBy(2.dp)) { CreditLines(full, meta, reserve = true, icons = true) }
            Box(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VyrkButton(
                    if (hasProgress) str(R.string.resume) else str(R.string.watch_now),
                    onClick = onWatch, onLongClick = onMenu, icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                    height = 42.dp, horizontalPadding = 22.dp, glow = true, shape = Dimens.pillShape,
                    modifier = Modifier.focusRequester(watchReq).traceFocus("hero:watch")
                        // Left and Right on Watch now move through the carousel; no need to reach the arrows.
                        // A held key doesn't race through the titles (repeats are ignored).
                        .onPreviewKeyEvent { e ->
                            val first = e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0
                            when {
                                e.type == KeyEventType.KeyUp && e.key == Key.Menu -> { onMenu(); true }
                                carousel && e.key == Key.DirectionLeft -> { if (first) { pressed = -1; onPrev() }; e.type == KeyEventType.KeyDown }
                                carousel && e.key == Key.DirectionRight -> { if (first) { pressed = 1; onNext() }; e.type == KeyEventType.KeyDown }
                                else -> false
                            }
                        },
                )
            }
        }
        // Carousel: an arrow at each side of the hero (shown, not focused: Left and Right on Watch now
        // move through the titles and light the arrow up), and the position dots level with Watch now.
        if (carousel) {
            HeroArrow(R.drawable.ic_left, lit = pressed < 0, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
            HeroArrow(R.drawable.ic_right, lit = pressed > 0, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = Dimens.screenMargin, bottom = 39.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                items.indices.forEach { i ->
                    val on = i == index
                    val w by androidx.compose.animation.core.animateDpAsState(if (on) 20.dp else 7.dp, motionSpec(Motion.STANDARD), label = "dot")
                    Box(Modifier.size(w, 7.dp).background(if (on) c.accent else c.text.copy(alpha = 0.32f), RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** A carousel arrow at the side of the hero. Not focusable; [lit] when its key was just pressed. */
@Composable
private fun HeroArrow(icon: Int, lit: Boolean, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    val fill by androidx.compose.animation.animateColorAsState(if (lit) c.accent else c.shade.copy(alpha = 0.22f), motionSpec(Motion.FAST), label = "arrow-fill")
    val ink by androidx.compose.animation.animateColorAsState(if (lit) c.onAccent else c.text, motionSpec(Motion.FAST), label = "arrow-ink")
    val zoom by androidx.compose.animation.core.animateFloatAsState(if (lit) com.vyrk.tv.ui.theme.focusZoom(1.12f) else 1f, motionSpec(Motion.FAST), label = "arrow-zoom")
    Box(
        modifier.size(36.dp).graphicsLayer { scaleX = zoom; scaleY = zoom }
            .background(fill, CircleShape)
            .border(1.dp, if (lit) c.accent else c.text.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { com.vyrk.tv.ui.components.Icon(icon, null, Modifier.size(16.dp), tint = ink) }
}

/** Header shown while a poster has focus: artwork, logo and facts for that title. */
@Composable
fun BoxScope.TitleHeader(meta: MetaPreview, full: MetaItem?, art: TitleArt, height: Dp, trailerPlaying: Boolean) {
    val c = Vyrk.colors
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(height)) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxHeight().padding(start = Dimens.screenMargin, top = 12.dp, bottom = 14.dp).width(500.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Logo: everything the fixed-height lines below leave, so short or square logos still read
            // from the sofa and the text never shifts as a title's details load.
            HeroLogo(meta.id, meta.name.ifBlank { full?.name.orEmpty() }, art.logos, HERO_LOGO_W, HERO_LOGO_H, Vyrk.type.displayM, Modifier.weight(1f))
            Box(Modifier.height(10.dp))
            MetaFacts(meta, full, reserve = true)
            Box(Modifier.height(8.dp))
            Text(full?.description ?: meta.description ?: "", style = Vyrk.type.bodyS, color = c.textSecondary, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.height(4.dp))
            CreditLines(full, meta, reserve = true, icons = true)
            Box(Modifier.height(6.dp))
            Text(str(R.string.header_hint), style = Vyrk.type.labelS, color = c.textMuted)
        }
    }
}

@Composable
fun Top10Card(onAdd: () -> Unit, onNotNow: () -> Unit, addReq: FocusRequester, onFocus: (Boolean) -> Unit) {
    val c = Vyrk.colors
    Row(
        Modifier.padding(horizontal = Dimens.screenMargin).fillMaxWidth()
            .glassPanel()
            .padding(horizontal = 26.dp, vertical = 18.dp)
            .onFocusChanged { onFocus(it.hasFocus) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("10", style = Vyrk.type.displayL.copy(fontSize = Vyrk.type.displayL.fontSize * 1.6f), color = c.accent)
        Column(Modifier.weight(1f)) {
            Text(str(R.string.top10_card_title), style = Vyrk.type.headlineM, color = c.text)
            Box(Modifier.height(4.dp))
            Text(str(R.string.top10_card_body), style = Vyrk.type.bodyM, color = c.textSecondary)
            Box(Modifier.height(12.dp))
            WrapRow {
                VyrkButton(str(R.string.top10_add), onClick = onAdd, style = ButtonStyle.PRIMARY, shape = Dimens.pillShape, modifier = Modifier.focusRequester(addReq).traceFocus("top10card:add"))
                VyrkButton(str(R.string.not_now), onClick = onNotNow, style = ButtonStyle.OUTLINE, shape = Dimens.pillShape, modifier = Modifier.traceFocus("top10card:not_now"))
            }
        }
    }
}
