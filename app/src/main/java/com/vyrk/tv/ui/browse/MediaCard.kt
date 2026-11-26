package com.vyrk.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.CardFacts
import com.vyrk.tv.R
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.components.TrimTransparent
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk

/*
 * The parts every media card shares, on TV (Poster, its expanded face) and phone (PhonePoster):
 * artwork that keeps faces in frame, the title (logo, else name) in a fixed slot, a soft shade at
 * the foot behind a row of facts (year, IMDb out of 10, Rotten Tomatoes' Tomatometer), and the
 * region's age certificate in the top corner. The card's own border, glow, size and shape stay
 * where they are, in Poster and PhonePoster.
 */

/** Text sizes and spacing for a card: TV is read from the sofa, phone from arm's length. */
class CardScale(
    val value: TextStyle,
    val suffix: TextStyle,
    val label: TextStyle,
    val badge: TextStyle,
    val title: TextStyle,
    val pad: Dp,
    val gap: Dp,
) {
    companion object {
        val Tv: CardScale @Composable get() = CardScale(Vyrk.type.labelL, Vyrk.type.labelM, Vyrk.type.labelM, Vyrk.type.labelM, Vyrk.type.titleS, 8.dp, 6.dp)
        val TvWide: CardScale @Composable get() = CardScale(Vyrk.type.labelL, Vyrk.type.labelM, Vyrk.type.labelM, Vyrk.type.labelM, Vyrk.type.titleL, 12.dp, 8.dp)
        val Phone: CardScale @Composable get() = CardScale(Vyrk.type.labelM, Vyrk.type.labelS, Vyrk.type.labelS, Vyrk.type.labelS, Vyrk.type.labelL, 6.dp, 5.dp)
    }
}

/** A title's name on a card: long words are hyphenated rather than split at any letter. */
fun TextStyle.forCardName(): TextStyle =
    copy(hyphens = androidx.compose.ui.text.style.Hyphens.Auto, lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)

/** Rotten Tomatoes' red, for its label only. */
private val Tomato = Color(0xFFFA320A)

/**
 * A card's facts: the year from the catalogue; IMDb's score from the catalogue when the title is
 * keyed by IMDb ID, else from Cinemeta; the age certificate for the device's region from TMDB.
 * Remote facts are asked for once the card has stayed on screen a moment, and kept.
 */
@Composable
fun rememberCardFacts(meta: MetaPreview, full: MetaItem? = null): CardFacts {
    val source = LocalGraph.current.cardFacts
    val session = LocalSession.current
    val trusted = CardFacts.addonRatingIsImdb(meta.id)
    val own = CardFacts.imdb(full?.takeIf { trusted && it.id == meta.id }?.imdbRating) ?: CardFacts.imdb(meta.imdbRating.takeIf { trusted })
    val needImdb = own == null
    var remote by remember(meta.type, meta.id, needImdb) { mutableStateOf(source.peek(meta, needImdb)) }
    LaunchedEffect(meta.type, meta.id, needImdb) {
        if (remote != null) return@LaunchedEffect
        kotlinx.coroutines.delay(350) // posters scrolled straight past don't ask
        remote = source.load(meta, session?.effectiveAddons().orEmpty(), needImdb)
    }
    return CardFacts(
        year = CardFacts.year((full?.takeIf { it.id == meta.id }?.releaseInfo) ?: meta.releaseInfo),
        imdb = own ?: remote?.imdb,
        rottenTomatoes = CardFacts.tomatometer(remote?.rottenTomatoes),
        certificate = remote?.certificate,
    )
}

/** The card's facts read aloud with its name, for TalkBack. */
@Composable
fun spokenFacts(facts: CardFacts): String = CardFacts.spoken(facts, str(R.string.facts_out_of_10), str(R.string.facts_rated))

/**
 * Artwork that keeps the subject in frame. A picture close to the card's shape fills it, cropped
 * from a little above centre, where faces usually are (or at [focus], a per-title focal point,
 * when a source gives one: none does yet). A picture of the other orientation (a landscape image
 * on a portrait card, a portrait one on a landscape card) is shown whole, over a blurred, dimmed
 * copy of itself, instead of being cropped through. [knownAspect], when the picture's shape is
 * known before it loads (a poster on a landscape card), avoids a first frame cropped the other way.
 */
@Composable
fun CardArtwork(url: String, cardAspect: Float, onError: () -> Unit, focus: Alignment? = null, knownAspect: Float = 0f) {
    var aspect by remember(url) { mutableFloatStateOf(knownAspect) }
    val portraitCard = cardAspect < 1f
    val mismatch = aspect > 0f && (if (portraitCard) aspect > 0.9f else aspect < 1.1f)
    if (mismatch) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { alpha = 0.5f })
    }
    AsyncImage(
        url, contentDescription = null,
        contentScale = if (mismatch) ContentScale.Fit else ContentScale.Crop,
        alignment = when {
            mismatch -> if (portraitCard) Alignment.Center else Alignment.CenterEnd
            else -> focus ?: SubjectBias
        },
        modifier = Modifier.fillMaxSize(),
        onSuccess = { s -> val img = s.result.image; if (img.width > 0 && img.height > 0) aspect = img.width.toFloat() / img.height },
        onError = { onError() },
    )
}

