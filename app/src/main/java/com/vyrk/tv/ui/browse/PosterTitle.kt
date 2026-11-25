package com.vyrk.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.transformations
import com.vyrk.core.addon.MetaPreview

/**
 * The title at the foot of a poster whose art doesn't carry it: anime key art from AniList, Kitsu
 * or MyAnimeList (see Artwork.isKeyArt), where Cinemeta's and TMDB's posters print the title.
 * The title's logo when one is known (the add-on's, else Cinemeta's by IMDb ID, from VYRK's anime
 * list when the add-on gives none), else its name, over a shade so either reads on bright art.
 */
@Composable
fun KeyArtTitle(meta: MetaPreview, posterWidth: Dp, textStyle: TextStyle, bottomInset: Dp = 0.dp) {
    val logos = cardLogos(meta)
    var failed by remember(meta.type, meta.id) { mutableStateOf(emptySet<String>()) }
    val url = logos.firstOrNull { it !in failed }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.85f))))
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp + bottomInset)
                .width(posterWidth * 0.86f).height(posterWidth * 0.36f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (url != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val request = remember(url) {
                    // Logos come with transparent margins: decoded at twice the slot, then trimmed.
                    coil3.request.ImageRequest.Builder(context).data(url).size(640, 320)
                        .transformations(com.vyrk.tv.ui.components.TrimTransparent()).build()
                }
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter,
                    onError = { failed = failed + url }, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    meta.name, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = textStyle.copy(shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1.5f), blurRadius = 6f)),
                )
            }
        }
    }
}
