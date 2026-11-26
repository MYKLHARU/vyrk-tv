package com.vyrk.tv.ui.overlays

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.CardFacts
import com.vyrk.core.tmdb.TmdbClient
import com.vyrk.core.tmdb.TmdbDetails
import com.vyrk.core.tmdb.TmdbTitle
import com.vyrk.tv.R
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.Poster
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.focusHalo
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.theme.focusZoom
import com.vyrk.tv.ui.traceFocus
import com.vyrk.tv.ui.tryRequestFocus
import kotlinx.coroutines.launch

// The title page's tabs below the hero (InfoPage): Details, Cast & crew, Related and Extras.
// Rows run to the screen's edges: they're laid out full width and padded to the margins.

/** A row's padding: the screen's margins at either end, a little room above and below for focus. */
private fun rowPadding(vertical: androidx.compose.ui.unit.Dp) = PaddingValues(horizontal = Dimens.screenMargin, vertical = vertical)

private val CAST_W = 120.dp
private val CAST_H = 160.dp
/** Cast in the row before "View all". */
private const val CAST_ROW = 15
private val EXTRA_W = 236.dp
private val EXTRA_H = 133.dp

/**
 * Who made the title, for the Cast & crew tab: TMDB's credits with photos when there are any,
 * else the names the add-on gives. Crew is the creators, directors, writers, composer, producers
 * and cinematographer, one card per person with their roles together.
 */
internal data class TitlePeople(val cast: List<Person>, val crew: List<Person>) {
    /** [id] is TMDB's person id (null for a name from the add-on: OK then searches for it). */
    data class Person(val id: Long?, val name: String, val role: String?, val photo: String?)

    companion object {
        private val crewRank = listOf("Creator", "Director", "Screenplay", "Writer", "Novel", "Story", "Original Music Composer", "Producer", "Director of Photography")
        private const val CREW_MAX = 16

        fun of(full: MetaItem?, tmdb: TmdbDetails?, directorLabel: String, writerLabel: String): TitlePeople {
            val credits = tmdb?.credits
            if (tmdb != null && credits != null && (credits.cast.isNotEmpty() || credits.crew.isNotEmpty() || tmdb.createdBy.isNotEmpty())) {
                val cast = credits.cast.distinctBy { it.personId }.map { Person(it.personId, it.name, it.role, it.profilePath) }
                val crew = (tmdb.createdBy + credits.crew.filter { it.role in crewRank })
                    .groupBy { it.personId }.values
                    .map { roles -> roles.sortedBy { crewRank.indexOf(it.role) } }
                    .sortedWith(compareBy<List<com.vyrk.core.tmdb.Credit>> { crewRank.indexOf(it.first().role) }.thenBy { it.first().name })
                    .take(CREW_MAX)
                    .map { roles -> Person(roles.first().personId, roles.first().name, roles.mapNotNull { it.role }.distinct().joinToString(", "), roles.firstNotNullOfOrNull { it.profilePath }) }
                return TitlePeople(cast, crew)
            }
            val cast = full?.castNames.orEmpty().distinct().map { Person(null, it, null, null) }
            val crew = (full?.directorNames.orEmpty().map { it to directorLabel } + full?.writerNames.orEmpty().map { it to writerLabel })
                .groupBy({ it.first }, { it.second }).map { (name, roles) -> Person(null, name, roles.distinct().joinToString(", "), null) }
            return TitlePeople(cast, crew)
        }
    }
}

/**
 * Videos for the Extras tab: TMDB's (trailers, teasers, clips, featurettes, behind the scenes,
 * bloopers) and the add-on's trailers, one per YouTube video.
 */
internal object TitleExtras {
    /** [type] is TMDB's video type ("Trailer", "Featurette", …), or null when unknown. */
    data class Extra(val key: String, val name: String, val type: String?)

    fun of(meta: MetaPreview, full: MetaItem?, tmdb: TmdbDetails?): List<Extra> {
        val fromTmdb = tmdb?.videos.orEmpty().map { Extra(it.key, it.name, it.type) }
        val fromAddon = (full?.trailerStreams ?: meta.trailerStreams).mapNotNull { t -> t.ytId?.takeIf { it.isNotBlank() }?.let { Extra(it, t.title.orEmpty(), "Trailer") } } +
            (full?.trailers ?: meta.trailers).mapNotNull { t -> t.source.takeIf { it.isNotBlank() }?.let { Extra(it, "", t.type) } }
        return (fromTmdb + fromAddon).distinctBy { it.key }
    }
}

