package com.vyrk.tv.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.tmdb.Credit
import com.vyrk.core.tmdb.Person
import com.vyrk.core.tmdb.PersonCredit
import com.vyrk.core.tmdb.TmdbClient
import com.vyrk.tv.R
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.components.focusGlow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

/** Round photo with the person's initials when TMDB has no picture. */
@Composable
fun PersonPhoto(name: String, path: String?, size: Dp) {
    val c = Vyrk.colors
    Box(Modifier.size(size).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
        Text(name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = Vyrk.type.titleM, color = c.textSecondary)
        TmdbClient.image(path)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * "Cast & crew" on the info page: the cast as portrait cards (photo, name, character), then the key
 * crew as smaller chips with their role in gold. OK on either opens the person page.
 */
@Composable
fun CastCrewRow(cast: List<Credit>, crew: List<Credit>) {
    val app = LocalApp.current
    val castPeople = cast.take(15).distinctBy { it.personId }
    val crewPeople = crew.take(4).distinctBy { it.personId to it.role }
    if (castPeople.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp)) {
        itemsIndexed(castPeople, key = { i, p -> "${p.personId}-$i" }) { i, p ->
            CastCard(p, "info:person#$i") { app.push(Overlay.Person(p.personId, p.name)) }
        }
    }
    if (crewPeople.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp)) {
        itemsIndexed(crewPeople, key = { i, p -> "crew-${p.personId}-$i" }) { i, p ->
            CrewChip(p, "info:crew#$i") { app.push(Overlay.Person(p.personId, p.name)) }
        }
    }
}

/** A cast member as a portrait card; initials in gold on dark steel when TMDB has no photo. */
@Composable
private fun CastCard(p: Credit, trace: String, onClick: () -> Unit) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.width(92.dp).traceFocus(trace).semantics { contentDescription = listOfNotNull(p.name, p.role).joinToString(", ") },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, focusedContainerColor = c.raised.copy(alpha = 0.9f)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.05f)),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.accent))),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(c.accent.copy(alpha = 0.32f), 10.dp)),
    ) {
        Column(Modifier.padding(5.dp)) {
            Box(
                Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(9.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.steel, c.panel))),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = Vyrk.type.cinematicM, color = c.gold.copy(alpha = 0.6f))
                TmdbClient.image(p.profilePath)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.height(6.dp))
            Text(p.name, style = Vyrk.type.labelL, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.role?.let { Text(it, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A key crew member: role in gold capitals, then the name. */
@Composable
private fun CrewChip(p: Credit, trace: String, onClick: () -> Unit) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.height(30.dp).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(15.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = c.shade.copy(alpha = 0.45f), contentColor = c.text, focusedContainerColor = c.text, focusedContentColor = c.background),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(border = Border(androidx.compose.foundation.BorderStroke(1.dp, c.text.copy(alpha = 0.14f))), focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.role?.let { Text(it.uppercase(), style = Vyrk.type.labelS.copy(letterSpacing = Vyrk.type.eyebrow.letterSpacing * 0.5f), color = c.gold, maxLines = 1) }
            Text(p.name, style = Vyrk.type.labelL, maxLines = 1)
        }
    }
}

@Composable
fun PersonPage(o: Overlay.Person) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf<Person?>(null) }
    var credits by remember { mutableStateOf<List<PersonCredit>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var dept by remember { mutableStateOf("all") }
    var bioOpen by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val cantOpen = str(R.string.cant_open_title)
    LaunchedEffect(o.id) {
        person = graph.tmdb.person(o.id)
        credits = graph.tmdb.personCredits(o.id)
        failed = person == null
    }
    LaunchedEffect(credits != null) { if (credits != null) runCatching { first.requestFocus() } }
    val c = Vyrk.colors
    Row(Modifier.fillMaxSize().background(c.background).padding(horizontal = 48.dp, vertical = 30.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(300.dp)) {
            PersonPhoto(o.name, person?.profilePath, 140.dp)
            Spacer(Modifier.height(14.dp))
            Text(person?.name ?: o.name, style = Vyrk.type.headlineL, color = c.text)
            person?.knownFor?.let { Text(str(R.string.known_for, it), style = Vyrk.type.labelL, color = c.accent) }
            person?.birthday?.let { b -> Text(str(R.string.born_on, listOfNotNull(b, person?.placeOfBirth).joinToString(" · ")), style = Vyrk.type.bodyS, color = c.textSecondary) }
            person?.deathday?.let { Text(str(R.string.died_on, it), style = Vyrk.type.bodyS, color = c.textSecondary) }
            Spacer(Modifier.height(10.dp))
            when {
                failed -> Text(str(R.string.person_unavailable), style = Vyrk.type.bodyM, color = c.textMuted)
                person == null -> Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textMuted)
                else -> person?.biography?.let { bio ->
                    Text(bio, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = if (bioOpen) 30 else 8, overflow = TextOverflow.Ellipsis)
                    if (bio.length > 400) VyrkButton(str(if (bioOpen) R.string.less else R.string.more), onClick = { bioOpen = !bioOpen }, modifier = Modifier.padding(top = 8.dp).traceFocus("person:bio"))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(TmdbClient.ATTRIBUTION, style = Vyrk.type.labelS, color = c.textMuted)
        }
        Column(Modifier.weight(1f)) {
            val list = credits.orEmpty()
            val depts = list.map { it.department }.distinct().sortedBy { if (it == "Acting") 0 else 1 }
            WrapRow(spacing = 6.dp) {
                VyrkChip(str(R.string.filter_all), dept == "all", { dept = "all" }, Modifier.traceFocus("person:filter_all"))
                depts.take(5).forEach { d -> VyrkChip(d, dept == d, { dept = d }, Modifier.traceFocus("person:filter:$d")) }
            }
            Spacer(Modifier.height(14.dp))
            if (credits == null) Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textMuted)
            val shown = list.filter { dept == "all" || it.department == dept }.take(60)
            LazyVerticalGrid(GridCells.Adaptive(118.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                itemsIndexed(shown, key = { _, it -> it.type + it.tmdbId }) { i, pc ->
                    Surface(
                        onClick = {
                            scope.launch {
                                val imdb = graph.tmdb.imdbId(pc.tmdbId, pc.type)
                                if (imdb != null) {
                                    app.push(Overlay.Info(MetaPreview(id = imdb, type = pc.type, name = pc.title, poster = TmdbClient.image(pc.posterPath, "w342"), releaseInfo = pc.year), null))
                                } else {
                                    // No IMDb ID: the user's add-ons can't look it up directly, so search by title.
                                    app.showToast(cantOpen)
                                    app.overlays.clear(); app.searchQuery = pc.title; app.section = Section.SEARCH
                                }
                            }
                        },
                        modifier = Modifier.width(118.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier).traceFocus("person:credit#$i")
                            .semantics { contentDescription = listOfNotNull(pc.title, pc.year, pc.role).joinToString(", ") },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = c.raised, focusedContainerColor = c.raised),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
                        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(177.dp).background(c.panel), contentAlignment = Alignment.Center) {
                                Text(pc.title, style = Vyrk.type.labelL, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp), maxLines = 4)
                                TmdbClient.image(pc.posterPath, "w342")?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                                Text(pc.title, style = Vyrk.type.labelM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(pc.year, pc.role).joinToString(" · "), style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
