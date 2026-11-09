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

private val LOGO_W = 400.dp
private val LOGO_H = 120.dp
private val PAGE_TEXT_W = 540.dp
private val CHIP_H = 34.dp
private val ACTION_H = 46.dp

/**
 * The title page, full screen. Page one is the title over its own artwork: logo, facts, synopsis,
 * people and Play / Trailer / library / more. Down (or the more button) slides up page two:
 * episodes for a series or sources for a film, beside the details, credits and cast. Back on page
 * two returns to page one; Back on page one closes the page.
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
    val full by com.vyrk.tv.ui.browse.produceMeta(session, meta)
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
    var page by remember { mutableIntStateOf(0) }

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
    fun toDetails() {
        if (!isSeries && showSourcesPanel && sources is SourcesState.Idle) loadSources()
        runCatching { detailsReq.requestFocus() }
    }
    LaunchedEffect(meta.id) {
        if (!isSeries && settings.loadSourcesOnOpen && showSourcesPanel) loadSources()
        if (o.showSources) { delay(80); toDetails() } else runCatching { playReq.requestFocus() }
    }

    // Back on page two returns to page one. Back reaches dialogs through the back dispatcher on
    // Android 13+, not as a key event, so this has to be a BackHandler (it runs before the
    // window's own Back, which closes the page).
    androidx.activity.compose.BackHandler(enabled = page == 1) { runCatching { playReq.requestFocus() } }

    val c = Vyrk.colors
    val art = rememberTitleArt(meta, full)
    val name = full?.name?.takeIf { it.isNotBlank() } ?: meta.name
    val synopsis = full?.description ?: meta.description ?: ""
    BoxWithConstraints(Modifier.fillMaxSize().background(c.shade)) {
        val pageH = maxHeight
        val offset by animateDpAsState(if (page == 1) -pageH else 0.dp, if (settings.reduceMotion) snap() else tween(320), label = "info-page")
        androidx.compose.runtime.key(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id)) { TitleBackdrop(art.backdrops) }
        // Page two darkens the artwork as it slides up over it.
        val dim = if (pageH.value > 0f) (-offset.value / pageH.value).coerceIn(0f, 1f) else 0f
        if (dim > 0f) Box(Modifier.fillMaxSize().background(c.shade.copy(alpha = 0.86f * dim)))
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Column(Modifier.fillMaxWidth().offset(y = offset).wrapContentHeight(Alignment.Top, unbounded = true).height(pageH * 2)) {
                // ---- Page one: the title, full screen ----
                Box(Modifier.fillMaxWidth().height(pageH).onFocusChanged { if (it.hasFocus) page = 0 }) {
                    Column(Modifier.align(Alignment.BottomStart).padding(start = Dimens.screenMargin, bottom = 44.dp).width(PAGE_TEXT_W)) {
                        // Fixed slots throughout, so nothing moves while the details and cast load.
                        Box(Modifier.width(LOGO_W).height(LOGO_H), contentAlignment = Alignment.BottomStart) {
                            HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), name, art.logos, LOGO_W, LOGO_H, Vyrk.type.displayL, Modifier.fillMaxHeight())
                        }
                        Spacer(Modifier.height(18.dp))
                        TitleFacts(meta, full)
                        Spacer(Modifier.height(12.dp))
                        Text(synopsis, style = Vyrk.type.bodyL, color = c.text.copy(alpha = 0.92f), minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(16.dp))
                        PeopleChips(tmdbCredits, full, onMore = { toDetails() })
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val inCw = item?.isInContinueWatching == true
                            VyrkButton(str(if (inCw) R.string.resume else R.string.play), icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                                height = ACTION_H, horizontalPadding = 24.dp,
                                modifier = Modifier.focusRequester(playReq).traceFocus("info:play"), onClick = {
                                    val ranked = (sources as? SourcesState.Ready)?.ranked
                                    app.overlays.remove(o); o.origin?.let { app.restore = it }
                                    Playback.start(activity, graph, session, meta, ranked = if (isSeries) null else ranked, origin = o.origin)
                                })
                            VyrkButton(str(R.string.trailer), icon = R.drawable.ic_trailer, height = ACTION_H, horizontalPadding = 22.dp,
                                modifier = Modifier.traceFocus("info:trailer"), onClick = { closeOverlay(app, o); app.preview = meta })
                            val inLib = item?.inLibrary == true
                            VyrkIconButton(if (inLib) R.drawable.ic_check else R.drawable.ic_plus, str(if (inLib) R.string.in_library else R.string.add_to_library),
                                onClick = { if (inLib) session.removeFromLibrary(meta.id) else session.addToLibrary(meta) },
                                size = ACTION_H, modifier = Modifier.traceFocus("info:library"))
                            VyrkIconButton(R.drawable.ic_info, str(R.string.more), onClick = { toDetails() }, size = ACTION_H, modifier = Modifier.traceFocus("info:more"))
                        }
                    }
                }
                // ---- Page two: episodes or sources beside the details, credits and cast ----
                Box(
                    Modifier.fillMaxWidth().height(pageH)
                        .onFocusChanged {
                            if (it.hasFocus) {
                                page = 1
                                if (!isSeries && showSourcesPanel && sources is SourcesState.Idle) loadSources()
                            }
                        }
                        .focusGroup(),
                ) {
                    // Clear of the clock in the top corner.
                    Row(Modifier.fillMaxSize().padding(start = Dimens.screenMargin, end = Dimens.screenMargin, top = 64.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        val details: @Composable (Modifier) -> Unit = { m ->
                            Column(m.fillMaxHeight().verticalScroll(rememberScrollState())) {
                                TitleDetails(name, synopsis, full, item, tmdbCredits, detailsReq, onWatched = { session.markWatched(meta, item?.watched != true) })
                            }
                        }
                        when {
                            isSeries -> {
                                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                                    SeasonGuide(full, item, sstate.newEpisodes[meta.id].orEmpty(), settings.blurUnwatched, onPlay = { v ->
                                        app.overlays.remove(o); o.origin?.let { app.restore = it }
                                        Playback.start(activity, graph, session, meta, videoId = v.id, origin = o.origin)
                                    }, onMark = { videos, watched -> full?.let { session.markVideos(it, videos, watched) } })
                                }
                                details(Modifier.weight(0.42f))
                            }
                            showSourcesPanel -> {
                                details(Modifier.weight(0.48f))
                                Column(Modifier.weight(0.52f).fillMaxHeight()) {
                                    SourcesPane(sources, sourcesReq, onRefresh = { loadSources() }, onPick = { r ->
                                        app.overlays.remove(o); o.origin?.let { app.restore = it }
                                        Playback.start(activity, graph, session, meta, picked = r, ranked = (sources as? SourcesState.Ready)?.ranked, origin = o.origin)
                                    })
                                }
                            }
                            else -> details(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Clock(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = Dimens.screenMargin))
    }
}

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
            0f to shade.copy(alpha = 0.50f), 0.18f to Color.Transparent, 0.55f to Color.Transparent, 1f to shade.copy(alpha = 0.92f),
        )))
    }
}

/** Certificate in a box (when the add-on gives one) · year · genre · runtime, then the IMDb rating. */
@Composable
private fun TitleFacts(meta: MetaPreview, full: MetaItem?) {
    val c = Vyrk.colors
    val cert = full?.certification?.takeIf { it.isNotBlank() }
    val parts = listOfNotNull(
        (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() },
        (full?.allGenres ?: meta.genres).firstOrNull(),
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
        if (!rating.isNullOrBlank()) Tag("IMDb $rating", color = c.background, background = c.warning)
    }
}

