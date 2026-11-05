package com.vyrk.tv.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.library.LibraryRules
import com.vyrk.tv.BuildConfig
import com.vyrk.tv.data.FocusMemory
import com.vyrk.tv.data.PreviewMode
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Origin
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.preview.HeaderTrailer
import com.vyrk.tv.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

const val CW_ROW = "cw"
/** Rows fetched as soon as a tab is built; the hero takes its titles from these. */
const val HERO_SOURCE_ROWS = 6

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowsePage(
    session: ProfileSession,
    model: BrowseModel,
    tabKey: String,
    onPlay: (MetaPreview, Origin?) -> Unit,
    /** False while the page is kept alive but hidden behind another tab. */
    active: Boolean = true,
) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val library by session.library.collectAsState()
    // Only the parts of the session state this page draws. Focus memory is rewritten on every
    // poster focus; reading the whole state here recomposed the page (and every kept page) each time.
    val hiddenCw by remember(session) { session.state.map { it.hiddenContinueWatching }.distinctUntilChanged() }
        .collectAsState(session.state.value.hiddenContinueWatching)
    val newEpisodes by remember(session) { session.state.map { it.newEpisodes }.distinctUntilChanged() }
        .collectAsState(session.state.value.newEpisodes)
    val listState = model.listState
    val flags = remember { PageFocusFlags() }
    val pageSpec = remember { PageSpec(flags) }
    val watchReq = remember { FocusRequester() }
    val top10Req = remember { FocusRequester() }
    val isHome = model.tab == Tab.HOME

    LaunchedEffect(addons, settings.hiddenRows) { model.rebuild(session.effectiveAddons(), settings.hiddenRows); model.prefetch(HERO_SOURCE_ROWS) }

    // Continue Watching follows the live library at once. Library entries carry only a name and a
    // poster, and entries written by other apps can have neither (Game of Thrones showed a blank
    // card with no title): fill them from the title's full meta, then Cinemeta's poster by IMDb id.
    val cwLibrary = remember(library, hiddenCw) { LibraryRules.continueWatching(library.values).filter { it.id !in hiddenCw } }
    // Changes only when one of these titles' metas arrives (not on every header fetch), so the page
    // doesn't recompose as focus moves.
    val cwMetas by remember(cwLibrary) {
        derivedStateOf { graph.metaCache.version; cwLibrary.count { graph.metaCache.peek(it.type, it.id) != null } }
    }
    val cwItems = remember(cwLibrary, cwMetas) {
        cwLibrary.map { li ->
            val full = graph.metaCache.peek(li.type, li.id)
            MetaPreview(
                id = li.id, type = li.type,
                name = li.name.ifBlank { full?.name.orEmpty() },
                poster = li.poster ?: full?.poster ?: com.vyrk.core.art.Artwork.metahubPoster(li.id),
                background = full?.background, logo = full?.logo,
            )
        }
    }
    // One at a time, so these never take the request slots the rows need.
    LaunchedEffect(cwLibrary.filter { it.name.isBlank() || it.poster == null }.map { it.type to it.id }) {
        val addons = session.effectiveAddons()
        cwLibrary.filter { it.name.isBlank() || it.poster == null }.take(24)
            .forEach { li -> graph.metaCache.get(addons, li.type, li.id) }
    }
    val cwRow = remember { RowState(CW_ROW, RowKind.CONTINUE, null, "") }
    val cwTitle = com.vyrk.tv.ui.str(com.vyrk.tv.R.string.continue_watching)
    cwRow.items = cwItems; cwRow.loadedOnce = true; cwRow.title = cwTitle

    val showTop10Card = isHome && !model.hasTop10Catalogs && !settings.top10CardDismissed && BuildConfig.TOP10_ADDON_URL.isNotBlank()
    val rows = buildList {
        if (isHome && cwItems.isNotEmpty()) add(cwRow)
        addAll(model.rows)
    }

    // Hero: featured titles from the first catalogue that has artwork.
    val heroCandidates by remember(model.rows) {
        derivedStateOf { model.rows.firstOrNull { r -> r.items.any { it.background != null } }?.items?.filter { it.background != null }?.take(6).orEmpty() }
    }
    LaunchedEffect(heroCandidates.map { it.id }) { model.lockHero(heroCandidates) }
    val heroItems = model.heroItems.ifEmpty { heroCandidates }
    val heroIndex = model.heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0))
    var heroFocused by remember { mutableStateOf(false) }
    var focusedPoster by model::focusedPoster
    var focusedRowKey by model::focusedRowKey
    val heroMeta = heroItems.getOrNull(heroIndex)
    val heroFull by produceMeta(session, heroMeta)
    val headerFull by produceMeta(session, focusedPoster, debounceMs = 250)
    val heroArt = rememberTitleArt(heroMeta, heroFull)
    val headerArt = rememberTitleArt(focusedPoster, headerFull, debounceMs = 300)
    val heroEnabled = settings.heroEnabled && heroItems.isNotEmpty() && model.tab != Tab.LIVE
    // Settings draws the artwork you were last looking at, blurred, behind its glass panels.
    val shownBackdrop = (if (heroEnabled) heroArt else headerArt).backdrops.firstOrNull()
    LaunchedEffect(active, shownBackdrop) { if (active && shownBackdrop != null) app.backdrop = shownBackdrop }
    // The hero keeps its slot while its titles load. Inserting it later above rows that are
    // already on screen left it scrolled out of view (lazy lists keep the first visible item in
    // place), which is why it sometimes never showed on the first visit to a tab.
    // Decided once the rows fetched up front have answered (or failed); none with artwork means no hero.
    val heroSourcesDone = model.built && model.rows.take(HERO_SOURCE_ROWS).all { it.loadedOnce }
    val heroPending = settings.heroEnabled && model.tab != Tab.LIVE && heroItems.isEmpty() && !heroSourcesDone
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Rotation every 9 s, paused while focused, under overlays/previews, scrolled away, in the background or with Reduce motion.
    LaunchedEffect(heroItems.size, settings.heroRotation, settings.reduceMotion, active) {
        if (!active || heroItems.size < 2 || !settings.heroRotation || settings.reduceMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(9_000)
                val atTop = listState.firstVisibleItemIndex == 0 && focusedPoster == null
                if (!heroFocused && app.overlays.isEmpty() && app.preview == null && atTop) model.heroIndex = (model.heroIndex + 1) % model.heroItems.size.coerceAtLeast(1)
            }
        }
    }

    // Initial focus once per screen: restored poster (restart) or the hero's Watch now.
    var initialDone by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<Origin?>(null) }
    // Decide only once the first catalogue has answered, so the hero's presence is known.
    // Also wait for the hero to be decided, so focus never lands on a row the hero then pushes down.
    val firstLoaded = model.built && (model.rows.firstOrNull()?.loadedOnce == true || model.rows.isEmpty()) && !heroPending
    LaunchedEffect(firstLoaded, rows.size) {

        if (initialDone || !firstLoaded) return@LaunchedEffect
        val mem = session.state.value.focus[tabKey]
        initialDone = true
        // Opened from the tab strip: focus stays there; Down enters the content.
        if (app.navFocused) return@LaunchedEffect
        when {
            mem != null && rows.any { it.key == mem.rowKey && it.items.isNotEmpty() } ->
                restoreTarget = Origin(tabKey, mem.rowKey, mem.index, mem.itemId ?: "")
            heroEnabled -> { listState.scrollToItem(0); delay(60); runCatching { watchReq.requestFocus() } }
            rows.firstOrNull()?.items?.isNotEmpty() == true -> restoreTarget = Origin(tabKey, rows.first().key, 0, "")
            showTop10Card -> { delay(60); runCatching { top10Req.requestFocus() } }
            else -> app.focusTabsTick++
        }
    }    // Restores requested by overlays and playback.
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(app.restore) {
        val r = app.restore ?: return@LaunchedEffect
        if (r.tab != tabKey) return@LaunchedEffect
        // Apply only once this window is visible and focused again (after the player or a dialog).
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        androidx.compose.runtime.snapshotFlow { windowInfo.isWindowFocused }.first { it }
        app.restore = null
        restoreTarget = r
    }
    // When the Top 10 card goes away while focused (rows were added, or Not now), focus the hero.
    val windowInfoForCard = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(showTop10Card) {
        if (!showTop10Card && flags.top10CardFocused.getAndSet(false)) {
            androidx.compose.runtime.snapshotFlow { windowInfoForCard.isWindowFocused }.first { it }
            listState.scrollToItem(0); delay(150)
            if (!runCatching { watchReq.requestFocus() }.isSuccess || !heroEnabled) restoreTarget = rows.firstOrNull()?.let { Origin(tabKey, it.key, 0, "") }
        }
    }
    // Only requests made while this page is open: a page built later (another tab) must not act
    // on an old request now that its hero can be ready on the first frame.
    val heroTickAtStart = remember { app.focusHeroTick }
    LaunchedEffect(app.focusHeroTick) {
        if (app.focusHeroTick > heroTickAtStart && heroEnabled && active) { listState.scrollToItem(0); runCatching { watchReq.requestFocus() } }
    }
    // Resolve a restore to a row index, falling back to a neighbour or the hero.
    LaunchedEffect(restoreTarget, rows.map { it.key to it.items.size }) {
        val t = restoreTarget ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.key == t.rowKey }
        val row = rows.getOrNull(idx)
        if (row == null || row.items.isEmpty()) {
            if (row != null && !row.loadedOnce) return@LaunchedEffect // wait for data
            restoreTarget = null
            if (heroEnabled) { listState.scrollToItem(0); delay(30); runCatching { watchReq.requestFocus() } }
            return@LaunchedEffect
        }
        val headerItems = (if (heroEnabled) 1 else 0) + (if (showTop10Card) 1 else 0)
        // Already on screen (back from the info page, or from the tab strip): don't jump the page.
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == t.rowKey }) listState.scrollToItem(idx + headerItems)
    }


    // Images ahead of time: hero backdrops and logos, and the first posters of the first rows, at
    // the size they are drawn, so they come from memory when they appear.
    val context = androidx.compose.ui.platform.LocalContext.current
    val posterPx = with(LocalDensity.current) { settings.posterSize.dp.roundToPx() }
    LaunchedEffect(heroItems.map { it.id }) {
        val dm = context.resources.displayMetrics
        val size = backdropSize(dm.widthPixels)
        heroItems.forEach { m ->
            com.vyrk.core.art.Artwork.forBackdrop(m.background, size)?.let { prefetchImage(context, model, it, dm.widthPixels, dm.heightPixels) }
            com.vyrk.core.art.Artwork.forLogo(m.logo)?.let { prefetchImage(context, model, it, null, null) }
        }
    }
    LaunchedEffect(rows.take(8).map { it.key to it.items.size }, posterPx) {
        rows.take(8).forEach { r -> r.items.take(8).forEach { m -> m.poster?.let { prefetchImage(context, model, it, posterPx, (posterPx * 1.5f).toInt()) } } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()
        // Down from the tab strip returns to the title you left, not whatever row is nearest.
        .focusProperties {
            onEnter = {
                val mem = session.state.value.focus[tabKey]
                if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down && focusedPoster != null && mem != null &&
                    rows.any { it.key == mem.rowKey && it.items.isNotEmpty() }) {
                    cancelFocusChange()
                    restoreTarget = Origin(tabKey, mem.rowKey, mem.index, mem.itemId ?: "")
                }
            }
        }
        .focusGroup()) {
        val screenH = maxHeight + Dimens.navHeight
        val heroH = (screenH * (if (isHome) 0.66f else 0.72f)) - Dimens.navHeight
        val headerH = screenH * 0.56f - Dimens.navHeight
        val density = LocalDensity.current
        val rowTitleH = 30.dp
        flags.posterTarget.set(with(density) { (headerH + rowTitleH).toPx() })

        // Backdrop: hero artwork (the focused poster's artwork is drawn under the header below).
        val showBackdrop = focusedPoster == null && heroEnabled && listState.firstVisibleItemIndex == 0
        // It reaches down behind the first row (Continue Watching), as in the reference layout.
        if (showBackdrop && active) Backdrop(heroArt.backdrops, Modifier.offset(y = -Dimens.navHeight).fillMaxWidth().height(heroH + 200.dp + Dimens.navHeight))

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentPadding = PaddingValues(bottom = 200.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.rowGap),
            ) {
                if (heroPending) item(key = "hero") { Box(Modifier.fillMaxWidth().height(heroH)) }
                if (heroEnabled) item(key = "hero") {
                    Hero(
                        items = heroItems, index = heroIndex.coerceAtMost(heroItems.lastIndex), full = heroFull, art = heroArt, height = heroH,
                        hasProgress = heroMeta?.let { library[it.id]?.isInContinueWatching } == true,
                        inLibrary = heroMeta?.let { library[it.id]?.inLibrary } == true,
                        watchReq = watchReq,
                        onWatch = { heroMeta?.let { onPlay(it, null) } },
                        onInfo = { heroMeta?.let { app.push(Overlay.Info(it, null)) } },
                        onToggleLibrary = { heroMeta?.let { m -> if (library[m.id]?.inLibrary == true) session.removeFromLibrary(m.id) else session.addToLibrary(m) } },
                        onPrev = { model.heroIndex = (heroIndex - 1 + heroItems.size) % heroItems.size },
                        onNext = { model.heroIndex = (heroIndex + 1) % heroItems.size },
                        onFocusChanged = { f ->
                            heroFocused = f; flags.heroFocused.set(f)
                            if (f) flags.top10CardFocused.set(false)
                            if (f) { focusedPoster = null; scope.launch { listState.animateScrollToItem(0) } } else if (app.preview?.id == heroMeta?.id) Unit
                        },
                    )
                }
                if (showTop10Card) item(key = "top10card") {
                    Top10Card(
                        addReq = top10Req,
                        onAdd = {
                            val url = BuildConfig.TOP10_ADDON_URL
                            app.push(Overlay.InstallConfirm(null, url, external = false))
                            scope.launch {
                                val d = session.fetchManifest(url)
                                val top = app.overlays.lastOrNull()
                                if (top is Overlay.InstallConfirm && top.input == url) app.replaceTop(top.copy(descriptor = d.getOrNull(), error = d.exceptionOrNull()?.let { "fetch" }))
                            }
                        },
                        onNotNow = { session.updateSettings { it.copy(top10CardDismissed = true) }; runCatching { watchReq.requestFocus() } },
                        onFocus = { f ->
                            // Cleared only when something else on the page takes focus, so removal of the
                            // card while focused is still known.
                            if (f) { flags.top10CardFocused.set(true); focusedPoster = null; scope.launch { listState.animateScrollToItem(0) } }
                        },
                    )
                }
                items(rows, key = { it.key }) { row ->
                    val origin = { i: Int, m: MetaPreview -> Origin(tabKey, row.key, i, m.id) }
                    BrowseRow(
                        row = row,
                        posterWidth = settings.posterSize.dp,
                        library = library,
                        newEpisodes = newEpisodes,
                        // One instance per row, reading live values when called, so rows can skip
                        // recomposition when the page recomposes (a new instance each time forced
                        // every visible poster to recompose on every focus move).
                        callbacks = remember(row) { PosterCallbacks(
                            onClick = { m, i ->
                                val inCw = session.library.value[m.id]?.isInContinueWatching == true
                                app.push(Overlay.Info(m, origin(i, m), focusResume = row.kind == RowKind.CONTINUE || inCw))
                            },
                            onLongClick = { m, i -> app.push(Overlay.ItemMenu(m, origin(i, m))) },
                            onPlayPause = { m, _ -> if (session.settings.value.previews != PreviewMode.OFF) app.preview = if (app.preview?.id == m.id) null else m },
                            onFocused = { m, i ->
                                if (app.preview != null && app.preview?.id != m.id) app.preview = null
                                focusedPoster = m; focusedRowKey = row.key
                                flags.heroFocused.set(false); flags.top10CardFocused.set(false)
                                session.rememberFocus(tabKey, FocusMemory(row.key, i, m.id))
                                if (row.kind != RowKind.CONTINUE && i >= row.items.size - 6) model.loadMore(row)
                            },
                        ) },
                        listState = model.rowListState(row.key),
                        onEnsureLoaded = { model.ensureLoaded(row) },
                        onLoadMore = { model.loadMore(row) },
                        onRetry = { model.retry(row) },
                        onSeeAll = { row.ref?.let { app.push(Overlay.SeeAll(row.title, it)) } },
                        restoreIndex = restoreTarget?.takeIf { it.rowKey == row.key }?.let { t ->
                            val byId = row.items.indexOfFirst { it.id == t.itemId }
                            if (byId >= 0) byId else t.index.coerceAtMost((row.items.size - 1).coerceAtLeast(0))
                        },
                        onRestored = { restoreTarget = null },
                    )
                }
            }
        }

        // Preview card: below the bar, never takes focus; Back or any focus move closes it.
        if (active) app.preview?.let { pm ->
            androidx.activity.compose.BackHandler { app.preview = null }
            com.vyrk.tv.ui.preview.PreviewCard(pm, sound = settings.previewSound, modifier = Modifier.padding(top = 12.dp, end = Dimens.screenMargin))
        }
        // Title header over the list while a poster has focus.
        val fp = focusedPoster
        if (fp != null && !heroFocused && active) {
            // Opaque: rows above the focused one scroll beneath it.
            Box(Modifier.offset(y = -Dimens.navHeight).fillMaxWidth().height(headerH + Dimens.navHeight).background(com.vyrk.tv.ui.theme.Vyrk.colors.background)) {
                Backdrop(headerArt.backdrops, Modifier.fillMaxSize())
                TitleHeader(fp, headerFull, headerArt, headerH, trailerPlaying = false)
                if (settings.previews == PreviewMode.AUTOMATIC && app.overlays.isEmpty()) {
                    HeaderTrailer(meta = fp, full = headerFull, sound = settings.previewSound, modifier = Modifier.padding(end = Dimens.screenMargin, top = Dimens.navHeight + 12.dp))
                }
            }
        }
    }
}

