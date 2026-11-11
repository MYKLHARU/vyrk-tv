package com.vyrk.tv.ui.overlays

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import com.vyrk.tv.ui.components.glassPanel
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.RuntimeText
import com.vyrk.core.addon.Video
import com.vyrk.core.lang.Languages
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.stream.Candidate
import com.vyrk.core.stream.Ranked
import com.vyrk.core.stream.StreamRanker
import com.vyrk.core.tmdb.Credits
import com.vyrk.tv.R
import com.vyrk.tv.player.Playback
import com.vyrk.tv.player.SourceLine
import com.vyrk.tv.player.exclusionText
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.HeroLogo
import com.vyrk.tv.ui.browse.rememberTitleArt
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.VyrkIconButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.components.focusGlow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface SourcesState {
    data object Idle : SourcesState
    data object Loading : SourcesState
    data class Ready(val ranked: List<Ranked>, val failedAddons: Int, val addonCount: Int) : SourcesState
}

private val LOGO_W = 380.dp
private val LOGO_H = 100.dp
private val PAGE_TEXT_W = 456.dp
private val CHIP_H = 34.dp
private val ACTION_H = 46.dp

/**
 * The title page, all on one screen. On the left, over the title's own artwork: logo, facts,
 * synopsis, people and Play / Trailer / library / watched / info. The info button opens and closes
 * a panel on the right with tabs: episodes for a series or sources for a film, then the details,
 * credits and cast. Back with the panel open closes it; Back again closes the page. The page stays
 * open under the player, so leaving the player comes back here.
 */
