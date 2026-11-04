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
import androidx.compose.runtime.Composable
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

/** Backdrop with a left and bottom fade into the page background. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    val bg = Vyrk.colors.background
    Box(modifier) {
        Crossfade(url, label = "backdrop") { u ->
            if (u != null) AsyncImage(u, null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.45f to bg.copy(alpha = 0.75f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to bg)))
    }
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

/** Year · type · runtime · IMDb rating, then genre chips. */
@Composable
fun MetaFacts(meta: MetaPreview, full: MetaItem?) {
    val c = Vyrk.colors
    val parts = listOfNotNull(
        (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() },
        when (meta.type) { "movie" -> str(R.string.type_movie); "series" -> str(R.string.type_series); "tv" -> str(R.string.type_live); else -> meta.type.replaceFirstChar { it.uppercase() } },
        (full?.runtime ?: meta.runtime)?.takeIf { it.isNotBlank() },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(parts.joinToString("  ·  "), style = Vyrk.type.labelL, color = c.textSecondary)
        val rating = full?.imdbRating ?: meta.imdbRating
        if (!rating.isNullOrBlank()) Tag("IMDb $rating", color = c.background, background = c.warning)
    }
    val genres = (full?.allGenres ?: meta.genres).take(4)
    if (genres.isNotEmpty()) {
        Box(Modifier.height(8.dp))
        WrapRow(spacing = 6.dp) { genres.forEach { Tag(it) } }
    }
}

@Composable
fun CreditLines(full: MetaItem?, meta: MetaPreview) {
    val cast = (full?.castNames ?: meta.cast).take(5)
    val director = (full?.directorNames ?: meta.director).take(2)
    if (cast.isNotEmpty()) Text(str(R.string.starring, cast.joinToString(", ")), style = Vyrk.type.bodyS, color = Vyrk.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (director.isNotEmpty()) Text(str(R.string.director_line, director.joinToString(", ")), style = Vyrk.type.bodyS, color = Vyrk.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Hero(
    items: List<MetaPreview>,
    index: Int,
    full: MetaItem?,
    height: Dp,
    hasProgress: Boolean,
    watchReq: FocusRequester,
    onWatch: () -> Unit,
    onInfo: () -> Unit,
    onTrailer: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val meta = items.getOrNull(index) ?: return
    val c = Vyrk.colors
    Box(Modifier.fillMaxWidth().height(height).onFocusChanged { onFocusChanged(it.hasFocus) }) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = Dimens.screenMargin, bottom = 20.dp).width(520.dp)) {
            TitleLogo(meta, 480.dp, 140.dp)
            Box(Modifier.height(12.dp))
            MetaFacts(meta, full)
            Box(Modifier.height(10.dp))
            Text(full?.description ?: meta.description ?: "", style = Vyrk.type.bodyM, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.height(6.dp))
            CreditLines(full, meta)
            Box(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VyrkButton(
                    if (hasProgress) str(R.string.resume) else str(R.string.watch_now),
                    onClick = onWatch, onLongClick = onInfo, icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                    modifier = Modifier.focusRequester(watchReq).traceFocus("hero:watch")
                        .onPreviewKeyEvent { e ->
                            when {
                                e.type == KeyEventType.KeyUp && e.key == Key.Menu -> { onInfo(); true }
                                // Left past the first button shows the previous featured title.
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && items.size > 1 -> { onPrev(); true }
                                else -> false
                            }
                        },
                )
                VyrkButton(str(R.string.trailer), onClick = onTrailer, icon = R.drawable.ic_trailer, modifier = Modifier.traceFocus("hero:trailer")
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && items.size > 1) { onNext(); true } else false })
                if (items.size > 1) {
                    // Position dots only; Left on Watch and Right on Trailer move between titles.
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
fun BoxScope.TitleHeader(meta: MetaPreview, full: MetaItem?, height: Dp, trailerPlaying: Boolean) {
    val c = Vyrk.colors
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(height)) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = Dimens.screenMargin, bottom = 14.dp).width(470.dp)) {
            // Logo: large, so short or square logos still read from the sofa.
            TitleLogo(meta, 440.dp, 130.dp)
            Box(Modifier.height(10.dp))
            MetaFacts(meta, full)
            Box(Modifier.height(8.dp))
            Text(full?.description ?: meta.description ?: "", style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.height(4.dp))
            CreditLines(full, meta)
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
