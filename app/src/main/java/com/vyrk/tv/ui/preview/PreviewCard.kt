package com.vyrk.tv.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaPreview
import com.vyrk.tv.R
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.browse.produceMeta
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk

/** 16:9 trailer card shown over the page. It never takes focus. */
@Composable
fun PreviewCard(meta: MetaPreview, sound: Boolean, modifier: Modifier = Modifier) {
    val session = LocalSession.current ?: return
    val full by produceMeta(session, meta)
    val yt = full?.youtubeTrailerId ?: meta.trailerStreams.firstNotNullOfOrNull { it.ytId }
    var state by remember(meta.id) { mutableStateOf(TrailerState.LOADING) }
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(modifier.size(420.dp, 290.dp).background(c.panel, RoundedCornerShape(18.dp)).padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(222.dp).clip(RoundedCornerShape(12.dp)).background(c.background)) {
                val art = full?.background ?: meta.background
                if (state != TrailerState.PLAYING && art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                when {
                    yt == null && full != null -> Text(str(R.string.no_trailer), style = Vyrk.type.bodyM, color = c.textSecondary, modifier = Modifier.align(Alignment.Center))
                    state == TrailerState.REFUSED -> Text(str(R.string.trailer_refused), style = Vyrk.type.bodyM, color = c.text, modifier = Modifier.align(Alignment.Center).padding(12.dp))
                    yt != null -> YouTubeTrailer(yt, muted = !sound, modifier = Modifier.fillMaxSize()) { state = it }
                }
            }
            Box(Modifier.height(10.dp))
            Text(meta.name, style = Vyrk.type.titleL, color = c.text, maxLines = 1)
            Text(str(R.string.header_hint), style = Vyrk.type.labelS, color = c.textMuted)
        }
    }
}