@Composable
fun InfoPage(o: Overlay.Info) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val session = LocalSession.current ?: return
    val scope = rememberCoroutineScope()
    val settings by session.settings.collectAsState()
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    val meta = o.meta
    val details = com.vyrk.tv.ui.browse.produceMeta(session, meta)
    val full by details
    // Cast and crew with photos from TMDB, when the build has a TMDB key and the title has an IMDb ID.
    var tmdbCredits by remember { mutableStateOf<Credits?>(null) }
    LaunchedEffect(meta.id) {
        if (!graph.tmdb.enabled || !meta.id.startsWith("tt")) return@LaunchedEffect
        val found = graph.tmdb.findByImdb(meta.id.substringBefore(':'), preferTv = meta.type == "series") ?: return@LaunchedEffect
        tmdbCredits = graph.tmdb.credits(found.first, found.second)
    }
    val item: LibraryItem? = library[meta.id]
    val isSeries = meta.type == "series" && (full?.videos?.isNotEmpty() ?: true)
    var sources by remember { mutableStateOf<SourcesState>(SourcesState.Idle) }
    val playReq = remember { FocusRequester() }
    val sourcesReq = remember { FocusRequester() }
    val detailsReq = remember { FocusRequester() }
    val showSourcesPanel = settings.sourcesOnInfoPage || o.showSources
    // The panel beside the title: episodes for a series or sources for a film, then the details.
    val tabs = buildList { if (isSeries) add(PanelTab.EPISODES) else if (showSourcesPanel) add(PanelTab.SOURCES); add(PanelTab.DETAILS) }
    var tab by remember(meta.id) { mutableStateOf<PanelTab?>(null) }
    val shownTab = tab?.takeIf { it in tabs } ?: tabs.first()
    // Series open with their episodes shown; films start with the panel closed.
    val panelByDefault = o.showSources || meta.type == "series"
    var panelOpen by remember(meta.id) { mutableStateOf(panelByDefault) }
    var panelFocused by remember { mutableStateOf(false) }
    val tabReq = remember { FocusRequester() }

    fun loadSources() {
        val vid = meta.behaviorHints.defaultVideoId ?: meta.id
        sources = SourcesState.Loading
        scope.launch {
            val addons = session.effectiveAddons()
            val results = graph.addonClient.allStreams(addons, meta.type, vid)
            val cands = results.flatMapIndexed { order, (a, r) ->
                (r as? AddonResult.Ok)?.value?.streams.orEmpty().mapIndexed { i, s -> Candidate(s, a.manifest.id, a.manifest.name, order, i) }
            }
            val mem = session.titleMemory(meta.id)
            val prefs = com.vyrk.core.stream.RankPrefs(
                preset = settings.preset, maxResolution = settings.maxResolution, maxSizeBytes = settings.maxSizeGb?.let { it * 1_000_000_000L },
                format = settings.format, hdr = settings.hdr, audioLanguages = settings.audioLanguages,
                rememberedKey = mem.pickedKey, lastBingeGroup = mem.bingeGroup, lastReleaseGroup = mem.releaseGroup,
                runtimeMinutes = RuntimeText.minutes(full?.runtime),
            )
            sources = SourcesState.Ready(StreamRanker.rank(cands, graph.device.caps(graph.deviceSettings.value.measuredBps), prefs), results.count { it.second is AddonResult.Failed }, results.size)
        }
    }
    // Focus moves once the tab's content has been laid out.
    fun focusSoon(req: FocusRequester) = scope.launch { repeat(2) { androidx.compose.runtime.withFrameNanos { } }; runCatching { req.requestFocus() } }
    fun toDetails() { panelOpen = true; tab = PanelTab.DETAILS; focusSoon(detailsReq) }
    LaunchedEffect(meta.id) {
        if (!isSeries && settings.loadSourcesOnOpen && showSourcesPanel) loadSources()
        if (o.showSources) {
            delay(80)
            if (!isSeries && sources is SourcesState.Idle) loadSources()
            focusSoon(tabReq)
        } else runCatching { playReq.requestFocus() }
    }

    // Back with the panel open closes it and returns to Play. Back reaches dialogs through the back
    // dispatcher on Android 13+, not as a key event, so this has to be a BackHandler (it runs
    // before the window's own Back, which closes the page).
    // In the panel, Back returns to Play; a panel the info button opened on a film also closes.
    androidx.activity.compose.BackHandler(enabled = panelFocused || (panelOpen && !panelByDefault)) {
        runCatching { playReq.requestFocus() }
        if (!panelByDefault) panelOpen = false
    }

    val c = Vyrk.colors
    val art = rememberTitleArt(meta, full, metadataLoading = details.loading)
    val name = full?.name?.takeIf { it.isNotBlank() } ?: meta.name
    val synopsis = full?.description ?: meta.description ?: ""
    Box(Modifier.fillMaxSize().background(c.shade)) {
        androidx.compose.runtime.key(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id)) { TitleBackdrop(art.backdrops) }
        // Clear of the clock in the top corner.
        Row(Modifier.fillMaxSize().padding(start = Dimens.screenMargin, end = Dimens.screenMargin, top = 60.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // ---- The title ----
            Column(Modifier.width(PAGE_TEXT_W).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.Bottom) {
                // Fixed slots throughout, so nothing moves while the details and cast load.
                com.vyrk.tv.ui.browse.TitleEyebrow(meta)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(LOGO_W).height(LOGO_H), contentAlignment = Alignment.BottomStart) {
                    HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), name, art.logos, LOGO_W, LOGO_H, Vyrk.type.cinematicL, Modifier.fillMaxHeight(), loading = art.logosLoading)
                }
                Spacer(Modifier.height(14.dp))
                TitleFacts(meta, full)
                Spacer(Modifier.height(10.dp))
                Text(synopsis, style = Vyrk.type.bodyL, color = c.text.copy(alpha = 0.92f), minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                PeopleChips(tmdbCredits, full, onMore = { toDetails() })
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val inCw = item?.isInContinueWatching == true
                    // The flagship action, as on Home: a glowing pill; a series names the episode it resumes.
                    val resumeEp = if (inCw && isSeries) episodeLabel(item?.state?.videoId) else null
                    VyrkButton(str(if (inCw) R.string.resume else R.string.play) + (resumeEp?.let { "  ·  $it" } ?: ""), icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                        height = ACTION_H + 2.dp, horizontalPadding = 24.dp, glow = true, shape = Dimens.pillShape,
                        modifier = Modifier.focusRequester(playReq).traceFocus("info:play"), onClick = {
                            val ranked = (sources as? SourcesState.Ready)?.ranked
                            Playback.start(activity, graph, session, meta, ranked = if (isSeries) null else ranked, origin = o.origin)
                        })
                    VyrkButton(str(R.string.trailer), icon = R.drawable.ic_trailer, height = ACTION_H, horizontalPadding = 18.dp, style = ButtonStyle.OUTLINE, shape = Dimens.pillShape,
                        modifier = Modifier.traceFocus("info:trailer"), onClick = { closeOverlay(app, o); app.preview = meta })
                    val inLib = item?.inLibrary == true
                    VyrkIconButton(if (inLib) R.drawable.ic_check else R.drawable.ic_plus, str(if (inLib) R.string.in_library else R.string.add_to_library),
                        onClick = { if (inLib) session.removeFromLibrary(meta.id) else session.addToLibrary(meta) },
                        size = ACTION_H, modifier = Modifier.traceFocus("info:library"))
                    val watched = item?.watched == true
                    VyrkIconButton(R.drawable.ic_eye, str(if (watched) R.string.mark_unwatched else R.string.mark_watched),
                        onClick = { session.markWatched(meta, !watched) }, size = ACTION_H, active = watched, modifier = Modifier.traceFocus("info:mark_watched"))
                    // Opens and closes the panel; it stays lit while the panel is open.
                    VyrkIconButton(R.drawable.ic_info, str(R.string.more), onClick = { panelOpen = !panelOpen }, size = ACTION_H, active = panelOpen,
                        // Right goes on into the open panel, not up to the people above.
                        modifier = Modifier.focusProperties { right = if (!panelOpen) FocusRequester.Cancel else if (tabs.size > 1) tabReq else detailsReq }.traceFocus("info:more"))
                }
                if (item?.isInContinueWatching == true && item.progress > 0f) {
                    Spacer(Modifier.height(12.dp))
                    ResumeLine(item)
                }
            }
            // ---- The panel: episodes or sources, and the details ----
            if (panelOpen) Column(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged {
                        panelFocused = it.hasFocus
                        if (it.hasFocus && !isSeries && showSourcesPanel && sources is SourcesState.Idle) loadSources()
                    }
                    .focusGroup()
                    .glassPanel(RoundedCornerShape(20.dp)).padding(start = 14.dp, end = 14.dp, top = 12.dp),
            ) {
                if (tabs.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                        tabs.forEach { t ->
                            SeasonTab(str(t.label), selected = t == shownTab, onClick = { tab = t }, trace = "info:tab:${t.name.lowercase()}",
                                modifier = if (t == shownTab) Modifier.focusRequester(tabReq) else Modifier)
                        }
                    }
                    com.vyrk.tv.ui.components.RuneDivider(Modifier.padding(top = 8.dp, bottom = 10.dp).fillMaxWidth(), centered = false)
                }
                when (shownTab) {
                    PanelTab.EPISODES -> SeasonGuide(full, item, sstate.newEpisodes[meta.id].orEmpty(), settings.blurUnwatched, onPlay = { v ->
                        Playback.start(activity, graph, session, meta, videoId = v.id, origin = o.origin)
                    }, onMark = { videos, watched -> full?.let { session.markVideos(it, videos, watched) } })
                    PanelTab.SOURCES -> SourcesPane(sources, sourcesReq, onRefresh = { loadSources() }, onPick = { r ->
                        Playback.start(activity, graph, session, meta, picked = r, ranked = (sources as? SourcesState.Ready)?.ranked, origin = o.origin)
                    })
                    PanelTab.DETAILS -> Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 2.dp, end = 2.dp, bottom = 16.dp)) {
                        TitleDetails(synopsis, full, item, tmdbCredits, detailsReq, onWatched = { session.markWatched(meta, item?.watched != true) })
                    }
                }
            }
        }
        Clock(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = Dimens.screenMargin))
    }
}

