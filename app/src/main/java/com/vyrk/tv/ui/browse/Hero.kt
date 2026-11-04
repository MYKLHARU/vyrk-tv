package com.vyrk.tv.ui.browse

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
 * Cinematic backdrop: the picture fills the right of the hero (subjects usually sit right of
 * centre) and stays untinted there. A black-navy fade comes in from the left under the text and
 * from the bottom into the page; nothing covers the whole image.
 */
@Composable
fun Backdrop(urls: List<String>, modifier: Modifier = Modifier) {
    val bg = Vyrk.colors.background
    val shade = Vyrk.colors.shade
    var attempt by remember(urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(attempt)
    Box(modifier) {
        Crossfade(url, label = "backdrop") { u ->
            if (u != null) Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    u, null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
                    onError = { if (urls.getOrNull(attempt) == u) attempt += 1 },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.80f),
                )
            }
        }
        // Left: solid under the title and text, clear from about two-thirds across.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to shade, 0.22f to shade, 0.36f to shade.copy(alpha = 0.82f), 0.50f to shade.copy(alpha = 0.40f), 0.64f to Color.Transparent,
        )))
        // Bottom: into the page colour, where the first row starts.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.52f to Color.Transparent, 0.80f to bg.copy(alpha = 0.70f), 1f to bg)))
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

/** Starring and Director lines. With [reserve], both lines keep their height while empty. */
@Composable
fun CreditLines(full: MetaItem?, meta: MetaPreview, reserve: Boolean = false) {
    val cast = (full?.castNames ?: meta.cast).take(5)
    val director = (full?.directorNames ?: meta.director).take(2)
    val style = Vyrk.type.bodyS
    val color = Vyrk.colors.textSecondary
    if (cast.isNotEmpty()) Text(str(R.string.starring, cast.joinToString(", ")), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    else if (reserve) Text(" ", style = style)
    if (director.isNotEmpty()) Text(str(R.string.director_line, director.joinToString(", ")), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    else if (reserve) Text(" ", style = style)
}

/**
 * Hero logo box. Logos are trimmed of empty margins and scaled to fill it, so a typical logo is
 * about 1.7× the size it had (it was drawn at its own pixel size, padding included).
 */
private val HERO_LOGO_W = 480.dp
private val HERO_LOGO_H = 200.dp
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
    onInfo: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val meta = items.getOrNull(index) ?: return
    val c = Vyrk.colors
    Box(Modifier.fillMaxWidth().height(height).onFocusChanged { onFocusChanged(it.hasFocus) }) {
        // Bottom-anchored. Every line below the logo keeps a fixed height, so the logo's share (all
        // that's left) never changes and nothing moves when the title's details arrive.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxHeight().padding(start = Dimens.screenMargin, top = 6.dp, bottom = 22.dp).width(HERO_TEXT_W),
            verticalArrangement = Arrangement.Bottom,
        ) {
            HeroLogo(meta.id, full?.name?.takeIf { it.isNotBlank() } ?: meta.name, art.logos, HERO_LOGO_W, HERO_LOGO_H, Vyrk.type.displayL, Modifier.weight(1f))
            Box(Modifier.height(14.dp))
            MetaFacts(meta, full, reserve = true)
            Box(Modifier.height(10.dp))
            Text(full?.description ?: meta.description ?: "", style = Vyrk.type.bodyM, color = c.textSecondary, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = HERO_TEXT_W))
            Box(Modifier.height(6.dp))
            Column(Modifier.widthIn(max = HERO_TEXT_W)) { CreditLines(full, meta, reserve = true) }
            Box(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VyrkButton(
                    if (hasProgress) str(R.string.resume) else str(R.string.watch_now),
                    onClick = onWatch, onLongClick = onInfo, icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                    height = 44.dp, horizontalPadding = 22.dp, glow = true,
                    modifier = Modifier.focusRequester(watchReq).traceFocus("hero:watch")
                        .onPreviewKeyEvent { e ->
                            when {
                                e.type == KeyEventType.KeyUp && e.key == Key.Menu -> { onInfo(); true }
                                // Left and Right on the only button move between featured titles.
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && items.size > 1 -> { onPrev(); true }
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && items.size > 1 -> { onNext(); true }
                                else -> false
                            }
                        },
                )
                if (items.size > 1) {
                    // Position dots only.
                    Row(Modifier.padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        items.indices.forEach { i ->
                            val on = i == index
                            Box(Modifier.size(if (on) 22.dp else 8.dp, 8.dp).background(if (on) c.accent else c.text.copy(alpha = 0.35f), RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
    }
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
            HeroLogo(meta.id, meta.name.ifBlank { full?.name.orEmpty() }, art.logos, 420.dp, 170.dp, Vyrk.type.displayM, Modifier.weight(1f))
            Box(Modifier.height(10.dp))
            MetaFacts(meta, full, reserve = true)
            Box(Modifier.height(8.dp))
            Text(full?.description ?: meta.description ?: "", style = Vyrk.type.bodyS, color = c.textSecondary, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.height(4.dp))
            CreditLines(full, meta, reserve = true)
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
            .background(Brush.horizontalGradient(listOf(c.raised, c.panel)), Dimens.panelShape)
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
                VyrkButton(str(R.string.top10_add), onClick = onAdd, style = ButtonStyle.PRIMARY, modifier = Modifier.focusRequester(addReq).traceFocus("top10card:add"))
                VyrkButton(str(R.string.not_now), onClick = onNotNow, modifier = Modifier.traceFocus("top10card:not_now"))
            }
        }
    }
}