/**
 * Full details for [meta], or null while they load. Never another title's: when [meta] changes,
 * produceState keeps its previous value until its new run sets one, and for that frame the header
 * paired the new title with the old title's details (its logo then got kept for the new title).
 * Each value is tagged with the id it was fetched for and only returned for that id.
 */
@Composable
fun produceMeta(session: ProfileSession, meta: MetaPreview?, debounceMs: Long = 0): androidx.compose.runtime.State<com.vyrk.core.addon.MetaItem?> {
    val graph = LocalGraph.current
    val fetched = androidx.compose.runtime.produceState<Pair<String?, com.vyrk.core.addon.MetaItem?>>(
        initialValue = meta?.id to meta?.let { graph.metaCache.peek(it.type, it.id) }, meta?.id,
    ) {
        val m = meta ?: run { value = null to null; return@produceState }
        graph.metaCache.peek(m.type, m.id)?.let { value = m.id to it; return@produceState }
        value = m.id to null
        if (debounceMs > 0) delay(debounceMs)
        value = m.id to graph.metaCache.get(session.effectiveAddons(), m.type, m.id)
    }
    val id = meta?.id
    return remember(id) {
        androidx.compose.runtime.derivedStateOf {
            fetched.value.takeIf { it.first == id }?.second ?: meta?.let { graph.metaCache.peek(it.type, it.id) }
        }
    }
}