/** The tabs of the title page's panel. */
private enum class PanelTab(val label: Int) { EPISODES(R.string.episodes), SOURCES(R.string.sources), DETAILS(R.string.details) }

/**
 * The title's own artwork across the whole screen: dark on the left under the text and along the
 * bottom under the buttons, clear on the right. The first backdrop that loads is kept while the
 * title's details arrive; the next candidate is tried if one fails.
 */
@Composable
private fun TitleBackdrop(urls: List<String>) {
    val shade = Vyrk.colors.shade
    var failed by remember { mutableStateOf(emptySet<String>()) }
    var shown by remember { mutableStateOf<String?>(null) }
    val url = shown?.takeIf { it !in failed } ?: urls.firstOrNull { it !in failed }
    Box(Modifier.fillMaxSize()) {
        Crossfade(url, animationSpec = com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.SLOW), label = "info-backdrop") { u ->
            if (u != null) AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onSuccess = { if (shown == null) shown = u }, onError = { failed = failed + u })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to shade.copy(alpha = 0.92f), 0.30f to shade.copy(alpha = 0.78f), 0.55f to shade.copy(alpha = 0.28f), 0.78f to Color.Transparent,
        )))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to shade.copy(alpha = 0.50f), 0.18f to Color.Transparent, 0.50f to Color.Transparent, 1f to shade.copy(alpha = 0.95f),
        )))
        // A soft obsidian pool low on the left, where the title and actions sit.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(shade.copy(alpha = 0.55f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY), radius = 1400f)))
        com.vyrk.tv.ui.components.RunePillar(Modifier.offset(x = 17.dp).padding(top = 72.dp, bottom = 28.dp))
    }
}

