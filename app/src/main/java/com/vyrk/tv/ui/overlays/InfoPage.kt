package com.vyrk.tv.ui.overlays

import com.vyrk.tv.ui.components.focusHalo
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.RuntimeText
import com.vyrk.core.addon.Video
import com.vyrk.core.catalog.CardFacts
import com.vyrk.core.lang.Languages
import com.vyrk.core.library.EpisodeGuide
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.stream.Ranked
import com.vyrk.core.stream.SourceFilters
import com.vyrk.core.stream.SourceSummary
import com.vyrk.tv.R
import com.vyrk.tv.player.Playback
import com.vyrk.tv.player.exclusionText
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.browse.HeroLogo
import com.vyrk.tv.ui.browse.rememberCardFacts
import com.vyrk.tv.ui.browse.rememberTitleArt
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.VyrkIconButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vyrk.tv.ui.tryRequestFocus

private val LOGO_W = 400.dp
private val LOGO_H = 100.dp
private val HERO_TEXT_W = 470.dp
private val ACTION_H = 46.dp
/** The tab bar's height. */
internal val TABS_H = 48.dp
/** On the first stop, this much of the page below the hero shows: the tab bar and the top of its content. */
private val PEEK_H = 132.dp
/** On the second stop, the tab bar sits this far down, under the title's name. */
private val STOP2_TOP = 54.dp

