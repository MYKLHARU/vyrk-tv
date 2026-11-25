package com.vyrk.tv.ui.phone

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.RuntimeText
import com.vyrk.core.addon.Video
import com.vyrk.core.lang.Languages
import com.vyrk.core.library.EpisodeGuide
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.stream.Ranked
import com.vyrk.core.stream.SourceFilters
import com.vyrk.core.stream.SourceSummary
import com.vyrk.core.tmdb.Credits
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.player.Playback
import com.vyrk.tv.player.exclusionText
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.HeroLogo
import com.vyrk.tv.ui.browse.produceMeta
import com.vyrk.tv.ui.browse.rememberTitleArt
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.overlays.PersonPhoto
import com.vyrk.tv.ui.overlays.SourcesState
import com.vyrk.tv.ui.overlays.loadTitleSources
import com.vyrk.tv.ui.overlays.rememberTmdbCredits
import com.vyrk.tv.ui.overlays.sourcesMessage
import com.vyrk.tv.ui.overlays.timeLeftLabel
import com.vyrk.tv.ui.preview.openInYouTube
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.launch

/**
 * The title page on a phone, one scrolling column: the artwork with the logo and facts, Play,
 * the actions, the synopsis and the people; then the episodes of a series (seasons as chips) or
 * the sources of a film; then the credits.
 */