/** Certificate in a box (when the add-on gives one) · year · genre · runtime, then the IMDb rating. */
@Composable
private fun TitleFacts(meta: MetaPreview, full: MetaItem?) {
    val c = Vyrk.colors
    val cert = full?.certification?.takeIf { it.isNotBlank() }
    val seasonCount = full?.videos?.mapNotNull { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 && meta.type == "series" }
    val parts = listOfNotNull(
        (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() },
        (full?.allGenres ?: meta.genres).take(2).joinToString(" / ").takeIf { it.isNotBlank() },
        seasonCount?.let { n -> if (n == 1) str(R.string.season_n, 1) else str(R.string.seasons_count, n) },
        RuntimeText.format(full?.runtime ?: meta.runtime),
    )
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cert != null) Box(Modifier.border(1.dp, c.textSecondary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(cert, style = Vyrk.type.labelM, color = c.textSecondary, maxLines = 1)
        }
        parts.forEachIndexed { i, p ->
            if (i > 0 || cert != null) Text("·", style = Vyrk.type.titleM, color = c.textMuted)
            Text(p, style = Vyrk.type.titleM, color = c.textSecondary, maxLines = 1)
        }
        val rating = full?.imdbRating ?: meta.imdbRating
        if (!rating.isNullOrBlank()) Tag("IMDb $rating", color = c.background, background = c.gold)
    }
}

private data class PersonRef(val name: String, val photo: String?, val personId: Long?)

private val PEOPLE_ROW_W = PAGE_TEXT_W
private val PEOPLE_GAP = 10.dp

/**
 * The director and the first actors as chips with their photos (TMDB, when the build has a key;
 * names only otherwise), as many as fit, then "+N more" for the rest. The row keeps its height
 * while credits load.
 */
@Composable
private fun PeopleChips(credits: Credits?, full: MetaItem?, onMore: () -> Unit) {
    val app = LocalApp.current
    val people = if (credits != null && (credits.cast.isNotEmpty() || credits.keyCrew.isNotEmpty())) {
        val director = credits.keyCrew.firstOrNull { it.role?.contains("Director", ignoreCase = true) == true }
        (listOfNotNull(director) + credits.cast).distinctBy { it.personId }.map { PersonRef(it.name, it.profilePath, it.personId) }
    } else {
        (full?.directorNames.orEmpty().take(1) + full?.castNames.orEmpty()).distinct().map { PersonRef(it, null, null) }
    }
    val onPerson: (PersonRef) -> Unit = { p ->
        val id = p.personId
        if (id != null) app.push(Overlay.Person(id, p.name)) else { app.overlays.clear(); app.searchQuery = p.name; app.section = Section.SEARCH }
    }
    // Wider than the text column (the artwork is clear there), measured so no chip is squeezed.
    SubcomposeLayout(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(PEOPLE_ROW_W).height(CHIP_H + 6.dp)) { constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val gap = PEOPLE_GAP.roundToPx()
        val chips = people.take(4).mapIndexed { i, p ->
            subcompose("person$i") { PersonChip(p.name, p.photo, "info:person#$i", showPhoto = true) { onPerson(p) } }.first().measure(loose)
        }
        // Room kept for "+N more", measured at the widest number it could show.
        val moreWidth = if (people.size > 1) subcompose("more-probe") { PersonChip(str(R.string.more_count, people.size), null, "", showPhoto = false) {} }.first().measure(loose).width else 0
        var used = 0
        var fit = 0
        for (chip in chips) {
            val add = chip.width + if (fit > 0) gap else 0
            val reserve = if (people.size - (fit + 1) > 0) gap + moreWidth else 0
            if (used + add + reserve > constraints.maxWidth) break
            used += add; fit++
        }
        val rest = people.size - fit
        val more = if (rest > 0) subcompose("more") { PersonChip(str(R.string.more_count, rest), null, "info:people_more", showPhoto = false, onClick = onMore) }.first().measure(loose) else null
        layout(constraints.maxWidth, constraints.maxHeight) {
            var x = 0
            for (chip in chips.take(fit) + listOfNotNull(more)) {
                chip.placeRelative(x, (constraints.maxHeight - chip.height) / 2)
                x += chip.width + gap
            }
        }
    }
}

