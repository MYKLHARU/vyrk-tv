package com.vyrk.tv.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        val fallback: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Text(meta.name, style = Vyrk.type.titleS, color = c.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        // AsyncImage rather than SubcomposeAsyncImage: no subcomposition per poster while rows scroll.
        var failed by remember(meta.poster) { mutableStateOf(false) }
        if (meta.poster != null && !failed) {
            AsyncImage(meta.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true })
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
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
                Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).height(4.dp).background(c.accent))
            }
        }
    }
}

/** Large outlined rank number for Top 10 rows. */
@Composable
fun RankNumber(rank: Int, height: Dp) {
    val measurer = rememberTextMeasurer()
    val accent = Vyrk.colors.accent
    val style = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = (height.value * 0.92f).sp)
    androidx.compose.foundation.Canvas(Modifier.size(height * (if (rank >= 10) 0.95f else 0.55f), height)) {
        val layout = measurer.measure(rank.toString(), style)
        val x = size.width - layout.size.width
        val y = size.height - layout.size.height * 0.92f
        drawText(layout, color = Color(0xFF0B141D), topLeft = androidx.compose.ui.geometry.Offset(x, y))
        drawText(layout, brush = Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.5f))), topLeft = androidx.compose.ui.geometry.Offset(x, y),
            drawStyle = Stroke(width = 2.5.dp.toPx()))
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
        Row(Modifier.padding(start = Dimens.screenMargin, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = Vyrk.type.titleL, color = if (hasFocus) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val height = posterWidth * 1.5f
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
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (row.kind == RowKind.TOP10) RankNumber(i + 1, height * 0.62f)
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