@Composable
private fun extraTypeLabel(type: String?): String = when (type?.lowercase()) {
    "trailer" -> str(R.string.trailer)
    "teaser" -> str(R.string.extra_teaser)
    "clip" -> str(R.string.extra_clip)
    "featurette" -> str(R.string.extra_featurette)
    "behind the scenes" -> str(R.string.extra_behind_scenes)
    "bloopers" -> str(R.string.extra_bloopers)
    else -> str(R.string.extra_video)
}

/** A heading over a row: the label, and a count in muted type. */
@Composable
private fun PanelHeading(label: String, count: Int? = null) {
    val c = Vyrk.colors
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = Vyrk.type.headlineS, color = c.text, maxLines = 1)
        if (count != null) Text(count.toString(), style = Vyrk.type.titleM, color = c.stageTextSecondary, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Details

/**
 * The title's facts as label and value, only those a source gives: director (films) or creators
 * (series), writers, genres, release or first-air date, country, original language, production
 * companies, certificate and IMDb's score. With [overview] (the hero cut the synopsis short) the
 * facts take the left column and the whole synopsis the right; otherwise the facts fill both.
 */
@Composable
internal fun DetailsPanel(meta: MetaPreview, full: MetaItem?, tmdb: TmdbDetails?, facts: CardFacts, overview: String?, loading: Boolean) {
    val c = Vyrk.colors
    val rows = detailRows(meta, full, tmdb, facts)
    if (rows.isEmpty() && overview == null) {
        Text(str(if (loading) R.string.loading else R.string.no_details), style = Vyrk.type.bodyM, color = c.textMuted, modifier = Modifier.padding(top = 20.dp))
        return
    }
    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            val split = if (overview != null) rows.size else (rows.size + 1) / 2
            FactTable(rows.take(split), Modifier.weight(1f))
            val right = rows.drop(split)
            if (overview != null || right.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 28.dp).width(1.dp).fillMaxHeight().background(c.text.copy(alpha = 0.1f)))
                Column(Modifier.weight(1f)) {
                    if (overview != null) {
                        Text(str(R.string.overview), style = Vyrk.type.titleL, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        Text(overview, style = Vyrk.type.bodyM, color = c.stageTextSecondary, maxLines = 9, overflow = TextOverflow.Ellipsis)
                    } else FactTable(right, Modifier.fillMaxWidth())
                }
            } else Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        // Credited only for what is shown: IMDb's score, and TMDB's details.
        val credits = listOfNotNull(
            if (facts.imdb != null) str(R.string.rating_attribution) else null,
            if (tmdb != null) TmdbClient.ATTRIBUTION else null,
        )
        if (credits.isNotEmpty()) Text(credits.joinToString("  ·  "), style = Vyrk.type.labelS, color = c.textMuted, modifier = Modifier.padding(bottom = 18.dp))
    }
}