private data class PersonRef(val name: String, val photo: String?, val personId: Long?)

private val PEOPLE_ROW_W = 640.dp
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
            containerColor = Color.Black.copy(alpha = 0.38f), contentColor = c.text,
            focusedContainerColor = c.text, focusedContentColor = c.background,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))), focusedBorder = Border.None),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = if (showPhoto) 4.dp else 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showPhoto) PersonPhoto(name, photo, CHIP_H - 8.dp)
            Text(name, style = Vyrk.type.labelL, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Page two's details column: name, full synopsis, Mark watched, credits and the whole cast. */
@Composable
private fun TitleDetails(name: String, synopsis: String, full: MetaItem?, item: LibraryItem?, credits: Credits?, firstReq: FocusRequester, onWatched: () -> Unit) {
    val app = LocalApp.current
    val c = Vyrk.colors
    Text(name, style = Vyrk.type.headlineM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    if (synopsis.isNotBlank()) Text(synopsis, style = Vyrk.type.bodyM, color = c.textSecondary, maxLines = 10, overflow = TextOverflow.Ellipsis)
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
        Text(str(R.string.cast_and_crew), style = Vyrk.type.labelL, color = c.textMuted)
        CastCrewRow(credits.cast, credits.keyCrew)
    } else if (cast.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(str(R.string.starring_label), style = Vyrk.type.labelL, color = c.textMuted)
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
        Text("$label  ", style = Vyrk.type.labelL, color = Vyrk.colors.textMuted)
        Text(value, style = Vyrk.type.bodyS, color = Vyrk.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourcesPane(state: SourcesState, firstReq: FocusRequester, onRefresh: () -> Unit, onPick: (Ranked) -> Unit) {
    val c = Vyrk.colors
    var filter by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf<String?>(null) }
    Text(str(R.string.ready_when_you_are), style = Vyrk.type.labelM, color = c.accent)
    val ready = state as? SourcesState.Ready
    val playable = ready?.ranked?.filter { it.playable }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(str(R.string.streams_count, playable.size), style = Vyrk.type.headlineM, color = c.text)
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
                                    SourceLine(r, current = false, modifier = if (r.candidate.key == topKey) Modifier.focusRequester(firstReq) else Modifier) { onPick(r) }
                                }
                                Spacer(Modifier.width(6.dp))
                                VyrkIconButton(R.drawable.ic_info, str(R.string.source_details), { expanded = if (expanded == k) null else k }, size = 30.dp, modifier = Modifier.traceFocus("sources:details"))
                            }
                            if (r.candidate.key == topKey) Tag(str(R.string.recommended), color = c.onAccent, background = c.accent, modifier = Modifier.padding(top = 4.dp))

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
    val resumeSeason = item?.state?.videoId?.let { id -> meta.videos.firstOrNull { it.id == id }?.season }
    var season by remember(meta.id) { mutableStateOf(resumeSeason ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1) }
    val wb = item?.watchedBitField(meta.videos)
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(seasons) { s -> VyrkChip(if (s == 0) str(R.string.specials) else str(R.string.season_n, s), s == season, { season = s }, Modifier.traceFocus("season:$s")) }
    }
    Spacer(Modifier.height(8.dp))
    val eps = meta.videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    VyrkButton(str(R.string.mark_season_watched), modifier = Modifier.traceFocus("season:mark"), onClick = { onMark(eps, true) })
    Spacer(Modifier.height(8.dp))
    val now = System.currentTimeMillis()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(eps, key = { it.id }) { v ->
            val watched = wb?.getVideo(v.id) == true
            val released = v.released?.let { LibraryItem.parseTime(kotlinx.serialization.json.JsonPrimitive(it)) }
            val future = released != null && released > now
            val progress = if (item?.state?.videoId == v.id && item.isInContinueWatching) item.progress else 0f
            val title = "S${v.season}E${v.episode} ${v.displayTitle}"
            val date = released?.let { (if (future) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)).format(java.util.Date(it)) }
            Column {
                androidx.tv.material3.Surface(
                    onClick = { if (!future) onPlay(v) }, onLongClick = { menuFor = if (menuFor == v.id) null else v.id },
                    modifier = Modifier.fillMaxWidth().traceFocus("episode:${v.id}")
                        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { menuFor = v.id; true } else false },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = c.raised.copy(alpha = 0.7f), focusedContainerColor = c.raised),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None),
                    glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = com.vyrk.tv.ui.components.focusGlow()),
                ) {
                    Row(Modifier.padding(8.dp)) {
                        Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(c.panel)) {
                            if (v.thumbnail != null) AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (blurUnwatched && !watched && Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier))
                            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(c.accent))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(title, style = Vyrk.type.titleS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (v.id in newIds) Tag(str(R.string.badge_new), color = c.onAccent, background = c.accent)
                                if (watched) Tag("✓ " + str(R.string.watched), color = c.background, background = c.success)
                            }
                            date?.let { Text(if (future) str(R.string.airs_on, it) else it, style = Vyrk.type.labelS, color = c.textMuted) }
                            val syn = v.synopsis
                            if (!syn.isNullOrBlank()) Text(if (blurUnwatched && !watched) str(R.string.hidden_synopsis) else syn, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (menuFor == v.id) WrapRow(modifier = Modifier.padding(top = 6.dp), spacing = 6.dp) {
                    VyrkButton(str(if (watched) R.string.mark_unwatched else R.string.mark_watched), onClick = { onMark(listOf(v), !watched); menuFor = null })
                    VyrkButton(str(R.string.mark_previous_watched), onClick = { onMark(LibraryRules.videosUpTo(meta, v), true); menuFor = null })
                    VyrkButton(str(R.string.mark_season_unwatched), onClick = { onMark(eps, false); menuFor = null })
                }
            }
        }
    }
}