@Composable
private fun PersonChip(name: String, photo: String?, trace: String, showPhoto: Boolean, onClick: () -> Unit) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.height(CHIP_H).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(CHIP_H / 2)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.shade.copy(alpha = 0.55f), contentColor = c.text,
            focusedContainerColor = c.text, focusedContentColor = c.background,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, c.text.copy(alpha = 0.16f))), focusedBorder = Border.None),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = if (showPhoto) 4.dp else 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showPhoto) PersonPhoto(name, photo, CHIP_H - 8.dp)
            Text(name, style = Vyrk.type.labelL, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The panel's details: full synopsis, Mark watched, credits and the whole cast. */
@Composable
private fun TitleDetails(synopsis: String, full: MetaItem?, item: LibraryItem?, credits: Credits?, firstReq: FocusRequester, onWatched: () -> Unit) {
    val app = LocalApp.current
    val c = Vyrk.colors
    if (synopsis.isNotBlank()) Text(synopsis, style = Vyrk.type.bodyL, color = c.text.copy(alpha = 0.86f), maxLines = 10, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(12.dp))
    WrapRow {
        VyrkButton(str(if (item?.watched == true) R.string.mark_unwatched else R.string.mark_watched), icon = R.drawable.ic_check,
            modifier = Modifier.focusRequester(firstReq).traceFocus("info:watched"), onClick = onWatched)
    }
    Spacer(Modifier.height(14.dp))
    full?.directorNames?.takeIf { it.isNotEmpty() }?.let { Credit(str(R.string.director), it.joinToString(", ")) }
    full?.writerNames?.takeIf { it.isNotEmpty() }?.let { Credit(str(R.string.writers), it.take(4).joinToString(", ")) }
    full?.country?.takeIf { it.isNotBlank() }?.let { Credit(str(R.string.country), it) }
    val cast = full?.castNames.orEmpty()
    if (credits != null && (credits.cast.isNotEmpty() || credits.keyCrew.isNotEmpty())) {
        Spacer(Modifier.height(8.dp))
        com.vyrk.tv.ui.components.SectionHeading(str(R.string.cast_and_crew), active = true, rule = true)
        CastCrewRow(credits.cast, credits.keyCrew)
    } else if (cast.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        com.vyrk.tv.ui.components.SectionHeading(str(R.string.starring_label), active = true)
        Spacer(Modifier.height(6.dp))
        WrapRow(spacing = 6.dp) {
            cast.take(20).forEach { person ->
                VyrkChip(person, false, onClick = { app.overlays.clear(); app.searchQuery = person; app.section = Section.SEARCH }, modifier = Modifier.traceFocus("info:cast"))
            }
        }
    }
    full?.imdbRating?.let { Spacer(Modifier.height(10.dp)); Text(str(R.string.rating_attribution), style = Vyrk.type.labelS, color = c.textMuted) }
}

/** The time, top right, as on the player. */
@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    Text(fmt.format(java.util.Date(now)), style = Vyrk.type.headlineS, color = Vyrk.colors.text.copy(alpha = 0.9f), modifier = modifier)
}