@Composable
fun PhoneInfoPage(o: Overlay.Info, onClose: () -> Unit) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val session = LocalSession.current ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by session.settings.collectAsState()
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    val meta = o.meta
    val details = produceMeta(session, meta)
    val full by details
    val credits = rememberTmdbCredits(graph, meta)
    val item: LibraryItem? = library[meta.id]
    val isSeries = meta.type == "series" && (full?.videos?.isNotEmpty() ?: true)
    val showSources = !isSeries && (settings.sourcesOnInfoPage || o.showSources)
    var sources by remember { mutableStateOf<SourcesState>(SourcesState.Idle) }
    fun loadSources() {
        sources = SourcesState.Loading
        scope.launch { sources = loadTitleSources(graph, session, meta, RuntimeText.minutes(full?.runtime)) }
    }
    LaunchedEffect(meta.id, showSources) { if (showSources && (settings.loadSourcesOnOpen || o.showSources) && sources is SourcesState.Idle) loadSources() }
    com.vyrk.tv.ui.overlays.InstantStartEffect(graph, session, meta, sources, pageLoadsSources = showSources && (settings.loadSourcesOnOpen || o.showSources))

    val c = Vyrk.colors
    val art = rememberTitleArt(meta, full, metadataLoading = details.loading)
    val name = full?.name?.takeIf { it.isNotBlank() } ?: meta.name
    val synopsis = full?.description ?: meta.description ?: ""
    val listState = rememberLazyListState()
    val noTrailer = str(R.string.no_trailer)
    var episodeMenu by remember { mutableStateOf<Video?>(null) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        com.vyrk.tv.ui.components.AmbientTintGlow(strength = 1.2f)
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            item(key = "art") { InfoHeader(meta, full, art.backdrops, art.logos, art.logosLoading, name) }
            item(key = "actions") {
                Column(Modifier.padding(horizontal = PhoneMargin)) {
                    val inCw = item?.isInContinueWatching == true
                    val resumeEp = if (inCw && isSeries) LibraryRules.episodeLabel(item.id, item.state.videoId) else null
                    PhoneButton(str(if (inCw) R.string.resume else R.string.play) + (resumeEp?.let { "  ·  $it" } ?: ""), {
                        val ranked = (sources as? SourcesState.Ready)?.ranked
                        Playback.start(activity, graph, session, meta, ranked = if (isSeries) null else ranked)
                    }, icon = R.drawable.ic_play, style = PhoneButtonStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), height = 52.dp, tag = "info:play")
                    if (inCw && item.progress > 0f) {
                        Spacer(Modifier.height(10.dp))
                        ResumeLine(item)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val inLib = item?.inLibrary == true
                        val watched = item?.watched == true
                        InfoAction(if (inLib) R.drawable.ic_check else R.drawable.ic_plus, str(if (inLib) R.string.in_library else R.string.add_to_library), inLib, "info:library") {
                            if (inLib) session.removeFromLibrary(meta.id) else session.addToLibrary(meta)
                        }
                        InfoAction(R.drawable.ic_trailer, str(R.string.trailer), false, "info:trailer") {
                            val yt = full?.youtubeTrailerId ?: meta.trailerStreams.firstNotNullOfOrNull { it.ytId }
                            if (yt != null) openInYouTube(context, yt) else app.showToast(noTrailer)
                        }
                        InfoAction(R.drawable.ic_eye, str(if (watched) R.string.watched else R.string.mark_watched), watched, "info:watched") {
                            session.markWatched(meta, !watched)
                        }
                        if (!isSeries) InfoAction(R.drawable.ic_sources, str(R.string.sources), sources !is SourcesState.Idle, "info:sources") {
                            if (sources is SourcesState.Idle) loadSources()
                            scope.launch { listState.animateScrollToItem(4) }
                        }
                    }
                }
            }
            item(key = "synopsis") {
                if (synopsis.isNotBlank()) {
                    var open by remember { mutableStateOf(false) }
                    Text(synopsis, style = Vyrk.type.bodyL, color = c.text.copy(alpha = 0.88f), maxLines = if (open) 40 else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = PhoneMargin, end = PhoneMargin, top = 18.dp).press({ open = !open }, pressedScale = 1f))
                }
            }
            item(key = "people") { People(credits, full) }
            if (isSeries) episodes(this, session, meta, full, details.loading, item, sstate.newEpisodes[meta.id].orEmpty(), settings.blurUnwatched,
                onPlay = { v -> Playback.start(activity, graph, session, meta, videoId = v.id) },
                onMenu = { v -> episodeMenu = v },
                onMarkSeason = { eps -> full?.let { session.markVideos(it, eps, true) } })
            else if (showSources) sourcesSection(this, sources, onRefresh = { loadSources() }, onPick = { r ->
                Playback.start(activity, graph, session, meta, picked = r, ranked = (sources as? SourcesState.Ready)?.ranked)
            })
            item(key = "credits") { CreditsBlock(full) }
        }
        // A back arrow over the artwork; the bar fills in once the artwork has scrolled away.
        val solid by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240 } }
        Box(Modifier.fillMaxWidth().background(if (solid) c.background else Color.Transparent)) {
            PhoneTopBar(if (solid) name else null, onBack = onClose)
        }
    }

    // Long press on an episode: its watched actions.
    val v = episodeMenu
    PhoneSheet(visible = v != null, onDismiss = { episodeMenu = null }, title = v?.let { listOfNotNull(it.season?.let { s -> "S$s" }, it.episode?.let { e -> "E$e" }).joinToString(" · ") + "  " + it.displayTitle }) {
        val m = full
        if (v != null && m != null) {
            val wb = item?.watchedBitField(m.videos)
            val watched = wb?.getVideo(v.id) == true
            val eps = m.videos.filter { it.season == v.season }
            PhoneListRow(str(R.string.play), { episodeMenu = null; Playback.start(activity, graph, session, meta, videoId = v.id) }, icon = R.drawable.ic_play)
            PhoneListRow(str(if (watched) R.string.mark_unwatched else R.string.mark_watched), { session.markVideos(m, listOf(v), !watched); episodeMenu = null }, icon = R.drawable.ic_check)
            PhoneListRow(str(R.string.mark_previous_watched), { session.markVideos(m, LibraryRules.videosUpTo(m, v), true); episodeMenu = null }, icon = R.drawable.ic_check)
            PhoneListRow(str(R.string.mark_season_unwatched), { session.markVideos(m, eps, false); episodeMenu = null }, icon = R.drawable.ic_eye_off)
        }
    }
}