/**
 * The title page: one full-width page over the title's own artwork, in two stops.
 *
 * First stop, the hero: logo (or the name in large type), year · genre · runtime, the certificate,
 * IMDb score and the picture and sound of the source VYRK would play, a synopsis of up to four
 * lines, then Play, Trailer, Library and Watched. Below it, the tab bar and the top of the open tab.
 *
 * Down from the buttons moves to the tab bar and the page slides up to its second stop: the tabs at
 * the top and the open tab filling the screen. Tabs open as focus reaches them: Episodes (series)
 * or Sources (films, when shown on the title page), Details, Cast & crew, Related and Extras (the
 * last three when there is something to show). Back from the tabs returns to Play; Back again
 * closes the page. The page stays open under the player and other pages, so focus comes back to
 * where it was.
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
    val tmdb = rememberTmdbDetails(graph, meta, full)
    val item: LibraryItem? = library[meta.id]
    val isSeries = meta.type == "series" && (full?.videos?.isNotEmpty() ?: true)
    var sources by remember { mutableStateOf<SourcesState>(SourcesState.Idle) }
    val playReq = remember { FocusRequester() }
    val sourcesReq = remember { FocusRequester() }
    val tabReq = remember { FocusRequester() }
    val showSourcesTab = settings.sourcesOnInfoPage || o.showSources
    val directorLabel = str(R.string.director)
    val writerLabel = str(R.string.writer)
    val people = remember(full, tmdb, directorLabel) { TitlePeople.of(full, tmdb, directorLabel, writerLabel) }
    val extras = remember(full, meta, tmdb) { TitleExtras.of(meta, full, tmdb) }
    val mainTrailer = full?.youtubeTrailerId ?: meta.trailerStreams.firstNotNullOfOrNull { it.ytId }
    val related = tmdb?.recommendations.orEmpty()
    val tabs = buildList {
        if (isSeries) add(InfoTab.EPISODES) else if (showSourcesTab) add(InfoTab.SOURCES)
        add(InfoTab.DETAILS)
        if (people.cast.isNotEmpty() || people.crew.isNotEmpty()) add(InfoTab.CAST)
        if (related.isNotEmpty()) add(InfoTab.RELATED)
        if (extras.any { it.key != mainTrailer }) add(InfoTab.EXTRAS)
    }
    val defaultTab = when { o.showSources && InfoTab.SOURCES in tabs -> InfoTab.SOURCES; isSeries -> InfoTab.EPISODES; else -> InfoTab.DETAILS }
    var tab by remember(meta.id) { mutableStateOf(defaultTab) }
    val shownTab = tab.takeIf { it in tabs } ?: defaultTab.takeIf { it in tabs } ?: tabs.first()
    var lowerFocused by remember { mutableStateOf(false) }
    var synopsisCut by remember(meta.id) { mutableStateOf(false) }
    var playingExtra by remember { mutableStateOf<TitleExtras.Extra?>(null) }

    fun loadSources() {
        sources = SourcesState.Loading
        scope.launch { sources = loadTitleSources(graph, session, meta, RuntimeText.minutes(full?.runtime)) }
    }
    // Focus moves once the tab's content has been laid out.
    fun focusSoon(req: FocusRequester) = scope.launch { repeat(2) { androidx.compose.runtime.withFrameNanos { } }; req.tryRequestFocus() }
    InstantStartEffect(graph, session, meta, sources, pageLoadsSources = !isSeries && settings.loadSourcesOnOpen && showSourcesTab)
    LaunchedEffect(meta.id) {
        if (!isSeries && settings.loadSourcesOnOpen && showSourcesTab) loadSources()
        if (o.showSources) {
            delay(80)
            if (!isSeries && sources is SourcesState.Idle) loadSources()
            focusSoon(tabReq)
        } else playReq.tryRequestFocus()
    }
    // A film's sources load when their tab opens, if they haven't already.
    LaunchedEffect(shownTab) { if (shownTab == InfoTab.SOURCES && sources is SourcesState.Idle) loadSources() }

    // Back from the tabs returns to Play (the page slides back to the hero). Back reaches dialogs
    // through the back dispatcher on Android 13+, not as a key event, so this has to be a
    // BackHandler (it runs before the window's own Back, which closes the page).
    androidx.activity.compose.BackHandler(enabled = lowerFocused && playingExtra == null) { playReq.tryRequestFocus() }
    // An extra playing full screen: Back closes it.
    androidx.activity.compose.BackHandler(enabled = playingExtra != null) { playingExtra = null }
    val context = androidx.compose.ui.platform.LocalContext.current

    val c = Vyrk.colors
    val art = rememberTitleArt(meta, full, metadataLoading = details.loading)
    val name = full?.name?.takeIf { it.isNotBlank() } ?: meta.name
    val synopsis = full?.description ?: meta.description ?: ""
    val facts = rememberCardFacts(meta, full)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.stage).onPreviewKeyEvent { e ->
        // While an extra plays the page keeps focus where it was and takes the remote: OK opens the
        // video in YouTube, the arrows do nothing, Back (the back dispatcher) closes it.
        val x = playingExtra ?: return@onPreviewKeyEvent false
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (ok && e.type == KeyEventType.KeyUp) com.vyrk.tv.ui.preview.openInYouTube(context, x.key)
        ok || e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight
    }) {
        val screenH = maxHeight
        val heroH = screenH - PEEK_H
        val contentH = screenH - STOP2_TOP - TABS_H
        val maxShift = heroH - STOP2_TOP
        // Two stops: the hero, or the tabs at the top. Read while laying out and drawing only.
        val shift = animateDpAsState(if (lowerFocused) maxShift else 0.dp, com.vyrk.tv.ui.theme.motionSpec(200), label = "info-stop")
        fun progress() = (shift.value / maxShift).coerceIn(0f, 1f)

        androidx.compose.runtime.key(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id)) {
            TitleBackdrop(art.backdrops, dim = { progress() })
        }
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(heroH + TABS_H + contentH)
                .offset { IntOffset(0, -shift.value.roundToPx()) },
        ) {
            // ---- The hero ----
            Box(Modifier.fillMaxWidth().height(heroH).graphicsLayer { alpha = 1f - progress() }) {
                Column(
                    Modifier.padding(start = Dimens.screenMargin, bottom = 22.dp).width(HERO_TEXT_W).fillMaxHeight().focusGroup(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    // Fixed slots for the logo and badges, so little moves while the details load.
                    Box(Modifier.width(LOGO_W).height(LOGO_H), contentAlignment = Alignment.BottomStart) {
                        HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), name, art.logos, LOGO_W, LOGO_H, heroTitleStyle(name), Modifier.fillMaxHeight(), loading = art.logosLoading)
                    }
                    Spacer(Modifier.height(14.dp))
                    TitleFacts(meta, full)
                    Spacer(Modifier.height(8.dp))
                    // Films: the picture and sound of the source VYRK would play, once sources are in.
                    val badges = if (isSeries) emptyList() else (sources as? SourcesState.Ready)?.ranked?.let { com.vyrk.core.stream.QualityBadges.best(it) }.orEmpty()
                    TitleBadges(facts.certificate ?: full?.certification?.takeIf { it.isNotBlank() }, facts.imdb, badges)
                    Spacer(Modifier.height(12.dp))
                    if (synopsis.isNotBlank()) {
                        Text(synopsis, style = Vyrk.type.bodyL, color = c.text.copy(alpha = 0.9f), maxLines = 4, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { synopsisCut = it.hasVisualOverflow })
                        Spacer(Modifier.height(18.dp))
                    }
                    HeroActions(meta, item, isSeries, playReq, onPlay = {
                        val ranked = (sources as? SourcesState.Ready)?.ranked
                        Playback.start(activity, graph, session, meta, ranked = if (isSeries) null else ranked, origin = o.origin)
                    }, onTrailer = { closeOverlay(app, o); app.preview = meta })
                    if (item?.isInContinueWatching == true && item.progress > 0f) {
                        Spacer(Modifier.height(12.dp))
                        ResumeLine(item)
                    }
                }
            }
            // ---- The tabs and the open tab ----
            Column(Modifier.fillMaxWidth().onFocusChanged { lowerFocused = it.hasFocus }.focusGroup()) {
                InfoTabBar(tabs, shownTab, tabReq, onSelect = { tab = it })
                Box(Modifier.fillMaxWidth().height(contentH).padding(horizontal = Dimens.screenMargin)) {
                    when (shownTab) {
                        InfoTab.EPISODES -> Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
                            SeasonGuide(full, details.loading, item, sstate.newEpisodes[meta.id].orEmpty(), settings.blurUnwatched, onPlay = { v ->
                                Playback.start(activity, graph, session, meta, videoId = v.id, origin = o.origin)
                            }, onMark = { videos, watched -> full?.let { session.markVideos(it, videos, watched) } })
                        }
                        InfoTab.SOURCES -> Column(Modifier.widthIn(max = 760.dp).fillMaxHeight().padding(top = 14.dp)) {
                            SourcesPane(sources, sourcesReq, onRefresh = { loadSources() }, onPick = { r ->
                                Playback.start(activity, graph, session, meta, picked = r, ranked = (sources as? SourcesState.Ready)?.ranked, origin = o.origin)
                            })
                        }
                        InfoTab.DETAILS -> DetailsPanel(meta, full, tmdb, facts, synopsis.takeIf { synopsisCut && it.isNotBlank() }, loading = details.loading)
                        InfoTab.CAST -> CastPanel(people)
                        InfoTab.RELATED -> RelatedPanel(related)
                        InfoTab.EXTRAS -> ExtrasPanel(extras, playing = playingExtra != null, onPlay = { playingExtra = it })
                    }
                }
            }
        }
        // On the second stop the title's name stays in view, top left, above the tabs.
        Text(name, style = Vyrk.type.headlineS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Dimens.screenMargin, top = 18.dp, end = 160.dp).graphicsLayer { alpha = progress() })
        Clock(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = Dimens.screenMargin))
        playingExtra?.let { x -> ExtraPlayer(x) }
    }
}

/** The title page's tabs. */
internal enum class InfoTab(val label: Int, val trace: String) {
    EPISODES(R.string.episodes, "episodes"), SOURCES(R.string.sources, "sources"), DETAILS(R.string.details, "details"),
    CAST(R.string.cast_and_crew, "cast"), RELATED(R.string.related, "related"), EXTRAS(R.string.extras, "extras"),
}