@Composable
private fun Credit(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label.uppercase() + "   ", style = Vyrk.type.eyebrow.copy(letterSpacing = Vyrk.type.eyebrow.letterSpacing * 0.6f), color = Vyrk.colors.gold.copy(alpha = 0.85f), modifier = Modifier.padding(top = 1.dp))
        Text(value, style = Vyrk.type.bodyS, color = Vyrk.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourcesPane(state: SourcesState, firstReq: FocusRequester, onRefresh: () -> Unit, onPick: (Ranked) -> Unit) {
    val c = Vyrk.colors
    var filter by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.vyrk.tv.ui.components.RuneGlyph(10.dp, color = c.gold)
        Spacer(Modifier.width(8.dp))
        Text(str(R.string.ready_when_you_are).uppercase(), style = Vyrk.type.eyebrow, color = c.gold, maxLines = 1)
    }
    Spacer(Modifier.height(4.dp))
    val ready = state as? SourcesState.Ready
    val playable = ready?.ranked?.filter { it.playable }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(str(R.string.streams_count, playable.size), style = Vyrk.type.cinematicS, color = c.text)
            Text(str(R.string.sources_available, ready?.ranked?.size ?: 0), style = Vyrk.type.bodyS, color = c.textSecondary)
        }
        VyrkIconButton(R.drawable.ic_refresh, str(R.string.refresh), onRefresh, size = 34.dp, modifier = Modifier.traceFocus("sources:refresh"))
    }
    Spacer(Modifier.height(10.dp))
    when {
        state is SourcesState.Idle -> Text(str(R.string.press_sources), style = Vyrk.type.bodyM, color = c.textMuted)
        state is SourcesState.Loading -> Text(str(R.string.finding_sources), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null && ready.addonCount == 0 -> Text(str(R.string.no_stream_addons), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null && ready.ranked.isEmpty() && ready.failedAddons > 0 -> Text(str(R.string.sources_addons_failed), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null && ready.ranked.isEmpty() -> Text(str(R.string.no_sources_found), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null -> {
            val langs = playable.flatMap { it.info.languages }.distinct()
            val filters = listOf("all", "4K", "1080p", "720p", "480p") + langs
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filters) { f ->
                    VyrkChip(if (f == "all") str(R.string.filter_all) else if (f in langs) Languages.name(f) else f, filter == f, { filter = f }, Modifier.traceFocus("sources:filter:$f"))
                }
            }
            Spacer(Modifier.height(10.dp))
            val shown = ready.ranked.filter { r ->
                when (filter) {
                    "all" -> true
                    "4K" -> (r.info.resolution ?: 0) >= 2160
                    "1080p" -> r.info.resolution == 1080
                    "720p" -> r.info.resolution == 720
                    "480p" -> (r.info.resolution ?: 0) in 1..576
                    else -> filter in r.info.languages
                }
            }
            val topKey = playable.firstOrNull()?.candidate?.key
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.candidate.addonId + it.candidate.index }) { r ->
                    val k = r.candidate.addonId + r.candidate.index
                    Column {
                        if (r.playable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    InfoSourceCard(r, recommended = r.candidate.key == topKey, modifier = if (r.candidate.key == topKey) Modifier.focusRequester(firstReq) else Modifier) { onPick(r) }
                                }
                                Spacer(Modifier.width(6.dp))
                                VyrkIconButton(R.drawable.ic_info, str(R.string.source_details), { expanded = if (expanded == k) null else k }, size = 30.dp, modifier = Modifier.traceFocus("sources:details"))
                            }

                            if (expanded == k) {
                                Text(r.candidate.stream.text, style = Vyrk.type.bodyS, color = c.textSecondary)
                                Text(r.reasons.joinToString("  ") { "${it.code} ${if (it.points > 0) "+" else ""}${it.points}" } + "  = ${r.score}", style = Vyrk.type.labelS, color = c.textMuted)
                            }
                        } else {
                            Text("${r.candidate.addonName} · ${r.info.resolutionLabel.ifEmpty { "—" }} — ${exclusionText(r)}", style = Vyrk.type.bodyS, color = c.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonGuide(meta: MetaItem?, item: LibraryItem?, newIds: Set<String>, blurUnwatched: Boolean, onPlay: (Video) -> Unit, onMark: (List<Video>, Boolean) -> Unit) {
    val c = Vyrk.colors
    if (meta == null) { Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textMuted); return }
    val seasons = meta.videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
    val wb = item?.watchedBitField(meta.videos)
    val now = System.currentTimeMillis()
    fun releasedAt(v: Video) = v.released?.let { LibraryItem.parseTime(kotlinx.serialization.json.JsonPrimitive(it)) }
    // Up next: the episode in progress, else the first aired, unwatched episode after the last watched one.
    val upNextId = remember(meta.id, item, wb) {
        val ordered = meta.videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        item?.state?.videoId?.takeIf { id -> item.isInContinueWatching && ordered.any { it.id == id } }
            ?: run {
                val last = ordered.indexOfLast { wb?.getVideo(it.id) == true }
                if (last < 0) null else ordered.drop(last + 1).firstOrNull { wb?.getVideo(it.id) != true && (releasedAt(it) ?: 0L) <= now }?.id
            }
    }
    val resumeSeason = item?.state?.videoId?.let { id -> meta.videos.firstOrNull { it.id == id }?.season }
    val upNextSeason = upNextId?.let { id -> meta.videos.firstOrNull { it.id == id }?.season }
    var season by remember(meta.id) { mutableStateOf(resumeSeason ?: upNextSeason ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val eps = meta.videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    // Seasons as tabs: the open one is dark steel with a gold rune and edge; remote focus is frosted cyan.
    // Up from the episodes lands on the open season, kept in view, not on whichever tab sits above.
    val seasonReq = remember { FocusRequester() }
    val seasonRow = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(meta.id) { seasons.indexOf(season).takeIf { it > 0 }?.let { seasonRow.scrollToItem(it) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f).focusProperties { onEnter = { runCatching { seasonReq.requestFocus() } } }.focusGroup(),
            state = seasonRow, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
        ) {
            items(seasons) { s ->
                SeasonTab(if (s == 0) str(R.string.specials) else str(R.string.season_n, s), selected = s == season, onClick = { season = s }, trace = "season:$s",
                    modifier = if (s == season) Modifier.focusRequester(seasonReq) else Modifier)
            }
        }
        Spacer(Modifier.width(10.dp))
        VyrkButton(str(R.string.mark_season_watched), icon = R.drawable.ic_check, style = ButtonStyle.OUTLINE, height = 32.dp, horizontalPadding = 12.dp,
            shape = Dimens.pillShape, modifier = Modifier.traceFocus("season:mark"), onClick = { onMark(eps, true) })
    }
    Spacer(Modifier.height(10.dp))
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Open a season at its next episode, so it is the first thing in view.
    LaunchedEffect(season, upNextId) {
        val i = eps.indexOfFirst { it.id == upNextId }
        if (i >= 0) listState.scrollToItem(i) else listState.scrollToItem(0)
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 24.dp)) {
        items(eps, key = { it.id }) { v ->
            val watched = wb?.getVideo(v.id) == true
            val released = releasedAt(v)
            val future = released != null && released > now
            val progress = if (item?.state?.videoId == v.id && item.isInContinueWatching) item.progress else 0f
            val date = released?.let { (if (future) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)).format(java.util.Date(it)) }
            Column {
                EpisodeCard(
                    v, watched = watched, progress = progress, upNext = v.id == upNextId, isNew = v.id in newIds, future = future,
                    date = date?.let { if (future) str(R.string.airs_on, it) else it }, blur = blurUnwatched && !watched,
                    onClick = { if (!future) onPlay(v) }, onMenu = { menuFor = if (menuFor == v.id) null else v.id },
                )
                if (menuFor == v.id) WrapRow(modifier = Modifier.padding(top = 6.dp, start = 4.dp), spacing = 6.dp) {
                    VyrkButton(str(if (watched) R.string.mark_unwatched else R.string.mark_watched), onClick = { onMark(listOf(v), !watched); menuFor = null })
                    VyrkButton(str(R.string.mark_previous_watched), onClick = { onMark(LibraryRules.videosUpTo(meta, v), true); menuFor = null })
                    VyrkButton(str(R.string.mark_season_unwatched), onClick = { onMark(eps, false); menuFor = null })
                }
            }
        }
    }
}

/** A season tab. Selected and focused look different, so the open season is never mistaken for focus. */
@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit, trace: String, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.steel.copy(alpha = 0.9f) else Color.Transparent, contentColor = if (selected) c.text else c.textSecondary,
            focusedContainerColor = c.accent.copy(alpha = 0.24f), focusedContentColor = c.text,
            pressedContainerColor = c.accent.copy(alpha = 0.4f), pressedContentColor = c.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, c.gold.copy(alpha = 0.55f))) else Border(BorderStroke(1.dp, c.text.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(1.5.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(c.accent.copy(alpha = 0.35f), 8.dp)),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (selected) com.vyrk.tv.ui.components.RuneGlyph(9.dp, color = c.gold)
            Text(label, style = Vyrk.type.labelL, maxLines = 1)
        }
    }
}