@Composable
private fun InfoHeader(meta: MetaPreview, full: MetaItem?, backdrops: List<String>, logos: List<String>, logosLoading: Boolean, name: String) {
    val c = Vyrk.colors
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Landscape: short enough that Play shows under the artwork.
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val h = minOf((maxWidth * 1.05f).coerceIn(360.dp, 560.dp), screenH * 0.72f)
        Box(Modifier.fillMaxWidth().height(h)) {
            val urls = (backdrops + listOfNotNull(meta.poster)).distinct()
            var attempt by remember(urls) { mutableIntStateOf(0) }
            urls.getOrNull(attempt)?.let { u ->
                val context = androidx.compose.ui.platform.LocalContext.current
                AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { if (urls.getOrNull(attempt) == u) attempt += 1 },
                    onSuccess = { kotlinx.coroutines.MainScope().launch { com.vyrk.tv.ui.components.AmbientTint.follow(context, u) } })
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to c.shade.copy(alpha = 0.6f), 0.2f to Color.Transparent, 0.5f to Color.Transparent, 0.82f to c.background.copy(alpha = 0.9f), 1f to c.background,
            )))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = PhoneMargin, vertical = 14.dp)) {
                com.vyrk.tv.ui.browse.TitleEyebrow(meta)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.BottomStart) {
                    HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), name, logos, this@BoxWithConstraints.maxWidth * 0.8f, 92.dp,
                        Vyrk.type.cinematicM, Modifier.fillMaxHeight(), loading = logosLoading)
                }
                Spacer(Modifier.height(10.dp))
                Facts(meta, full)
            }
        }
    }
}

