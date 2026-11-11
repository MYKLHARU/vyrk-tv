package com.vyrk.tv.ui.browse

import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
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
data class TitleArt(val logos: List<String>, val backdrops: List<String>, val logosLoading: Boolean = false) {
    companion object { val NONE = TitleArt(emptyList(), emptyList()) }
}

/** How long one wallpaper takes to blend into the next. */
private const val WALLPAPER_FADE_MS = 700

/**
 * Full-screen wallpaper behind the hero, the title header and the rows: the title's artwork from
 * the top of the screen, darkened on the left under the logo and text, with a teal haze, blending
 * into the app's own ambient background towards the bottom, where the rows sit.
 *
 * Each title keeps its successful image while its details arrive. A new title starts against
 * the ambient background until its own image is ready. Requests are screen-sized, like the
 * hero's prefetch, so prefetched pictures come straight from memory.
 */
@Composable
fun Wallpaper(titleKey: String, urls: List<String>, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    val shade = c.shade
    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = remember(context) { coil3.SingletonImageLoader.get(context) }
    val dm = context.resources.displayMetrics
    fun request(url: String) = coil3.request.ImageRequest.Builder(context).data(url).size(dm.widthPixels, dm.heightPixels).build()
    var attempt by remember(titleKey, urls) { mutableIntStateOf(0) }
    val target = urls.getOrNull(attempt)
    // Image state belongs to the title whose text is on screen.
    var shown by remember(titleKey) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(titleKey, target) {
        if (target == null) { shown = null; return@LaunchedEffect }
        // A successful image stays for this title while richer metadata arrives.
        if (shown != null) return@LaunchedEffect
        val result = loader.execute(request(target))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (result is coil3.request.SuccessResult) shown = target
        else if (urls.getOrNull(attempt) == target) { shown = null; attempt += 1 }
    }
    Box(modifier) {
        // The app's own background (navy, violet low on the left, teal high on the right), as on
        // Library and Settings: what the picture blends into at the bottom.
        com.vyrk.tv.ui.components.AmbientBackground()
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Fully there down to about half way, then melting into the background below.
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.50f to Color.Black, 0.68f to Color.Black.copy(alpha = 0.62f), 0.86f to Color.Black.copy(alpha = 0.16f), 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) {
            androidx.compose.runtime.key(titleKey) {
                Crossfade(shown, animationSpec = com.vyrk.tv.ui.theme.motionSpec(WALLPAPER_FADE_MS), label = "wallpaper") { u ->
                    if (u != null) AsyncImage(request(u), null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
                }
            }
            // Left: under the logo, facts and synopsis.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to shade.copy(alpha = 0.92f), 0.30f to shade.copy(alpha = 0.78f), 0.50f to shade.copy(alpha = 0.50f), 0.70f to shade.copy(alpha = 0.22f), 1f to shade.copy(alpha = 0.12f),
            )))
            // A cold fjord-blue haze where the picture meets the text (aurora green tinted faces).
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(listOf(c.ambientViolet.copy(alpha = 0.16f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.32f, size.height * 0.30f), radius = size.width * 0.40f))
            })
        }
        // Under the bar.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to shade.copy(alpha = 0.30f), 0.14f to Color.Transparent)))
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
fun HeroLogo(titleId: String, name: String, logos: List<String>, maxWidth: Dp, maxHeight: Dp, textStyle: TextStyle, modifier: Modifier = Modifier, loading: Boolean = false) {
    val kept = shownLogos[titleId]
    val candidates = remember(titleId, logos, kept) { (listOfNotNull(kept) + logos).distinct() }
    var failed by remember(titleId) { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }
    val url = if (kept != null && kept !in failed) kept else candidates.firstOrNull { it !in failed }
    var textFallback by remember(titleId) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(titleId, url, loading) {
        // Metadata and provider lookups own the loading state. Never latch text over a valid
        // logo which arrives after a timeout, provider refresh or previously missing metadata.
        if (url != null || loading) textFallback = false
        else { kotlinx.coroutines.delay(4_000); textFallback = true }
    }
    if (textFallback && url == null && !loading) {
        Box(modifier.width(maxWidth).heightIn(max = maxHeight).fillMaxHeight(), contentAlignment = Alignment.BottomStart) {
            Text(name.ifBlank { " " }, style = textStyle, color = Vyrk.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    if (url == null) {
        Box(modifier.width(maxWidth).heightIn(max = maxHeight).fillMaxHeight())
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) {
        coil3.request.ImageRequest.Builder(context).data(url)
            .transformations(com.vyrk.tv.ui.components.TrimTransparent()).build()
    }
    var loaded by remember(titleId, url) { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier.width(maxWidth).heightIn(max = maxHeight).fillMaxHeight(), contentAlignment = Alignment.BottomStart) {
        // Reserve the logo slot while loading; text is only a settled failure fallback.
        androidx.compose.runtime.key(titleId, url) {
            AsyncImage(
                model = request, contentDescription = if (loaded) name else null, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                onSuccess = { loaded = true; shownLogos.putIfAbsent(titleId, url) },
                onError = { if (shownLogos[titleId] == url) shownLogos.remove(titleId); failed = failed + url },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Logo artwork if the title has one, else the title as text. */
@Composable
fun TitleLogo(meta: MetaPreview, maxWidth: Dp, maxHeight: Dp) {
    val logo = meta.logo
    val text: @Composable () -> Unit = {
        Text(meta.name, style = Vyrk.type.cinematicM, color = Vyrk.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxWidth))
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
        if (!rating.isNullOrBlank()) Tag("IMDb $rating", color = c.background, background = c.gold)
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
            HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), full?.name?.takeIf { it.isNotBlank() } ?: meta.name, art.logos, HERO_LOGO_W, HERO_LOGO_H, Vyrk.type.cinematicL, Modifier.weight(1f), loading = art.logosLoading)
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
            HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), meta.name.ifBlank { full?.name.orEmpty() }, art.logos, HERO_LOGO_W, HERO_LOGO_H, Vyrk.type.cinematicM, Modifier.weight(1f), loading = art.logosLoading)
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