/** The name in large, bold type when there is no logo; a size smaller for long names. */
@Composable
private fun heroTitleStyle(name: String) = if (name.length > 24) Vyrk.type.displayS else Vyrk.type.displayM

/**
 * The title's artwork, right-aligned and blended into the stage: it fades out towards the text on
 * the left and along the bottom, with a little midnight blue low on the left. [dim] (0 to 1, the
 * page's second stop) darkens it under the tabs. The first backdrop that loads is kept while the
 * title's details arrive; the next candidate is tried if one fails.
 */
@Composable
private fun TitleBackdrop(urls: List<String>, dim: () -> Float) {
    val c = Vyrk.colors
    val stage = c.stage
    var failed by remember { mutableStateOf(emptySet<String>()) }
    var shown by remember { mutableStateOf<String?>(null) }
    val url = shown?.takeIf { it !in failed } ?: urls.firstOrNull { it !in failed }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val artW = maxWidth * 0.78f
        Box(Modifier.align(Alignment.TopEnd).width(artW).fillMaxHeight()) {
            Crossfade(url, animationSpec = com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.SLOW), label = "info-backdrop") { u ->
                if (u != null) AsyncImage(u, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
                    onSuccess = { if (shown == null) shown = u }, onError = { failed = failed + u })
            }
            // The artwork's left edge melts into the stage.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to stage, 0.3f to stage.copy(alpha = 0.7f), 0.6f to Color.Transparent)))
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(shown) { shown?.let { com.vyrk.tv.ui.components.AmbientTint.follow(context, it) } }
        // Down the page: clear behind the title, then into the stage under the tabs; a touch at the top for the clock.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to stage.copy(alpha = 0.45f), 0.14f to Color.Transparent, 0.55f to Color.Transparent, 0.82f to stage.copy(alpha = 0.9f), 1f to stage,
        )))
        // Midnight blue, low on the left, where the text and actions sit.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.stageSurface.copy(alpha = 0.7f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY), radius = 1300f)))
        // The second stop: the artwork steps back behind the tabs.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim() * 0.62f }.background(stage))
    }
}