/** TMDB backdrop size for this screen: original on screens wider than 1280 px (1080p and 4K), else w1280. */
fun backdropSize(screenWidthPx: Int): String = if (screenWidthPx > 1280) "original" else "w1280"

/**
 * Logo and backdrop candidates for a title, best first: the add-on's own artwork (asked for at a
 * size sharp enough for the hero), then TMDB's English-first pick when the build has a TMDB key
 * and the add-on gave none, then Cinemeta's by IMDb id. Each is tried in turn if one fails to load.
 */
@Composable
fun rememberTitleArt(meta: MetaPreview?, full: com.vyrk.core.addon.MetaItem?, debounceMs: Long = 0): TitleArt {
    val graph = LocalGraph.current
    val size = backdropSize(androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels)
    val addonLogo = com.vyrk.core.art.Artwork.forLogo(full?.logo ?: meta?.logo)
    val addonBackdrop = com.vyrk.core.art.Artwork.forBackdrop(full?.background ?: meta?.background, size)
    val needTmdb = meta != null && graph.tmdb.enabled && (addonLogo == null || addonBackdrop == null)
    // Tagged with the id it was fetched for, like produceMeta: the previous title's TMDB logo must
    // never be offered for the next one in the frame before this restarts.
    val fetched by androidx.compose.runtime.produceState<Pair<String?, com.vyrk.core.tmdb.TmdbArtwork?>>(null to null, meta?.id, needTmdb) {
        value = meta?.id to null
        val m = meta ?: return@produceState
        if (!needTmdb) return@produceState
        if (debounceMs > 0) delay(debounceMs)
        value = m.id to graph.tmdb.artwork(m.id, m.type, size)
    }
    val tmdb = fetched.takeIf { it.first == meta?.id }?.second
    val id = meta?.id ?: return TitleArt.NONE
    return remember(id, addonLogo, addonBackdrop, tmdb) {
        TitleArt(
            logos = listOfNotNull(addonLogo, tmdb?.logo, com.vyrk.core.art.Artwork.metahubLogo(id)).distinct(),
            backdrops = listOfNotNull(addonBackdrop, tmdb?.backdrop, com.vyrk.core.art.Artwork.metahubBackground(id)).distinct(),
        )
    }
}

/** Fetches an image into Coil's caches once per model; null size means the image's own size. */
private fun prefetchImage(context: android.content.Context, model: BrowseModel, url: String, w: Int?, h: Int?) {
    if (!model.prefetchedImages.add("$url@$w")) return
    val b = coil3.request.ImageRequest.Builder(context).data(url)
    if (w != null && h != null && w > 0 && h > 0) b.size(w, h)
    coil3.SingletonImageLoader.get(context).enqueue(b.build())
}