/**
 * One episode: a large still (its number in gold when there is none) with progress and a watched
 * mark, then "S1 · E3", the title, air date and runtime, and the synopsis. The next episode to
 * watch is lit with the accent and labelled. Hold OK or Menu opens the watched actions.
 */
@Composable
private fun EpisodeCard(
    v: Video, watched: Boolean, progress: Float, upNext: Boolean, isNew: Boolean, future: Boolean, date: String?, blur: Boolean,
    onClick: () -> Unit, onMenu: () -> Unit,
) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick, onLongClick = onMenu,
        modifier = Modifier.fillMaxWidth().traceFocus("episode:${v.id}")
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (upNext) c.accent.copy(alpha = 0.08f) else c.panel.copy(alpha = 0.55f), contentColor = c.text,
            focusedContainerColor = c.raised.copy(alpha = 0.95f), focusedContentColor = c.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (upNext) c.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.06f))),
            focusedBorder = Border(BorderStroke(1.5.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.02f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(c.accent.copy(alpha = 0.30f), 10.dp)),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(144.dp, 81.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(c.steel, c.panel)))) {
                Text("E${v.episode ?: ""}", style = Vyrk.type.cinematicM, color = c.gold.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.Center))
                if (v.thumbnail != null) AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (blur && Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier))
                if (watched || future) Box(Modifier.fillMaxSize().background(c.shade.copy(alpha = if (future) 0.55f else 0.35f)))
                if (watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(c.gold, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    com.vyrk.tv.ui.components.Icon(R.drawable.ic_check, null, Modifier.size(11.dp), tint = c.background)
                }
                if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listOfNotNull(v.season?.let { "S$it" }, v.episode?.let { "E$it" }).joinToString("  ·  "), style = Vyrk.type.eyebrow, color = c.gold, maxLines = 1)
                    if (upNext) Tag(str(R.string.next_episode), color = c.onAccent, background = c.accent)
                    if (isNew) Tag(str(R.string.badge_new), color = c.onAccent, background = c.accent)
                }
                Spacer(Modifier.height(3.dp))
                Text(v.displayTitle, style = Vyrk.type.titleM, color = if (watched) c.textSecondary else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(date, RuntimeText.format(v.runtime), if (watched) str(R.string.watched) else null)
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = Vyrk.type.labelM, color = c.textMuted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                val syn = v.synopsis
                if (!syn.isNullOrBlank()) Text(if (blur) str(R.string.hidden_synopsis) else syn, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * A source on the title page: its quality as a block (gold from 4K), the add-on, the release
 * details and languages. The recommended source is lit with the accent. Same trace as the
 * player's source list.
 */
@Composable
private fun InfoSourceCard(r: Ranked, recommended: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Vyrk.colors
    val i = r.info
    val parts = listOf(i.releaseType.label, i.codec.label, i.hdr.joinToString("/") { it.label }, i.audio.joinToString(" ") { it.label }, i.channels.orEmpty(),
        i.sizeBytes?.let { "%.1f GB".format(it / 1e9) }.orEmpty()).filter { it.isNotBlank() }
    val langs = i.languages.joinToString(", ") { Languages.label(it) }
    val uhd = (i.resolution ?: 0) >= 2160
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().traceFocus("source:${r.candidate.addonId}:${r.candidate.index}"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (recommended) c.accent.copy(alpha = 0.10f) else c.raised.copy(alpha = 0.55f), contentColor = c.text,
            focusedContainerColor = c.raised.copy(alpha = 0.95f), focusedContentColor = c.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (recommended) c.accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f))),
            focusedBorder = Border(BorderStroke(1.5.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.02f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(c.accent.copy(alpha = 0.30f), 10.dp)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(62.dp).height(38.dp).background(c.shade.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .border(1.dp, if (uhd) c.gold.copy(alpha = 0.8f) else c.text.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(i.resolutionLabel.ifEmpty { "—" }, style = Vyrk.type.titleS, color = if (uhd) c.gold else c.text, maxLines = 1) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.candidate.addonName, style = Vyrk.type.titleS, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (recommended) Tag(str(R.string.recommended), color = c.onAccent, background = c.accent)
                }
                Text(parts.joinToString("  ·  ").ifEmpty { "—" }, style = Vyrk.type.labelM, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (langs.isNotEmpty()) Text(langs, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "S1 E3" from a series video id (tt…:1:3), or null. */
private fun episodeLabel(videoId: String?): String? = videoId?.split(':')?.takeIf { it.size >= 3 }?.let { p ->
    val s = p[p.size - 2].toIntOrNull(); val e = p.last().toIntOrNull()
    if (s != null && e != null) "S$s E$e" else null
}

/** Where a started title stands: a slim accent bar and "S1 E3 · 42m left". */
@Composable
private fun ResumeLine(item: LibraryItem) {
    val c = Vyrk.colors
    val st = item.state
    val leftMin = if (st.duration > 0 && st.duration > st.timeOffset) ((st.duration - st.timeOffset) / 60_000L).toInt() else null
    val left = leftMin?.takeIf { it > 0 }?.let { m -> str(R.string.time_left, if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(180.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(item.progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
        }
        val label = listOfNotNull(episodeLabel(st.videoId), left).joinToString("  ·  ")
        if (label.isNotEmpty()) Text(label, style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1)
    }
}