/** Year · genre · seasons · runtime. */
@Composable
private fun TitleFacts(meta: MetaPreview, full: MetaItem?) {
    val c = Vyrk.colors
    val seasonCount = full?.videos?.mapNotNull { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 && meta.type == "series" }
    val parts = listOfNotNull(
        (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() },
        (full?.allGenres ?: meta.genres).take(2).joinToString(" / ").takeIf { it.isNotBlank() },
        seasonCount?.let { n -> if (n == 1) str(R.string.season_n, 1) else str(R.string.seasons_count, n) },
        RuntimeText.format(full?.runtime ?: meta.runtime),
    )
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("·", style = Vyrk.type.titleM, color = c.textMuted)
            Text(p, style = Vyrk.type.titleM, color = c.stageTextSecondary, maxLines = 1)
        }
    }
}

/**
 * The certificate (the region's, else the add-on's) in an outline, IMDb's score (only IMDb's: see
 * rememberCardFacts) and, for films, the quality badges of the source VYRK would play. The row
 * keeps its height while they arrive.
 */
@Composable
private fun TitleBadges(certificate: String?, imdb: Double?, badges: List<com.vyrk.core.stream.QualityBadge>) {
    val c = Vyrk.colors
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (certificate != null) Box(Modifier.border(1.dp, c.stageTextSecondary.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(certificate, style = Vyrk.type.labelM, color = c.stageTextSecondary, maxLines = 1)
        }
        if (imdb != null) Tag("IMDb ${CardFacts.formatImdb(imdb)}", color = c.background, background = c.gold)
        if (badges.isNotEmpty()) com.vyrk.tv.ui.components.QualityBadgeRow(badges)
    }
}

/** Play (or Resume, naming the episode), Trailer, Library and Watched. Up from the tabs comes back to the last one focused. */
@Composable
private fun HeroActions(meta: MetaPreview, item: LibraryItem?, isSeries: Boolean, playReq: FocusRequester, onPlay: () -> Unit, onTrailer: () -> Unit) {
    val session = LocalSession.current ?: return
    Row(Modifier.focusRestorer(playReq), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val inCw = item?.isInContinueWatching == true
        // The flagship action, as on Home: a glowing pill; a series names the episode it resumes.
        val resumeEp = if (inCw && isSeries) LibraryRules.episodeLabel(item.id, item.state.videoId) else null
        VyrkButton(str(if (inCw) R.string.resume else R.string.play) + (resumeEp?.let { "  ·  $it" } ?: ""), icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
            height = ACTION_H + 2.dp, horizontalPadding = 26.dp, glow = true, shape = Dimens.pillShape,
            modifier = Modifier.focusRequester(playReq).traceFocus("info:play"), onClick = onPlay)
        VyrkButton(str(R.string.trailer), icon = R.drawable.ic_trailer, height = ACTION_H, horizontalPadding = 20.dp, style = ButtonStyle.OUTLINE, shape = Dimens.pillShape,
            modifier = Modifier.traceFocus("info:trailer"), onClick = onTrailer)
        val inLib = item?.inLibrary == true
        VyrkIconButton(if (inLib) R.drawable.ic_check else R.drawable.ic_plus, str(if (inLib) R.string.in_library else R.string.add_to_library),
            onClick = { if (inLib) session.removeFromLibrary(meta.id) else session.addToLibrary(meta) },
            size = ACTION_H, modifier = Modifier.traceFocus("info:library"))
        val watched = item?.watched == true
        VyrkIconButton(R.drawable.ic_eye, str(if (watched) R.string.mark_unwatched else R.string.mark_watched),
            onClick = { session.markWatched(meta, !watched) }, size = ACTION_H, active = watched, modifier = Modifier.traceFocus("info:mark_watched"))
    }
}