/** Certificate, year, genres, seasons, runtime and the IMDb rating, wrapping onto a second line if needed. */
@Composable
private fun Facts(meta: MetaPreview, full: MetaItem?) {
    val c = Vyrk.colors
    val cert = full?.certification?.takeIf { it.isNotBlank() }
    val seasonCount = full?.videos?.mapNotNull { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 && meta.type == "series" }
    val parts = listOfNotNull(
        (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() },
        (full?.allGenres ?: meta.genres).take(2).joinToString(" / ").takeIf { it.isNotBlank() },
        seasonCount?.let { n -> if (n == 1) str(R.string.season_n, 1) else str(R.string.seasons_count, n) },
        RuntimeText.format(full?.runtime ?: meta.runtime),
    )
    ChipFlow(spacing = 8.dp) {
        if (cert != null) Box(Modifier.border(1.dp, c.textSecondary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(cert, style = Vyrk.type.labelM, color = c.textSecondary, maxLines = 1)
        }
        Text(parts.joinToString("  ·  "), style = Vyrk.type.labelL, color = c.textSecondary)
        val rating = full?.imdbRating ?: meta.imdbRating
        if (!rating.isNullOrBlank()) Tag("IMDb $rating", color = c.onGold, background = c.gold)
    }
}

@Composable
private fun InfoAction(icon: Int, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val c = Vyrk.colors
    Column(
        Modifier.width(76.dp).press(onClick, tag = tag).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).background(if (active) c.gold.copy(alpha = 0.18f) else c.steel.copy(alpha = 0.7f), CircleShape)
                .border(1.dp, if (active) c.gold.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(19.dp), tint = if (active) c.goldHighlight else c.text) }
        Text(label, style = Vyrk.type.labelM, color = if (active) c.goldHighlight else c.textSecondary, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ResumeLine(item: LibraryItem) {
    val c = Vyrk.colors
    val st = item.state
    val left = timeLeftLabel(st)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(item.progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
        }
        val label = listOfNotNull(LibraryRules.episodeLabel(item.id, st.videoId), left).joinToString("  ·  ")
        if (label.isNotEmpty()) Text(label, style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1)
    }
}

private data class PersonRef(val name: String, val role: String?, val photo: String?, val personId: Long?)

/** The director and cast with their photos (TMDB, when the build has a key), names only otherwise. */
@Composable
private fun People(credits: Credits?, full: MetaItem?) {
    val app = LocalApp.current
    val c = Vyrk.colors
    val people = if (credits != null && (credits.cast.isNotEmpty() || credits.keyCrew.isNotEmpty())) {
        val director = credits.keyCrew.firstOrNull { it.role?.contains("Director", ignoreCase = true) == true }
        (listOfNotNull(director) + credits.cast.take(20)).distinctBy { it.personId }.map { PersonRef(it.name, it.role, it.profilePath, it.personId) }
    } else (full?.directorNames.orEmpty().take(1) + full?.castNames.orEmpty().take(15)).distinct().map { PersonRef(it, null, null, null) }
    if (people.isEmpty()) return
    Column(Modifier.padding(top = 22.dp)) {
        PhoneSectionHeader(str(R.string.cast_and_crew))
        LazyRow(contentPadding = PaddingValues(horizontal = PhoneMargin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(people, key = { i, p -> "${p.personId ?: p.name}-$i" }) { i, p ->
                Column(
                    Modifier.width(76.dp).press({
                        val id = p.personId
                        if (id != null) app.push(Overlay.Person(id, p.name))
                        else { app.overlays.clear(); app.searchQuery = p.name; app.section = Section.SEARCH }
                    }, tag = "info:person#$i"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PersonPhoto(p.name, p.photo, 64.dp)
                    Text(p.name, style = Vyrk.type.labelM, color = c.text, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    p.role?.let { Text(it, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Seasons as chips, Mark season watched, then the season's episodes; opens at the next one to watch. */
private fun episodes(
    scope: LazyListScope, session: ProfileSession, meta: MetaPreview, full: MetaItem?, loading: Boolean, item: LibraryItem?, newIds: Set<String>, blur: Boolean,
    onPlay: (Video) -> Unit, onMenu: (Video) -> Unit, onMarkSeason: (List<Video>) -> Unit,
) = with(scope) {
    item(key = "episodes") {
        val c = Vyrk.colors
        Column(Modifier.padding(top = 22.dp)) {
            PhoneSectionHeader(str(R.string.episodes))
            if (full == null) Text(str(if (loading) R.string.loading else R.string.no_episodes), style = Vyrk.type.bodyM, color = c.textMuted, modifier = Modifier.padding(PhoneMargin))
        }
    }
    val m = full ?: return@with
    item(key = "season-guide") { SeasonGuide(m, item, newIds, blur, onPlay, onMenu, onMarkSeason) }
}

@Composable
private fun SeasonGuide(meta: MetaItem, item: LibraryItem?, newIds: Set<String>, blur: Boolean, onPlay: (Video) -> Unit, onMenu: (Video) -> Unit, onMarkSeason: (List<Video>) -> Unit) {
    val c = Vyrk.colors
    val seasons = EpisodeGuide.seasons(meta.videos)
    val wb = item?.watchedBitField(meta.videos)
    val now = System.currentTimeMillis()
    val upNextId = remember(meta.id, item, wb) { EpisodeGuide.upNextId(meta.videos, item, wb, now) }
    var season by remember(meta.id) { mutableStateOf(EpisodeGuide.startSeason(meta.videos, item, upNextId)) }
    val eps = meta.videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    val seasonRow = rememberLazyListState()
    LaunchedEffect(meta.id) { seasons.indexOf(season).takeIf { it > 0 }?.let { seasonRow.scrollToItem(it) } }
    Column {
        LazyRow(state = seasonRow, contentPadding = PaddingValues(horizontal = PhoneMargin, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons) { s ->
                PhoneChip(if (s == 0) str(R.string.specials) else str(R.string.season_n, s), s == season, { season = s }, tag = "season:$s")
            }
        }
        PhoneButton(str(R.string.mark_season_watched), { onMarkSeason(eps) }, icon = R.drawable.ic_check, style = PhoneButtonStyle.GHOST, height = 40.dp,
            modifier = Modifier.padding(start = 4.dp), tag = "season:mark")
        Column(Modifier.padding(horizontal = PhoneMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            eps.forEach { v ->
                val watched = wb?.getVideo(v.id) == true
                val released = EpisodeGuide.releasedAt(v)
                val future = released != null && released > now
                val progress = if (item?.state?.videoId == v.id && item.isInContinueWatching) item.progress else 0f
                val date = released?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) }
                EpisodeRow(v, watched, progress, v.id == upNextId, v.id in newIds, future, date?.let { if (future) str(R.string.airs_on, it) else it },
                    blur && !watched, onClick = { if (!future) onPlay(v) }, onLongClick = { onMenu(v) })
            }
        }
    }
}

@Composable
private fun EpisodeRow(v: Video, watched: Boolean, progress: Float, upNext: Boolean, isNew: Boolean, future: Boolean, date: String?, blur: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Vyrk.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().press(onClick, onLongClick, tag = "episode:${v.id}", pressedScale = 0.985f).clip(shape)
            .background(if (upNext) c.accent.copy(alpha = 0.08f) else c.panel.copy(alpha = 0.6f), shape)
            .border(1.dp, if (upNext) c.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f), shape)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(136.dp).height(77.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(c.steel, c.panel)))) {
                Text("E${v.episode ?: ""}", style = Vyrk.type.cinematicS, color = c.gold.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.Center))
                if (v.thumbnail != null) AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (blur && Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier))
                if (watched || future) Box(Modifier.fillMaxSize().background(c.shade.copy(alpha = if (future) 0.55f else 0.35f)))
                if (watched) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).background(c.gold, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.ic_check, null, Modifier.size(10.dp), tint = c.background)
                }
                if (!future) Box(Modifier.align(Alignment.Center).size(30.dp).background(c.shade.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.ic_play, null, Modifier.padding(start = 2.dp).size(12.dp), tint = Color.White)
                }
                if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(listOfNotNull(v.season?.let { "S$it" }, v.episode?.let { "E$it" }).joinToString(" · "), style = Vyrk.type.eyebrow, color = c.gold, maxLines = 1)
                    if (upNext) Tag(str(R.string.next_episode), color = c.onAccent, background = c.accent)
                    else if (isNew) Tag(str(R.string.badge_new), color = c.onAccent, background = c.accent)
                }
                Text(v.displayTitle, style = Vyrk.type.titleM, color = if (watched) c.textSecondary else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val facts = listOfNotNull(date, RuntimeText.format(v.runtime))
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = Vyrk.type.labelM, color = c.textMuted, maxLines = 1)
            }
        }
        val syn = v.synopsis
        if (!syn.isNullOrBlank()) Text(if (blur) str(R.string.hidden_synopsis) else syn, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A film's sources: how many, a quality and language filter, and each source as a card. */
private fun sourcesSection(scope: LazyListScope, state: SourcesState, onRefresh: () -> Unit, onPick: (Ranked) -> Unit) = with(scope) {
    item(key = "sources-head") {
        val c = Vyrk.colors
        val ready = state as? SourcesState.Ready
        val playable = ready?.ranked?.filter { it.playable }.orEmpty()
        Column(Modifier.padding(top = 22.dp)) {
            Row(Modifier.padding(start = PhoneMargin, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.sources), style = Vyrk.type.headlineS, color = c.text)
                    if (ready != null) Text(str(R.string.sources_available, ready.ranked.size) + "  ·  " + str(R.string.streams_count, playable.size), style = Vyrk.type.bodyS, color = c.textSecondary)
                }
                PhoneIconButton(R.drawable.ic_refresh, str(R.string.refresh), onRefresh, filled = false, tag = "sources:refresh")
            }
            val msg = sourcesMessage(state)
            if (msg != null) Text(msg, style = Vyrk.type.bodyM, color = c.textMuted, modifier = Modifier.padding(horizontal = PhoneMargin, vertical = 8.dp))
        }
    }
    val ready = state as? SourcesState.Ready ?: return@with
    if (ready.ranked.isEmpty()) return@with
    item(key = "sources-list") { SourceList(ready, onPick) }
}

@Composable
private fun SourceList(ready: SourcesState.Ready, onPick: (Ranked) -> Unit) {
    val c = Vyrk.colors
    var filter by remember { mutableStateOf(SourceFilters.ALL) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val playable = ready.ranked.filter { it.playable }
    val langs = SourceFilters.languages(playable)
    val filters = SourceFilters.of(playable)
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = PhoneMargin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters) { f ->
                PhoneChip(if (f == SourceFilters.ALL) str(R.string.filter_all) else if (f in langs) Languages.name(f) else f, filter == f, { filter = f }, tag = "sources:filter:$f")
            }
        }
        val shown = ready.ranked.filter { SourceFilters.matches(it, filter) }
        val topKey = playable.firstOrNull()?.candidate?.key
        Column(Modifier.padding(horizontal = PhoneMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEachIndexed { i, r ->
                val k = r.candidate.addonId + r.candidate.index
                if (r.playable) SourceCard(r, recommended = r.candidate.key == topKey, expanded = expanded == k, tag = "source#$i",
                    onClick = { onPick(r) }, onLongClick = { expanded = if (expanded == k) null else k })
                else Text("${r.candidate.addonName} · ${r.info.resolutionLabel.ifEmpty { "—" }} — ${exclusionText(r)}", style = Vyrk.type.bodyS, color = c.textMuted)
            }
        }
    }
}