@Composable
private fun FactTable(rows: List<Pair<String, String>>, modifier: Modifier) {
    val c = Vyrk.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = Vyrk.type.titleS, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp).padding(top = 1.dp))
                Text(value, style = Vyrk.type.bodyM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun detailRows(meta: MetaPreview, full: MetaItem?, tmdb: TmdbDetails?, facts: CardFacts): List<Pair<String, String>> {
    val series = meta.type == "series"
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val crew = tmdb?.credits?.crew.orEmpty()
    fun names(list: List<String>, max: Int) = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(max).joinToString(", ").takeIf { it.isNotEmpty() }
    val director = if (series) null else names(full?.directorNames.orEmpty().ifEmpty { crew.filter { it.role == "Director" }.map { it.name } }, 3)
    val creators = if (series) names(tmdb?.createdBy.orEmpty().map { it.name }, 3) else null
    val writers = if (series) null else names(full?.writerNames.orEmpty().ifEmpty { crew.filter { it.role in listOf("Screenplay", "Writer", "Novel", "Story") }.map { it.name } }, 4)
    val genres = names(full?.allGenres.orEmpty().ifEmpty { meta.genres }, 6)
    val date = (full?.released ?: tmdb?.releaseDate)?.let { formatDate(it, locale) }
    val country = full?.country?.takeIf { it.isNotBlank() }
        ?: names(tmdb?.productionCountries.orEmpty().map { java.util.Locale("", it).getDisplayCountry(locale) }, 3)
    val language = (tmdb?.originalLanguage ?: full?.originalLanguage)?.takeIf { it.isNotBlank() }?.let { code ->
        java.util.Locale(code).getDisplayLanguage(locale).takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(locale) } ?: com.vyrk.core.lang.Languages.name(code)
    }
    val production = names(tmdb?.productionCompanies.orEmpty(), 3)
    val certificate = facts.certificate ?: full?.certification?.takeIf { it.isNotBlank() }
    val rating = facts.imdb?.let { "IMDb ${CardFacts.formatImdb(it)}/10" }
    return listOfNotNull(
        director?.let { str(R.string.director) to it },
        creators?.let { str(R.string.created_by) to it },
        writers?.let { str(R.string.writers) to it },
        genres?.let { str(R.string.genres) to it },
        date?.let { str(if (series) R.string.first_aired else R.string.release_date) to it },
        country?.let { str(R.string.country) to it },
        language?.let { str(R.string.original_language_label) to it },
        production?.let { str(R.string.production) to it },
        certificate?.let { str(R.string.certification) to it },
        rating?.let { str(R.string.ratings) to it },
    )
}

/** "2010-10-01" or "2010-10-01T00:00:00.000Z" in the app's language ("1 Oct 2010"); null when it isn't a date. */
private fun formatDate(iso: String, locale: java.util.Locale): String? = runCatching {
    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso.take(10)) ?: return null
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(day)
}.getOrNull()

// ---------------------------------------------------------------- Cast & crew

/**
 * The cast as a row of large portraits (name, then character), with "View all" after the first
 * [CAST_ROW] when there are more; it opens the whole cast as a grid, and Back returns to the row.
 * Then the crew as compact cards. OK on anyone opens their page (a name without a TMDB id searches).
 */