/**
 * The tab bar: plain labels; the open tab sits in a soft accent pill, and the focused one is lit
 * with the accent. A tab opens as focus reaches it. Entering the bar from above or below lands on
 * the open tab. A hairline runs under the bar.
 */
@Composable
private fun InfoTabBar(tabs: List<InfoTab>, shown: InfoTab, openReq: FocusRequester, onSelect: (InfoTab) -> Unit) {
    val c = Vyrk.colors
    Column(Modifier.fillMaxWidth().height(TABS_H).padding(horizontal = Dimens.screenMargin)) {
        Row(
            Modifier.weight(1f).focusProperties { onEnter = { openReq.tryRequestFocus() } }.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { t ->
                val selected = t == shown
                Surface(
                    onClick = { onSelect(t) },
                    modifier = Modifier.then(if (selected) Modifier.focusRequester(openReq) else Modifier)
                        .onFocusChanged { if (it.isFocused) onSelect(t) }
                        .focusHalo().height(36.dp).traceFocus("info:tab:${t.trace}"),
                    shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) c.accent.copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = if (selected) c.text else c.stageTextSecondary,
                        focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
                        pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = if (selected) Border(BorderStroke(1.dp, c.accent.copy(alpha = 0.45f))) else Border.None,
                        focusedBorder = Border.None,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.04f)),
                    glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
                ) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text(str(t.label), style = Vyrk.type.titleM, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.08f)))
    }
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
private fun SourcesPane(state: SourcesState, firstReq: FocusRequester, onRefresh: () -> Unit, onPick: (Ranked) -> Unit) {
    val c = Vyrk.colors
    var filter by remember { mutableStateOf(SourceFilters.ALL) }
    var expanded by remember { mutableStateOf<String?>(null) }
    Text(str(R.string.ready_when_you_are).uppercase(), style = Vyrk.type.eyebrow, color = c.accent, maxLines = 1)
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
    val message = sourcesMessage(state)
    when {
        message != null -> Text(message, style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null -> {
            val langs = SourceFilters.languages(playable)
            val filters = SourceFilters.of(playable)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filters) { f ->
                    VyrkChip(if (f == SourceFilters.ALL) str(R.string.filter_all) else if (f in langs) Languages.name(f) else f, filter == f, { filter = f }, Modifier.traceFocus("sources:filter:$f"))
                }
            }
            Spacer(Modifier.height(10.dp))
            val shown = ready.ranked.filter { SourceFilters.matches(it, filter) }
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
private fun SeasonGuide(meta: MetaItem?, loading: Boolean, item: LibraryItem?, newIds: Set<String>, blurUnwatched: Boolean, onPlay: (Video) -> Unit, onMark: (List<Video>, Boolean) -> Unit) {
    val c = Vyrk.colors
    // Finished without matching metadata: say so instead of showing "Loading…" forever.
    if (meta == null) { Text(str(if (loading) R.string.loading else R.string.no_episodes), style = Vyrk.type.bodyM, color = c.textMuted); return }
    val seasons = EpisodeGuide.seasons(meta.videos)
    val wb = item?.watchedBitField(meta.videos)
    val now = System.currentTimeMillis()
    val upNextId = remember(meta.id, item, wb) { EpisodeGuide.upNextId(meta.videos, item, wb, now) }
    var season by remember(meta.id) { mutableStateOf(EpisodeGuide.startSeason(meta.videos, item, upNextId)) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val eps = meta.videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    // Seasons as tabs: the open one is a raised pill with an accent edge; remote focus is lit with the accent.
    // Up from the episodes lands on the open season, kept in view, not on whichever tab sits above.
    val seasonReq = remember { FocusRequester() }
    val seasonRow = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(meta.id) { seasons.indexOf(season).takeIf { it > 0 }?.let { seasonRow.scrollToItem(it) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f).focusProperties { onEnter = { seasonReq.tryRequestFocus() } }.focusGroup(),
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
            val released = EpisodeGuide.releasedAt(v)
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

/** A season tab. Selected (a raised pill with an accent edge) and focused (lit with the accent) look different, so the open season is never mistaken for focus. */
@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit, trace: String, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = modifier.focusHalo().height(32.dp).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.stageSurface else Color.Transparent, contentColor = if (selected) c.text else c.stageTextSecondary,
            focusedContainerColor = c.accent.copy(alpha = 0.24f), focusedContentColor = c.text,
            pressedContainerColor = c.accent.copy(alpha = 0.4f), pressedContentColor = c.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, c.accent.copy(alpha = 0.5f))) else Border(BorderStroke(1.dp, c.text.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(1.5.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.04f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Vyrk.type.labelL, maxLines = 1)
        }
    }
}

/**
 * One episode: a large still (its number in the accent when there is none) with progress and a watched
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
        modifier = Modifier.focusHalo().fillMaxWidth().traceFocus("episode:${v.id}")
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (upNext) c.accent.copy(alpha = 0.08f) else c.stageSurface.copy(alpha = 0.7f), contentColor = c.text,
            focusedContainerColor = c.stageSurface, focusedContentColor = c.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (upNext) c.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.06f))),
            focusedBorder = Border(BorderStroke(1.5.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.02f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(144.dp, 81.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(c.stageSurface, c.stage)))) {
                Text("E${v.episode ?: ""}", style = Vyrk.type.cinematicM, color = c.accent.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.Center))
                if (v.thumbnail != null) AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (blur && Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier))
                if (watched || future) Box(Modifier.fillMaxSize().background(c.shade.copy(alpha = if (future) 0.55f else 0.35f)))
                if (watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(c.accent, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    com.vyrk.tv.ui.components.Icon(R.drawable.ic_check, null, Modifier.size(11.dp), tint = c.onAccent)
                }
                if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listOfNotNull(v.season?.let { "S$it" }, v.episode?.let { "E$it" }).joinToString("  ·  "), style = Vyrk.type.eyebrow, color = c.accent, maxLines = 1)
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
    // Picture and sound as badges; the rest as text.
    val sum = remember(r) { SourceSummary.of(i) }
    val uhd = sum.uhd
    Surface(
        onClick = onClick,
        modifier = modifier.focusHalo().fillMaxWidth().traceFocus("source:${r.candidate.addonId}:${r.candidate.index}"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (recommended) c.accent.copy(alpha = 0.10f) else c.stageSurface.copy(alpha = 0.7f), contentColor = c.text,
            focusedContainerColor = c.stageSurface, focusedContentColor = c.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (recommended) c.accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f))),
            focusedBorder = Border(BorderStroke(1.5.dp, c.accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = com.vyrk.tv.ui.theme.focusZoom(1.02f)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
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
                    com.vyrk.tv.ui.components.debridTag(r)?.let { Tag(it, color = c.gold, background = c.gold.copy(alpha = 0.16f)) }
                }
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sum.badges.isNotEmpty()) com.vyrk.tv.ui.components.QualityBadgeRow(sum.badges)
                    Text(sum.details, style = Vyrk.type.labelM, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (sum.languages.isNotEmpty()) Text(sum.languages, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


/** Where a started title stands: a slim accent bar and "S1 E3 · 42m left". */
@Composable
private fun ResumeLine(item: LibraryItem) {
    val c = Vyrk.colors
    val st = item.state
    val left = timeLeftLabel(st)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(180.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(item.progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
        }
        val label = listOfNotNull(com.vyrk.core.library.LibraryRules.episodeLabel(item.id, st.videoId), left).joinToString("  ·  ")
        if (label.isNotEmpty()) Text(label, style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1)
    }
}
