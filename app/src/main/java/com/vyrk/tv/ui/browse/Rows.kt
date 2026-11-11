package com.vyrk.tv.ui.browse

import com.vyrk.tv.ui.components.focusHalo
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
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
        modifier = modifier.focusHalo()
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
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
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

/**
 * Top 10 numbers 1–10 from the user's artwork (numbers-1-to-10 SVGs, 85 × 148 viewBox): each is
 * an outline, white on the left and fading to clear towards the poster, fainter down the ranks.
 * Digits 1–9 are font outlines in glyph units, placed by translate + scale (y flipped); 10 is
 * drawn in viewBox units, its 0 overlapping the 1 and cut off at the right edge.
 */
private class RankGlyph(val d: String, val tx: Float, val ty: Float, val scale: Float, val opacity: Float)

private val RANK_GLYPHS = mapOf(
    1 to RankGlyph("M836 1456V0H499V1076L167 979V1235L805 1456Z", 13.2541f, 147.0000f, 0.1002747f, 0.50f),
    2 to RankGlyph("M1106 260V0H90V220L558 712Q731 911 731 1025Q731 1217 571 1217Q492 1217 445.0 1151.5Q398 1086 398 988H60Q60 1189 203.5 1333.0Q347 1477 579 1477Q820 1477 944.5 1365.5Q1069 1254 1069 1055Q1069 903 976.5 771.0Q884 639 720 477L532 260Z", 2.0691f, 147.0000f, 0.0988490f, 0.45f),
    3 to RankGlyph("M397 618V869H556Q745 869 745 1054Q745 1119 703.5 1168.0Q662 1217 573 1217Q512 1217 460.0 1181.0Q408 1145 408 1081H70Q70 1266 218.0 1371.5Q366 1477 562 1477Q794 1477 938.0 1371.0Q1082 1265 1082 1061Q1082 962 1021.0 879.0Q960 796 858 748Q973 706 1039.5 620.5Q1106 535 1106 409Q1106 204 950.0 92.0Q794 -20 562 -20Q440 -20 322.0 24.0Q204 68 126.0 162.0Q48 256 48 405H386Q386 335 441.5 287.5Q497 240 573 240Q666 240 717.0 291.0Q768 342 768 416Q768 526 713.0 572.0Q658 618 561 618Z", 1.3186f, 145.0494f, 0.0975284f, 0.40f),
    4 to RankGlyph("M59 515 643 1454V1456H980V569H1127V309H980V0H643V309H79ZM381 569H643V1019L621 983Z", -1.9162f, 147.0000f, 0.1002747f, 0.35f),
    5 to RankGlyph("M377 649 109 712 197 1456H1048V1194H472L439 905Q463 919 520.5 938.5Q578 958 648 958Q864 958 981.0 830.5Q1098 703 1098 469Q1098 344 1043.5 232.0Q989 120 877.0 50.0Q765 -20 592 -20Q463 -20 345.0 32.5Q227 85 153.0 182.5Q79 280 80 414H419Q423 334 469.5 287.0Q516 240 590 240Q691 240 725.5 315.0Q760 390 760 492Q760 599 711.5 662.0Q663 725 553 725Q479 725 437.5 699.5Q396 674 377 649Z", 0.0880f, 145.0217f, 0.0989160f, 0.30f),
    6 to RankGlyph("M1129 482Q1129 268 986.5 124.0Q844 -20 620 -20Q392 -20 239.5 132.0Q87 284 84 555V689Q84 938 190.0 1114.0Q296 1290 470.5 1383.5Q645 1477 849 1477H903V1212H885Q713 1212 586.5 1127.5Q460 1043 429 871Q542 982 717 982Q916 982 1022.5 838.0Q1129 694 1129 482ZM421 516Q421 240 611 240Q690 240 740.5 309.0Q791 378 791 482Q791 594 737.5 659.0Q684 724 599 724Q533 724 488.0 693.5Q443 663 421 619Z", -4.1924f, 145.0494f, 0.0975284f, 0.30f),
    7 to RankGlyph("M1102 1456V1276L558 0H202L747 1196H52V1456Z", 3.7857f, 147.0000f, 0.1002747f, 0.30f),
    8 to RankGlyph("M1101 401Q1101 192 957.5 86.0Q814 -20 595 -20Q377 -20 231.0 86.0Q85 192 85 401Q85 522 147.0 611.5Q209 701 314 751Q222 800 169.0 881.0Q116 962 116 1067Q116 1267 250.5 1372.0Q385 1477 593 1477Q803 1477 937.0 1372.0Q1071 1267 1071 1067Q1071 962 1018.5 880.5Q966 799 875 750Q979 700 1040.0 611.0Q1101 522 1101 401ZM734 1049Q734 1217 593 1217Q454 1217 454 1049Q454 971 489.5 923.0Q525 875 595.0 875.0Q665 875 699.5 923.0Q734 971 734 1049ZM762 428Q762 517 716.0 566.0Q670 615 593 615Q518 615 470.5 566.0Q423 517 423 428Q423 340 470.5 290.0Q518 240 595 240Q671 240 716.5 290.0Q762 340 762 428Z", -3.2899f, 145.0494f, 0.0975284f, 0.30f),
    9 to RankGlyph("M1096 765Q1096 506 994.0 331.5Q892 157 718.0 68.5Q544 -20 329 -20H303V250H323Q503 250 617.0 322.5Q731 395 753 563Q644 465 509 465Q310 465 195.0 600.0Q80 735 80 957Q80 1175 225.0 1326.0Q370 1477 587 1477Q796 1477 946.0 1322.5Q1096 1168 1096 881ZM418 957Q418 851 463.5 779.5Q509 708 601 708Q710 708 758 802V943Q758 1083 711.0 1150.0Q664 1217 584.0 1217.0Q504 1217 461.0 1134.5Q418 1052 418 957Z", -2.8023f, 145.0494f, 0.0975284f, 0.30f),
)