/** Crops keep a little more of the top than the bottom: heads, not feet. */
private val SubjectBias = BiasAlignment(0f, -0.35f)

/**
 * The title: its logo in a fixed slot ([slotWidth] × [slotHeight]), trimmed of transparent margins
 * and fitted, so logos line up row to row; else its name, as wide as the slot and up to
 * [maxLines] lines tall (never squeezed into the logo's height, where it would overrun the facts).
 */
@Composable
fun CardTitle(
    meta: MetaPreview, logos: List<String>, slotWidth: Dp, slotHeight: Dp, textStyle: TextStyle,
    alignment: Alignment, textAlign: TextAlign, maxLines: Int = 2, decodeWidth: Int = 320,
) {
    var failed by remember(meta.type, meta.id) { mutableStateOf(emptySet<String>()) }
    val url = logos.firstOrNull { it !in failed }
    Box(if (url != null) Modifier.width(slotWidth).height(slotHeight) else Modifier.width(slotWidth), contentAlignment = alignment) {
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url, decodeWidth) {
                ImageRequest.Builder(context).data(url).size(decodeWidth, decodeWidth / 2).transformations(TrimTransparent()).build()
            }
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Fit, alignment = alignment,
                onError = { failed = failed + url }, modifier = Modifier.fillMaxSize())
        } else {
            Text(meta.name.ifBlank { str(if (meta.type == "series") R.string.type_series else R.string.type_movie) },
                style = textStyle.forCardName(), color = Color.White, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
        }
    }
}

/** Year, IMDb out of 10 and the Tomatometer, each only when known; wraps instead of overlapping on narrow cards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardFactsRow(facts: CardFacts, scale: CardScale, centered: Boolean, modifier: Modifier = Modifier) {
    if (!facts.hasRow) return
    val c = Vyrk.colors
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(scale.gap, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        facts.year?.let { Text(it, style = scale.value, color = Color.White.copy(alpha = 0.92f), maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically)) }
        facts.imdb?.let { score ->
            Rated("IMDb", c.gold, c.background, CardFacts.formatImdb(score), "/10", scale)
        }
        facts.rottenTomatoes?.let { pct ->
            Rated("RT", Tomato, Color.White, CardFacts.formatPercent(pct), null, scale)
        }
    }
}

/** A score with its source's label in front ("IMDb 8.2/10", "RT 92%"). */
@Composable
private fun Rated(source: String, labelColor: Color, labelText: Color, value: String, suffix: String?, scale: CardScale) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(labelColor, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp)) {
            Text(source, style = scale.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = labelText, maxLines = 1)
        }
        Text(value, style = scale.value, color = Color.White, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        if (suffix != null) Text(suffix, style = scale.suffix, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
    }
}

/** The region's age certificate, compact, for a card's top corner. */
@Composable
fun CertificateBadge(certificate: String, scale: CardScale) {
    Box(
        Modifier.background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) { Text(certificate, style = scale.badge, color = Color.White, maxLines = 1) }
}

/**
 * A portrait card's foot: a soft shade, the title when the artwork doesn't print it ([logos] or
 * the name, for anime key art), then the facts row, all above [bottomInset] (the progress bar).
 */
@Composable
fun BoxScope.PortraitFoot(meta: MetaPreview, facts: CardFacts, showTitle: Boolean, logos: List<String>, width: Dp, scale: CardScale, bottomInset: Dp) {
    if (!showTitle && !facts.hasRow) return
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (showTitle) 0.5f else 0.32f)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.4f), 1f to Color.Black.copy(alpha = 0.8f))),
    )
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = scale.pad, end = scale.pad, bottom = scale.pad + bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTitle) {
            CardTitle(meta, logos, width * 0.84f, width * 0.3f, scale.title, Alignment.BottomCenter, TextAlign.Center)
            if (facts.hasRow) Box(Modifier.height(5.dp))
        }
        CardFactsRow(facts, scale, centered = true, modifier = Modifier.fillMaxWidth())
    }
}

/** The top-right corner: the watched tick (if any) beside the age certificate (if known). */
@Composable
fun BoxScope.CardCorner(watched: Boolean, certificate: String?, scale: CardScale, tickSize: Dp) {
    if (!watched && certificate == null) return
    val c = Vyrk.colors
    Row(
        Modifier.align(Alignment.TopEnd).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (watched) Box(Modifier.width(tickSize).height(tickSize).background(c.success, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            com.vyrk.tv.ui.components.Icon(R.drawable.ic_check, null, Modifier.width(tickSize * 0.6f).height(tickSize * 0.6f), tint = c.background)
        }
        if (certificate != null) CertificateBadge(certificate, scale)
    }
}