/** A source: its quality as a block (gold from 4K), the add-on, release details and languages. Long press shows the add-on's own text. */
@Composable
fun SourceCard(r: Ranked, recommended: Boolean, expanded: Boolean, tag: String, onClick: () -> Unit, onLongClick: () -> Unit, current: Boolean = false) {
    val c = Vyrk.colors
    val i = r.info
    val sum = remember(r) { SourceSummary.of(i) }
    val uhd = sum.uhd
    val shape = RoundedCornerShape(12.dp)
    val lit = recommended || current
    Column(
        Modifier.fillMaxWidth().press(onClick, onLongClick, tag = tag, pressedScale = 0.985f).clip(shape)
            .background(if (lit) c.accent.copy(alpha = 0.10f) else c.raised.copy(alpha = 0.6f), shape)
            .border(1.dp, if (lit) c.accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(58.dp).height(38.dp).background(c.shade.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .border(1.dp, if (uhd) c.gold.copy(alpha = 0.8f) else c.text.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(i.resolutionLabel.ifEmpty { "—" }, style = Vyrk.type.titleS, color = if (uhd) c.gold else c.text, maxLines = 1) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.candidate.addonName, style = Vyrk.type.titleS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (recommended) Tag(str(R.string.recommended), color = c.onAccent, background = c.accent)
                    com.vyrk.tv.ui.components.debridTag(r)?.let { Tag(it, color = c.gold, background = c.gold.copy(alpha = 0.16f)) }
                    if (current) Icon(R.drawable.ic_check, null, Modifier.size(16.dp), tint = c.gold)
                }
                if (sum.badges.isNotEmpty()) com.vyrk.tv.ui.components.QualityBadgeRow(sum.badges, Modifier.padding(top = 4.dp))
                Text(sum.details, style = Vyrk.type.labelM, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (sum.languages.isNotEmpty()) Text(sum.languages, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (expanded) Text(r.candidate.stream.text, style = Vyrk.type.bodyS, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CreditsBlock(full: MetaItem?) {
    val c = Vyrk.colors
    if (full == null) return
    Column(Modifier.padding(start = PhoneMargin, end = PhoneMargin, top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable fun line(label: String, value: String) {
            Column {
                Text(label.uppercase(), style = Vyrk.type.eyebrow, color = c.gold.copy(alpha = 0.85f))
                Text(value, style = Vyrk.type.bodyM, color = c.textSecondary)
            }
        }
        full.directorNames.takeIf { it.isNotEmpty() }?.let { line(str(R.string.director), it.joinToString(", ")) }
        full.writerNames.takeIf { it.isNotEmpty() }?.let { line(str(R.string.writers), it.take(4).joinToString(", ")) }
        full.country?.takeIf { it.isNotBlank() }?.let { line(str(R.string.country), it) }
        if (full.imdbRating != null) Text(str(R.string.rating_attribution), style = Vyrk.type.labelS, color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
    }
}