/** 10 in viewBox units: the 1 stops where the 0 passes in front of it. */
private const val RANK_TEN = "M10 27 L56 16 L56 36.45 M56 117.55 L56 139 L25 139 L25 51 L10 56 L10 27"
private const val RANK_VIEW_W = 85f
private const val RANK_VIEW_H = 148f

private val rankPaths = HashMap<Int, androidx.compose.ui.graphics.Path>()
private fun rankPath(rank: Int): androidx.compose.ui.graphics.Path = rankPaths.getOrPut(rank) {
    androidx.compose.ui.graphics.vector.PathParser().parsePathString(RANK_GLYPHS[rank]?.d ?: RANK_TEN).toPath()
}

/**
 * Top 10 rank: the user's number artwork in antique gold, in a box 85 : 148 of [height]; a wide
 * digit runs to the box's edge and is cut off there. The row sets it behind the poster's corner.
 */
@Composable
fun RankNumber(rank: Int, height: Dp) {
    val glyph = RANK_GLYPHS[rank]
    val path = remember(rank) { rankPath(rank) }
    val gold = Vyrk.colors.gold
    // The fade to clear on the right, as in the artwork (10 has its own stops).
    val fade = if (glyph != null) arrayOf(0f to Color.White, 0.53f to Color.White, 0.69f to Color.White.copy(alpha = 0.69f), 0.86f to Color.White.copy(alpha = 0.2f), 1f to Color.Transparent)
        else arrayOf(0f to Color.White, 0.50f to Color.White, 0.68f to Color.White.copy(alpha = 0.78f), 0.86f to Color.White.copy(alpha = 0.2f), 1f to Color.Transparent)
    androidx.compose.foundation.Canvas(
        Modifier.size(height * (RANK_VIEW_W / RANK_VIEW_H), height)
            .graphicsLayer { alpha = 0.92f; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        val k = size.height / RANK_VIEW_H
        clipRect {
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                if (glyph != null) {
                    translate(glyph.tx, glyph.ty) {
                        scale(glyph.scale, -glyph.scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                            drawPath(path, Color(0xFFF9FAFC), style = Stroke(width = 23f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                    }
                } else {
                    val ten = Stroke(width = 1.65f, join = androidx.compose.ui.graphics.StrokeJoin.Miter)
                    val ink = Color(0xFFE9ECEF)
                    drawPath(path, ink, style = ten)
                    drawOval(ink, topLeft = androidx.compose.ui.geometry.Offset(90f - 45f, 77f - 62f), size = androidx.compose.ui.geometry.Size(90f, 124f), style = ten)
                    drawOval(ink, topLeft = androidx.compose.ui.geometry.Offset(95f - 19f, 77f - 46f), size = androidx.compose.ui.geometry.Size(38f, 92f), style = ten)
                }
            }
            // Antique gold, bright at the top and dimming downwards, like light on carved metal.
            drawRect(Brush.verticalGradient(0f to Color(0xFFF3DEB0), 0.45f to gold, 1f to gold.copy(alpha = 0.35f), startY = 0f, endY = size.height), blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
            drawRect(Brush.horizontalGradient(*fade, startX = 0f, endX = size.width), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
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
        // Wait for placement, not a fixed wall-clock delay. Slow TV renderers can need
        // longer than 32 ms to re-place a kept page beneath the browse controls.
        androidx.compose.runtime.withFrameNanos { }
        repeat(24) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { restoreReq.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                onRestored()
                return@LaunchedEffect
            }
        }
        onRestored()
    }

    Column(Modifier.fillMaxWidth()) {
        val landscape = row.kind == RowKind.CONTINUE
        com.vyrk.tv.ui.components.SectionHeading(row.title, active = hasFocus, chevron = landscape, rule = true, modifier = Modifier.padding(start = Dimens.screenMargin, end = Dimens.screenMargin, bottom = 10.dp))
        // Continue Watching is landscape (16:9 backdrops); other rows are posters.
        val height = if (landscape) Dimens.landscapeCardHeight else posterWidth * 1.5f
        when {
            row.items.isEmpty() && row.error != null -> Row(Modifier.padding(start = Dimens.screenMargin).height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(R.string.row_error), style = Vyrk.type.bodyM, color = c.textMuted)
                VyrkButton(str(R.string.retry), onClick = onRetry, modifier = Modifier.traceFocus("row:${row.key}:retry"))
            }
            row.items.isEmpty() && row.loadedOnce && !row.loading -> Row(Modifier.padding(start = Dimens.screenMargin).height(48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(if (row.animeOnly) R.string.anime_no_matches else R.string.row_empty), style = Vyrk.type.bodyM, color = c.textMuted)
                if (row.hasMore) VyrkButton(str(R.string.anime_load_more), onClick = onLoadMore,
                    modifier = Modifier.traceFocus("row:${row.key}:more"))
            }
            row.items.isEmpty() -> Row(Modifier.padding(start = Dimens.screenMargin), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(7) { Box(Modifier.size(posterWidth, height).clip(Dimens.posterShape).background(c.raised.copy(alpha = 0.6f))) }
            }
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides ComfortZoneSpec) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = Dimens.screenMargin),
                    horizontalArrangement = Arrangement.spacedBy(if (landscape) 18.dp else 14.dp),
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
                        ) else Box(contentAlignment = Alignment.BottomStart) {
                            // Top 10: a rank a little over half the poster's height, tucked behind its
                            // lower-left corner, so the number frames the artwork instead of outweighing it.
                            val rankH = height * 0.60f
                            val rankW = rankH * (RANK_VIEW_W / RANK_VIEW_H)
                            if (top10) RankNumber(i + 1, rankH)
                            Poster(
                                meta = meta,
                                width = posterWidth,
                                state = posterState(library[meta.id], newEpisodes[meta.id].orEmpty()),
                                modifier = (if (top10) Modifier.padding(start = rankW * 0.86f) else Modifier)
                                    .then(req).onFocusChanged { if (it.isFocused) callbacks.onFocused(meta, i) },
                                onClick = { callbacks.onClick(meta, i) },
                                onLongClick = { callbacks.onLongClick(meta, i) },
                                onPlayPause = { callbacks.onPlayPause(meta, i) },
                                traceLabel = "poster:${row.key}#$i",
                            )
                        }
                    }
                    if (row.kind == RowKind.CATALOG && (row.items.size >= 10 ||
                        row.animeOnly && row.ref?.catalog?.supportsExtra("skip") == true)) item(key = "catalog-action") {
                        Box(Modifier.size(posterWidth, height), contentAlignment = Alignment.Center) {
                            val more = row.animeOnly && row.hasMore
                            VyrkButton(str(if (more) R.string.anime_load_more else R.string.see_all),
                                onClick = { if (row.animeOnly && row.hasMore) onLoadMore() else onSeeAll() },
                                style = ButtonStyle.SECONDARY,
                                modifier = Modifier.traceFocus("row:${row.key}:${if (more) "more" else "see_all"}"))
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
 * accent progress bar and what's left (episode and time). Same keys
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
    val height = Dimens.landscapeCardHeight
    val progress = if (item?.isInContinueWatching == true) item.progress else 0f
    val label = continueLabel(item)
    val desc = listOfNotNull(meta.name.takeIf { it.isNotBlank() }, label).joinToString(", ")
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.focusHalo()
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
        shape = ClickableSurfaceDefaults.shape(Dimens.posterShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = c.raised, focusedContainerColor = c.raised),
        // Restrained: a slight lift, a cyan edge and a soft halo; neighbours never get covered.
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.04f)),
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.accent.copy(alpha = 0.9f))),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
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
            // Dark from the left under the logo, and from the bottom under the episode line.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.shade.copy(alpha = 0.72f), 0.58f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.38f to Color.Transparent, 1f to c.shade.copy(alpha = if (focused) 0.82f else 0.92f))))
            Box(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 11.dp).width(width * 0.5f).height(height * 0.40f), contentAlignment = Alignment.BottomStart) {
                val logos = remember(meta.type, meta.id, meta.logo, meta.imdbId) { com.vyrk.core.art.Artwork.logos(meta, null, null) }
                HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), meta.name, logos, width * 0.5f, height * 0.40f, Vyrk.type.titleM, Modifier.fillMaxHeight())
            }
            // No watched tick here: "watched" means finished at least once before, which a card in
            // this row is not, now; the card shows where you are instead.
            Box(
                Modifier.align(Alignment.TopEnd).padding(9.dp).size(24.dp)
                    .background(if (focused) c.accent else c.shade.copy(alpha = 0.45f), CircleShape)
                    .border(1.dp, if (focused) c.accent else Color.White.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(R.drawable.ic_play, null, Modifier.padding(start = 1.dp).size(10.dp), tint = if (focused) c.onAccent else Color.White.copy(alpha = 0.9f)) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                if (label != null) Text(label, style = Vyrk.type.labelL, color = c.text, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
                    if (progress > 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent))
                }
            }
        }
    }
}
