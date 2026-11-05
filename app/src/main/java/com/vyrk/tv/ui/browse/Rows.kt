package com.vyrk.tv.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.library.LibraryItem
import com.vyrk.tv.R
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.focusGlow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Jakarta
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

/** What a poster shows from the live library. */
data class PosterState(val progress: Float, val watched: Boolean, val isNew: Boolean)

fun posterState(item: LibraryItem?, newIds: Set<String>): PosterState =
    PosterState(
        progress = if (item?.isInContinueWatching == true) item.progress else 0f,
        watched = item?.watched == true,
        isNew = newIds.isNotEmpty(),
    )

class PosterCallbacks(
    val onClick: (MetaPreview, Int) -> Unit,
    val onLongClick: (MetaPreview, Int) -> Unit,
    val onPlayPause: (MetaPreview, Int) -> Unit,
    val onFocused: (MetaPreview, Int) -> Unit,
)

@Composable
fun Poster(
    meta: MetaPreview,
    width: Dp,
    state: PosterState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayPause: () -> Unit,
    traceLabel: String,
) {
    val c = Vyrk.colors
    val height = width * 1.5f
    val year = meta.releaseInfo?.take(4).orEmpty()
    val pct = (state.progress * 100).toInt()
    val newLabel = str(R.string.badge_new)
    val desc = buildString {
        append(meta.name); if (year.isNotEmpty()) append(", ").append(year)
        if (pct > 0) append(", ").append(pct).append("%")
        if (state.watched) append(", ✓")
        if (state.isNew) append(", ").append(newLabel)
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .size(width, height)
            .semantics { contentDescription = desc }
            .traceFocus(traceLabel)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Menu -> { onLongClick(); true }
                    Key.MediaPlayPause, Key.MediaPlay -> { onPlayPause(); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(Dimens.posterShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = c.raised, focusedContainerColor = c.raised),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.06f)),
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.accent.copy(alpha = 0.8f))),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        // Never a blank card: the add-on's poster, then Cinemeta's by IMDb id, then the title on
        // a tinted card (or the type, when even the name is missing).
        val fallback: @Composable () -> Unit = {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.raised, c.panel))).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    meta.name.ifBlank { str(if (meta.type == "series") R.string.type_series else R.string.type_movie) },
                    style = Vyrk.type.titleM, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        // AsyncImage rather than SubcomposeAsyncImage: no subcomposition per poster while rows scroll.
        val candidates = remember(meta.poster, meta.id) { listOfNotNull(meta.poster, com.vyrk.core.art.Artwork.metahubPoster(meta.id)).distinct() }
        var attempt by remember(candidates) { mutableIntStateOf(0) }
        val url = candidates.getOrNull(attempt)
        if (url != null) {
            AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { if (candidates.getOrNull(attempt) == url) attempt += 1 })
        } else fallback()
        if (state.isNew) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).background(c.accent, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(newLabel, style = Vyrk.type.labelS, color = c.onAccent)
            }
        }
        if (state.watched) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).background(c.success, CircleShape), contentAlignment = Alignment.Center) {
                Icon(R.drawable.ic_check, null, Modifier.size(11.dp), tint = c.background)
            }
        }
        if (state.progress > 0f) {
            // Slim blue bar inset from the edges, on a soft shade so it reads on bright posters.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Box(
                Modifier.align(Alignment.BottomCenter).padding(start = 9.dp, end = 9.dp, bottom = 9.dp).fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.28f)),
            ) {
                Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.03f, 1f)).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
            }
        }
    }
}

/** Width of a rank number's box as a share of the poster's height (85 : 148). */
private const val RANK_BOX = 0.574f
/** Outline thickness as a share of the poster's height. */
private const val RANK_STROKE = 0.016f
/** Where the outline starts fading towards the poster, as a share of the box's width. */
private const val RANK_FADE_FROM = 0.55f