@Composable
internal fun CastPanel(people: TitlePeople) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var all by remember(people) { mutableStateOf(false) }
    val viewAllReq = remember { FocusRequester() }
    val firstReq = remember { FocusRequester() }
    val castReq = remember { FocusRequester() }
    val crewReq = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val open: (TitlePeople.Person) -> Unit = { p ->
        val id = p.id
        if (id != null) app.push(Overlay.Person(id, p.name)) else { app.overlays.clear(); app.searchQuery = p.name; app.section = Section.SEARCH }
    }
    if (all) {
        androidx.activity.compose.BackHandler {
            all = false
            scope.launch { repeat(2) { withFrameNanos { } }; viewAllReq.tryRequestFocus() }
        }
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; firstReq.tryRequestFocus() }
        LazyVerticalGrid(
            GridCells.Adaptive(CAST_W + 16.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dimens.screenMargin, end = Dimens.screenMargin, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { PanelHeading(str(R.string.cast), people.cast.size) }
            items(people.cast.size, key = { "all-$it" }) { i ->
                val p = people.cast[i]
                CastCard(p, "info:cast_all#$i", if (i == 0) Modifier.focusRequester(firstReq) else Modifier) { open(p) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        if (people.cast.isNotEmpty()) {
            Box(Modifier.padding(start = Dimens.screenMargin)) { PanelHeading(str(R.string.cast), people.cast.size) }
            // Entering the row lands on the first card, then on the one last focused.
            LazyRow(Modifier.focusRestorer(castReq), state = rowState, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = rowPadding(12.dp)) {
                itemsIndexed(people.cast.take(CAST_ROW), key = { i, p -> "${p.id ?: p.name}-$i" }) { i, p ->
                    CastCard(p, "info:person#$i", if (i == 0) Modifier.focusRequester(castReq) else Modifier) { open(p) }
                }
                if (people.cast.size > CAST_ROW) item(key = "view-all") {
                    ViewAllCard(people.cast.size, Modifier.focusRequester(viewAllReq)) { all = true }
                }
            }
        }
        if (people.crew.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(start = Dimens.screenMargin)) { PanelHeading(str(R.string.crew)) }
            LazyRow(Modifier.focusRestorer(crewReq), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = rowPadding(10.dp)) {
                itemsIndexed(people.crew, key = { i, p -> "crew-${p.id ?: p.name}-$i" }) { i, p ->
                    CrewCard(p, "info:crew#$i", if (i == 0) Modifier.focusRequester(crewReq) else Modifier) { open(p) }
                }
            }
        }
    }
}

/** The initials of a name, for a portrait without a photo. */
private fun initials(name: String) = name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")

/** A portrait (3:4, rounded) that scales and lights its edge with focus, then the name and the character. */
@Composable
private fun CastCard(p: TitlePeople.Person, trace: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Vyrk.colors
    Column(Modifier.width(CAST_W)) {
        Surface(
            onClick = onClick,
            modifier = modifier.focusHalo().size(CAST_W, CAST_H).traceFocus(trace)
                .semantics { contentDescription = listOfNotNull(p.name, p.role).joinToString(", ") },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = c.stageSurface, focusedContainerColor = c.stageSurface),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))),
                focusedBorder = Border(BorderStroke(2.dp, c.accent)),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = focusZoom(1.05f)),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(initials(p.name), style = Vyrk.type.cinematicM, color = c.accent.copy(alpha = 0.55f))
                TmdbClient.image(p.photo)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(p.name, style = Vyrk.type.titleS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        p.role?.takeIf { it.isNotBlank() }?.let { Text(it, style = Vyrk.type.labelM, color = c.stageTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** After the cast row: the whole cast's count, and OK opens it as a grid. */
@Composable
private fun ViewAllCard(count: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = modifier.focusHalo().size(CAST_W, CAST_H).traceFocus("info:cast_view_all"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = c.stageSurface, focusedContainerColor = c.stageSurface),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusZoom(1.05f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(R.drawable.ic_people, null, Modifier.size(26.dp), tint = c.accent)
            Spacer(Modifier.height(10.dp))
            Text(str(R.string.view_all), style = Vyrk.type.titleS, color = c.text, maxLines = 1)
            Text(str(R.string.people_count, count), style = Vyrk.type.labelM, color = c.stageTextSecondary, maxLines = 1)
        }
    }
}

/** A crew member: round photo (or initials), name, and their roles. */
@Composable
private fun CrewCard(p: TitlePeople.Person, trace: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = modifier.focusHalo().height(56.dp).widthIn(max = 260.dp).traceFocus(trace)
            .semantics { contentDescription = listOfNotNull(p.name, p.role).joinToString(", ") },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = c.stageSurface.copy(alpha = 0.8f), contentColor = c.text, focusedContainerColor = c.stageSurface, focusedContentColor = c.text),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))),
            focusedBorder = Border(BorderStroke(2.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusZoom(1.04f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 8.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
                Text(initials(p.name), style = Vyrk.type.labelL, color = c.stageTextSecondary)
                TmdbClient.image(p.photo)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column {
                Text(p.name, style = Vyrk.type.titleS, maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.role?.takeIf { it.isNotBlank() }?.let { Text(it, style = Vyrk.type.labelM, color = c.stageTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---------------------------------------------------------------- Related

/**
 * Titles TMDB recommends, as the app's own poster cards. They're shown by TMDB id; OK looks up
 * the IMDb id and opens the title through your add-ons (or searches for it when there is none).
 */
@Composable
internal fun RelatedPanel(titles: List<TmdbTitle>) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val cantOpen = str(R.string.cant_open_title)
    val metas = remember(titles) {
        titles.map { t ->
            MetaPreview(id = "tmdb:${t.tmdbId}", type = t.type, name = t.title, poster = TmdbClient.image(t.posterPath, "w342"),
                background = TmdbClient.image(t.backdropPath, "w1280"), releaseInfo = t.year, description = t.overview)
        }
    }
    val firstReq = remember { FocusRequester() }
    LazyRow(Modifier.padding(top = 18.dp).focusRestorer(firstReq), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = rowPadding(12.dp)) {
        itemsIndexed(metas, key = { _, m -> m.type + m.id }) { i, m ->
            val t = titles[i]
            Poster(m, 120.dp, posterState(null, emptySet()), modifier = if (i == 0) Modifier.focusRequester(firstReq) else Modifier, onClick = {
                scope.launch {
                    val imdb = graph.tmdb.imdbId(t.tmdbId, t.type)
                    if (imdb != null) app.push(Overlay.Info(m.copy(id = imdb), null))
                    else { app.showToast(cantOpen); app.overlays.clear(); app.searchQuery = t.title; app.section = Section.SEARCH }
                }
            }, onLongClick = {}, onPlayPause = {}, traceLabel = "info:related#$i")
        }
    }
}

// ---------------------------------------------------------------- Extras

/** The title's videos as cards: the kind (trailer, featurette…) and the name. OK plays one full screen. */
@Composable
internal fun ExtrasPanel(extras: List<TitleExtras.Extra>, playing: Boolean, onPlay: (TitleExtras.Extra) -> Unit) {
    val reqs = remember(extras) { extras.map { FocusRequester() } }
    var last by remember(extras) { mutableIntStateOf(-1) }
    // Closing the player returns to the card that started it.
    LaunchedEffect(playing) { if (!playing && last >= 0) { repeat(2) { withFrameNanos { } }; reqs.getOrNull(last)?.tryRequestFocus() } }
    LazyRow(Modifier.padding(top = 18.dp).then(if (reqs.isNotEmpty()) Modifier.focusRestorer(reqs[0]) else Modifier), horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = rowPadding(12.dp)) {
        itemsIndexed(extras, key = { _, x -> x.key }) { i, x ->
            ExtraCard(x, "info:extra#$i", Modifier.focusRequester(reqs[i])) { last = i; onPlay(x) }
        }
    }
}

@Composable
private fun ExtraCard(x: TitleExtras.Extra, trace: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Vyrk.colors
    val kind = extraTypeLabel(x.type)
    Column(Modifier.width(EXTRA_W)) {
        Surface(
            onClick = onClick,
            modifier = modifier.focusHalo().size(EXTRA_W, EXTRA_H).traceFocus(trace).semantics { contentDescription = listOf(kind, x.name).filter { it.isNotBlank() }.joinToString(", ") },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = c.stageSurface, focusedContainerColor = c.stageSurface),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))),
                focusedBorder = Border(BorderStroke(2.dp, c.accent)),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = focusZoom(1.05f)),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.stageSurface, c.stage))), contentAlignment = Alignment.Center) {
                Box(Modifier.size(46.dp).background(c.accent.copy(alpha = 0.9f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.ic_play, null, Modifier.size(20.dp), tint = c.onAccent)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(kind.uppercase(), style = Vyrk.type.eyebrow, color = c.accent, maxLines = 1)
        if (x.name.isNotBlank()) Text(x.name, style = Vyrk.type.titleS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

/**
 * An extra playing full screen with sound. It never takes focus: the page holds the keys while it
 * plays (OK opens it in YouTube, Back closes it; see InfoPage).
 */
@Composable
internal fun ExtraPlayer(x: TitleExtras.Extra) {
    val c = Vyrk.colors
    var state by remember(x.key) { mutableStateOf(com.vyrk.tv.ui.preview.TrailerState.LOADING) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state != com.vyrk.tv.ui.preview.TrailerState.REFUSED) {
            com.vyrk.tv.ui.preview.YouTubeTrailer(x.key, muted = false, modifier = Modifier.fillMaxSize()) { state = it }
        } else {
            Text(str(R.string.extra_refused), style = Vyrk.type.titleL, color = c.text, modifier = Modifier.align(Alignment.Center).padding(48.dp))
        }
        // Until it plays (or if it can't), say what it is and which keys do what.
        if (state != com.vyrk.tv.ui.preview.TrailerState.PLAYING) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 32.dp, end = 48.dp)) {
                Text(extraTypeLabel(x.type).uppercase(), style = Vyrk.type.eyebrow, color = c.accent)
                if (x.name.isNotBlank()) Text(x.name, style = Vyrk.type.headlineS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(str(R.string.extra_keys), style = Vyrk.type.labelM, color = c.stageTextSecondary)
            }
        }
    }
}