/** Overall strength by rank: #1 strongest, stepping down to 30 % from #5. */
private fun rankAlpha(rank: Int): Float = when (rank) { 1 -> 0.50f; 2 -> 0.45f; 3 -> 0.40f; 4 -> 0.35f; else -> 0.30f }

/**
 * Top 10 rank, drawn from VYRK's own font: the digits' outline as tall as the poster beside them
 * (the glyph ink, not its line box, is scaled to the poster's height), in a box 0.574 of that
 * height wide. A narrow digit sits against the poster; a wide one runs into it and is cut off at
 * the box's edge. The outline is white on the left and fades to clear towards the poster, and the
 * whole number gets fainter down the ranks.
 */
@Composable
fun RankNumber(rank: Int, height: Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val text = rank.toString()
    val heightPx = with(density) { height.toPx() }
    val strokePx = heightPx * RANK_STROKE
    val boxPx = heightPx * RANK_BOX
    val glyphs = remember(text, heightPx) {
        // Android's text path gives the glyph outlines (Compose's getPathForRange gives the boxes
        // around characters, for selection).
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) ?: android.graphics.Typeface.DEFAULT_BOLD
            if (android.os.Build.VERSION.SDK_INT >= 26) fontVariationSettings = "'wght' 800"
            textSize = 200f
        }
        val outline = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, outline)
        val ink = android.graphics.RectF().also { outline.computeBounds(it, true) }
        val scale = (heightPx - strokePx) / ink.height()
        outline.transform(android.graphics.Matrix().apply { setTranslate(-ink.left, -ink.top); postScale(scale, scale) })
        outline.asComposePath() to (ink.width() * scale + strokePx)
    }
    val (path, glyphWidthPx) = glyphs
    // Narrow digits touch the poster; wide ones start near the left and run under its edge.
    val left = if (glyphWidthPx <= boxPx * 0.94f) boxPx - glyphWidthPx else boxPx * 0.06f
    val white = Color.White.copy(alpha = rankAlpha(rank))
    androidx.compose.foundation.Canvas(Modifier.size(with(density) { boxPx.toDp() }, height)) {
        clipRect {
            // Inset by half the stroke so the outline's outer edge stays inside the poster's height.
            translate(left + strokePx / 2, strokePx / 2) {
                drawPath(
                    path,
                    brush = Brush.horizontalGradient(0f to white, 1f to white.copy(alpha = 0f), startX = boxPx * RANK_FADE_FROM - left, endX = boxPx - left),
                    style = Stroke(width = strokePx, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
            }
        }
    }
}

/**
 * A catalogue row. Entering it from above or below focuses its first title (scrolling back to
 * the start if needed); leaving vertically resets the scroll so the next entry lands on it again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseRow(
    row: RowState,
    posterWidth: Dp,
    library: Map<String, LibraryItem>,
    newEpisodes: Map<String, Set<String>>,
    callbacks: PosterCallbacks,
    onEnsureLoaded: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSeeAll: () -> Unit,
    restoreIndex: Int?,
    onRestored: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val c = Vyrk.colors
    val scope = rememberCoroutineScope()
    val firstReq = remember { FocusRequester() }
    val restoreReq = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(row.key) { onEnsureLoaded() }

    LaunchedEffect(restoreIndex, row.items.size) {
        val idx = restoreIndex ?: return@LaunchedEffect
        if (row.items.isEmpty()) return@LaunchedEffect
        val target = idx.coerceAtMost(row.items.lastIndex)
        listState.scrollToItem((target - 1).coerceAtLeast(0))
        kotlinx.coroutines.delay(32)
        runCatching { restoreReq.requestFocus() }
        onRestored()
    }

    Column(Modifier.fillMaxWidth()) {
        val landscape = row.kind == RowKind.CONTINUE
        com.vyrk.tv.ui.components.SectionHeading(row.title, active = hasFocus, chevron = landscape, modifier = Modifier.padding(start = Dimens.screenMargin, bottom = 8.dp))
        // Continue Watching is landscape (16:9 backdrops); other rows are posters.
        val height = if (landscape) Dimens.landscapeCardWidth * 9f / 16f else posterWidth * 1.5f
        when {
            row.items.isEmpty() && row.error != null -> Row(Modifier.padding(start = Dimens.screenMargin).height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(R.string.row_error), style = Vyrk.type.bodyM, color = c.textMuted)
                VyrkButton(str(R.string.retry), onClick = onRetry, modifier = Modifier.traceFocus("row:${row.key}:retry"))
            }
            row.items.isEmpty() && row.loadedOnce && !row.loading -> Box(Modifier.padding(start = Dimens.screenMargin).height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text(str(R.string.row_empty), style = Vyrk.type.bodyM, color = c.textMuted)
            }
            row.items.isEmpty() -> Row(Modifier.padding(start = Dimens.screenMargin), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(7) { Box(Modifier.size(posterWidth, height).clip(Dimens.posterShape).background(c.raised.copy(alpha = 0.6f))) }
            }
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides ComfortZoneSpec) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = Dimens.screenMargin),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height + 12.dp)
                        .onFocusChanged { st ->
                            if (hasFocus && !st.hasFocus && restoreIndex == null) scope.launch { listState.scrollToItem(0) }
                            hasFocus = st.hasFocus
                        }
                        .focusProperties {
                            onEnter = {
                                if (restoreIndex == null && (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down)) {
                                    if (listState.firstVisibleItemIndex == 0) runCatching { firstReq.requestFocus() }
                                }
                            }
                        }
                        .focusGroup(),
                ) {
                    itemsIndexed(row.items, key = { _, m -> m.type + ":" + m.id }) { i, meta ->
                        if (i >= row.items.size - 6) LaunchedEffect(row.items.size) { onLoadMore() }
                        val req = when {
                            restoreIndex != null && i == restoreIndex.coerceAtMost(row.items.lastIndex) -> Modifier.focusRequester(restoreReq)
                            i == 0 -> Modifier.focusRequester(firstReq)
                            else -> Modifier
                        }
                        val top10 = row.kind == RowKind.TOP10
                        if (landscape) ContinueCard(
                            meta = meta,
                            item = library[meta.id],
                            width = Dimens.landscapeCardWidth,
                            modifier = req.onFocusChanged { if (it.isFocused) callbacks.onFocused(meta, i) },
                            onClick = { callbacks.onClick(meta, i) },
                            onLongClick = { callbacks.onLongClick(meta, i) },
                            onPlayPause = { callbacks.onPlayPause(meta, i) },
                            traceLabel = "poster:${row.key}#$i",
                        ) else Row(verticalAlignment = Alignment.Bottom) {
                            if (top10) RankNumber(i + 1, height)
                            Poster(
                                meta = meta,
                                width = posterWidth,
                                state = posterState(library[meta.id], newEpisodes[meta.id].orEmpty()),
                                modifier = req.onFocusChanged { if (it.isFocused) callbacks.onFocused(meta, i) },
                                onClick = { callbacks.onClick(meta, i) },
                                onLongClick = { callbacks.onLongClick(meta, i) },
                                onPlayPause = { callbacks.onPlayPause(meta, i) },
                                traceLabel = "poster:${row.key}#$i",
                            )
                        }
                    }
                    if (row.kind == RowKind.CATALOG && row.items.size >= 10) item {
                        Box(Modifier.size(posterWidth, height), contentAlignment = Alignment.Center) {
                            VyrkButton(str(R.string.see_all), onClick = onSeeAll, style = ButtonStyle.SECONDARY, modifier = Modifier.traceFocus("row:${row.key}:see_all"))
                        }
                    }
                }
            }
        }
    }
}

/** "S5 E4 · 28m left", "28m left" or "S1 E3"; null when the library knows neither. */
@Composable
private fun continueLabel(item: LibraryItem?): String? {
    val st = item?.state ?: return null
    val episode = st.videoId?.split(':')?.takeIf { it.size >= 3 }?.let { p ->
        val season = p[p.size - 2].toIntOrNull(); val ep = p.last().toIntOrNull()
        if (season != null && ep != null) "S$season E$ep" else null
    }
    val leftMin = if (st.duration > 0 && st.duration > st.timeOffset) ((st.duration - st.timeOffset) / 60_000L).toInt() else null
    val left = leftMin?.takeIf { it > 0 }?.let { m -> str(R.string.time_left, if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m") }
    return listOfNotNull(episode, left).joinToString(" · ").ifEmpty { null }
}

/**
 * Continue Watching card, landscape: the title's backdrop with its logo, a play button, a slim
 * accent progress bar and what's left (episode and time). Watched titles carry a tick. Same keys
 * and focus behaviour as a poster (Menu opens the item menu, Play/Pause plays).
 */
@Composable
fun ContinueCard(
    meta: MetaPreview,
    item: LibraryItem?,
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayPause: () -> Unit,
    traceLabel: String,
) {
    val c = Vyrk.colors
    val height = width * 9f / 16f
    val progress = if (item?.isInContinueWatching == true) item.progress else 0f
    val label = continueLabel(item)
    val desc = listOfNotNull(meta.name.takeIf { it.isNotBlank() }, label).joinToString(", ")
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .size(width, height)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = desc }
            .traceFocus(traceLabel)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Menu -> { onLongClick(); true }
                    Key.MediaPlayPause, Key.MediaPlay -> { onPlayPause(); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dimens.radiusM)),
        colors = ClickableSurfaceDefaults.colors(containerColor = c.raised, focusedContainerColor = c.raised),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.07f)),
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.accent.copy(alpha = 0.85f))),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The title's backdrop, then Cinemeta's, then its poster; never blank.
            val candidates = remember(meta.id, meta.background, meta.poster) {
                listOfNotNull(com.vyrk.core.art.Artwork.forBackdrop(meta.background, "w780"), com.vyrk.core.art.Artwork.metahubBackground(meta.id), meta.poster).distinct()
            }
            var attempt by remember(candidates) { mutableIntStateOf(0) }
            val url = candidates.getOrNull(attempt)
            if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { if (candidates.getOrNull(attempt) == url) attempt += 1 })
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.raised, c.panel))))
            // Dark from the left under the logo, and from the bottom under the controls.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.shade.copy(alpha = 0.78f), 0.62f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to c.shade.copy(alpha = if (focused) 0.75f else 0.88f))))
            Box(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 9.dp).width(width * 0.56f).height(height * 0.42f), contentAlignment = Alignment.BottomStart) {
                val logos = remember(meta.id, meta.logo) { listOfNotNull(com.vyrk.core.art.Artwork.forLogo(meta.logo), com.vyrk.core.art.Artwork.metahubLogo(meta.id)).distinct() }
                HeroLogo(meta.id, meta.name, logos, width * 0.56f, height * 0.42f, Vyrk.type.titleS, Modifier.fillMaxHeight())
            }
            if (item?.watched == true) {
                Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(17.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.ic_check, null, Modifier.size(10.dp), tint = c.onAccent)
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 9.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier.size(20.dp).background(if (focused) c.accent else Color.Black.copy(alpha = 0.35f), CircleShape)
                        .border(1.dp, if (focused) c.accent else Color.White.copy(alpha = 0.75f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(R.drawable.ic_play, null, Modifier.padding(start = 1.dp).size(9.dp), tint = if (focused) c.onAccent else Color.White) }
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                    if (progress > 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent))
                }
                if (label != null) Text(label, style = Vyrk.type.labelS, color = c.text, maxLines = 1, softWrap = false)
            }
        }
    }
}
