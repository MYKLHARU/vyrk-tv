package com.vyrk.tv.ui.browse

import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
/** Titles in a hero lineup. */
const val HERO_COUNT = 6

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowsePage(
    session: ProfileSession,
    model: BrowseModel,
    tabKey: String,
    onPlay: (MetaPreview, Origin?) -> Unit,
    /** False while the page is kept alive but hidden behind another tab. */
    active: Boolean = true,
    /** The open tab's name, shown over the hero and the title header. */
    pageLabel: String? = null,
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
    val cwItems = remember(cwLibrary, cwMetas, model) {
        cwLibrary.map { li ->
            val full = graph.metaCache.peek(li.type, li.id)
            MetaPreview(
                id = li.id, type = li.type,
                name = li.name.ifBlank { full?.name.orEmpty() },
                poster = li.poster ?: full?.poster ?: com.vyrk.core.art.Artwork.metahubPoster(li.id),
                background = full?.background, logo = full?.logo, genres = full?.allGenres.orEmpty(),
            )
        }.filter {
            val full = graph.metaCache.peek(it.type, it.id)
            !model.animeOnly || com.vyrk.core.catalog.AnimeBrowse.matches(it, genre = model.genre,
                trustedCatalog = full?.malId != null || full?.kitsuId != null)
        }
    }
    // One at a time, so these never take the request slots the rows need.
    LaunchedEffect(cwLibrary.filter { model.animeOnly || it.name.isBlank() || it.poster == null }.map { it.type to it.id }) {
        val addons = session.effectiveAddons()
        cwLibrary.filter { model.animeOnly || it.name.isBlank() || it.poster == null }.take(24)
            .forEach { li -> graph.metaCache.get(addons, li.type, li.id) }
    }
    val cwRow = remember { RowState(CW_ROW, RowKind.CONTINUE, null, "") }
    val cwTitle = com.vyrk.tv.ui.str(com.vyrk.tv.R.string.continue_watching)
    cwRow.items = cwItems; cwRow.loadedOnce = true; cwRow.title = cwTitle

    val showTop10Card = isHome && !model.animeOnly && !model.hasTop10Catalogs && !settings.top10CardDismissed && BuildConfig.TOP10_ADDON_URL.isNotBlank()
    val rows = buildList {
        if (isHome && cwItems.isNotEmpty()) add(cwRow)
        addAll(model.rows)
    }

    // Hero: featured titles. Movies, Series and the other pages feature the first catalogue with
    // artwork. Home has its own mix, so it never repeats the Movies or Series carousel: titles taken
    // in turn from each of its rows, leaving out the ones those two pages feature. Home waits for
    // the rows fetched up front, so the lineup it locks is the full mix.
    val heroCandidates by remember(model.rows) {
        derivedStateOf {
            fun art(r: RowState) = r.items.filter { it.background != null }
            fun firstWithArt(rows: List<RowState>) = rows.firstOrNull { art(it).isNotEmpty() }?.let { art(it).take(HERO_COUNT) }.orEmpty()
            when {
                !isHome -> firstWithArt(model.rows)
                !model.rows.take(HERO_SOURCE_ROWS).all { it.loadedOnce } -> emptyList()
                else -> {
                    val elsewhere = (firstWithArt(model.rows.filter { it.ref?.catalog?.type == "movie" }) +
                        firstWithArt(model.rows.filter { it.ref?.catalog?.type == "series" })).mapTo(HashSet()) { it.id }
                    val pools = model.rows.map { r -> art(r).filter { it.id !in elsewhere } }.filter { it.isNotEmpty() }
                    val picked = LinkedHashMap<String, MetaPreview>()
                    var i = 0
                    while (picked.size < HERO_COUNT && pools.any { i < it.size }) {
                        pools.forEach { pool -> pool.getOrNull(i)?.let { m -> if (picked.size < HERO_COUNT) picked.putIfAbsent(m.id, m) } }
                        i++
                    }
                    picked.values.toList().ifEmpty { firstWithArt(model.rows) }
                }
            }
        }
    }
    LaunchedEffect(heroCandidates.map { it.id }) { model.lockHero(heroCandidates) }
    val heroItems = model.heroItems.ifEmpty { heroCandidates }
    val heroIndex = model.heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0))
    var heroFocused by remember { mutableStateOf(false) }
    var focusedPoster by model::focusedPoster
    var focusedRowKey by model::focusedRowKey
    val heroMeta = heroItems.getOrNull(heroIndex)
    val heroDetails = produceMeta(session, heroMeta)
    val headerDetails = produceMeta(session, focusedPoster, debounceMs = 250)
    val heroFull by heroDetails
    val headerFull by headerDetails
    val heroArt = rememberTitleArt(heroMeta, heroFull, metadataLoading = heroDetails.loading)
    val headerArt = rememberTitleArt(focusedPoster, headerFull, debounceMs = 300, metadataLoading = headerDetails.loading)
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
        // The same height on every page.
        val heroH = screenH * 0.66f - Dimens.navHeight
        val headerH = screenH * 0.56f - Dimens.navHeight
        val density = LocalDensity.current
        val rowTitleH = 30.dp
        flags.posterTarget.set(with(density) { (headerH + rowTitleH).toPx() })

        // Full-screen wallpaper behind everything: the hero's artwork, or the focused title's while a
        // row has focus. The title header below draws over it with no background of its own.
        val headerShown = focusedPoster != null && !heroFocused && active
        val wallpaper = when {
            headerShown -> headerArt.backdrops
            heroEnabled -> heroArt.backdrops
            else -> emptyList()
        }
        // Taller than this page (it reaches up behind the bar), so its height must not be capped to the
        // page's: a plain height() was, which left the bottom bar-height of the screen uncovered with
        // a hard edge above the last row.
        val wallpaperMeta = if (headerShown) focusedPoster else heroMeta
        if (active && wallpaper.isNotEmpty() && wallpaperMeta != null) Wallpaper(
            com.vyrk.core.art.Artwork.titleKey(wallpaperMeta.type, wallpaperMeta.id), wallpaper,
            Modifier.offset(y = -Dimens.navHeight).fillMaxWidth()
                .wrapContentHeight(androidx.compose.ui.Alignment.Top, unbounded = true).height(screenH),
        )
        val headerBottomPx = with(density) { headerH.toPx() }
        // The carved rune pillar down the left edge, over the artwork and under everything else.
        if (active) com.vyrk.tv.ui.components.RunePillar(Modifier.offset(x = 17.dp).padding(top = 8.dp, bottom = 20.dp))

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
            LazyColumn(
                state = listState,
                // Under the title header, rows that scroll up are cut off at its bottom edge (it has
                // no background now, so they would show through the text).
                // Rows that scroll left fade out behind the rune pillar instead of running over it.
                modifier = Modifier.fillMaxSize().clipToBounds()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        if (headerShown) clipRect(top = headerBottomPx) { this@drawWithContent.drawContent() } else drawContent()
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 24.dp.toPx(), endX = 40.dp.toPx()), blendMode = BlendMode.DstIn)
                    },
                contentPadding = PaddingValues(bottom = 200.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.rowGap),
            ) {
                if (heroPending) item(key = "hero") { Box(Modifier.fillMaxWidth().height(heroH)) }
                if (heroEnabled) item(key = "hero") {
                    Hero(
                        items = heroItems, index = heroIndex.coerceAtMost(heroItems.lastIndex), full = heroFull, art = heroArt, height = heroH,
                        hasProgress = heroMeta?.let { library[it.id]?.isInContinueWatching } == true,
                        watchReq = watchReq, pageLabel = pageLabel,
                        onWatch = { heroMeta?.let { onPlay(it, null) } },
                        // Hold OK or Menu: the title menu, as on posters (remove from Continue Watching, details, library).
                        onMenu = { heroMeta?.let { app.push(Overlay.ItemMenu(it, null)) } },
                        onPrev = { model.heroIndex = (heroIndex - 1 + heroItems.size) % heroItems.size },
                        onNext = { model.heroIndex = (heroIndex + 1) % heroItems.size },
                        onFocusChanged = { f ->
                            heroFocused = f; flags.heroFocused.set(f)
                            if (f) flags.top10CardFocused.set(false)
                            if (f) { focusedPoster = null; scope.launch { listState.animateScrollToItem(0) } } else if (app.preview?.id == heroMeta?.id) Unit
                        },
                    )
                }
                if (model.animeOnly && model.built && model.rows.isEmpty()) item(key = "anime-empty") {
                    androidx.tv.material3.Text(com.vyrk.tv.ui.str(com.vyrk.tv.R.string.anime_no_catalogs),
                        modifier = Modifier.padding(Dimens.screenMargin), color = com.vyrk.tv.ui.theme.Vyrk.colors.textSecondary)
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
                                if (!row.animeOnly && row.kind != RowKind.CONTINUE && i >= row.items.size - 6) model.loadMore(row)
                                // The wallpaper for the titles either side, ahead of time, so moving along the row
                                // finds them ready (same screen size as the wallpaper asks for).
                                val dm = context.resources.displayMetrics
                                val size = backdropSize(dm.widthPixels)
                                listOf(i + 1, i - 1, i + 2).forEach { j ->
                                    row.items.getOrNull(j)?.let { n -> com.vyrk.core.art.Artwork.forBackdrop(n.background, size)?.let { prefetchImage(context, model, it, dm.widthPixels, dm.heightPixels) } }
                                }
                            },
                        ) },
                        listState = model.rowListState(row.key),
                        onEnsureLoaded = { model.ensureLoaded(row) },
                        onLoadMore = { model.loadMore(row) },
                        onRetry = { model.retry(row) },
                        onSeeAll = { row.ref?.let { app.push(Overlay.SeeAll(row.title, it, row.animeOnly, row.browseType, row.genre)) } },
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
        // Title header over the wallpaper while a poster has focus.
        val fp = focusedPoster
        if (fp != null && headerShown) {
            Box(Modifier.offset(y = -Dimens.navHeight).fillMaxWidth().height(headerH + Dimens.navHeight)) {
                TitleHeader(fp, headerFull, headerArt, headerH, trailerPlaying = false, pageLabel = pageLabel)
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
fun produceMeta(session: ProfileSession, meta: MetaPreview?, debounceMs: Long = 0): MetaState {
    val graph = LocalGraph.current
    val key = meta?.let { com.vyrk.core.art.Artwork.titleKey(it.type, it.id) }
    val fetched = androidx.compose.runtime.produceState<Triple<String?, com.vyrk.core.addon.MetaItem?, Boolean>>(
        initialValue = Triple(key, meta?.let { graph.metaCache.peek(it.type, it.id) }, meta != null), session, key,
    ) {
        val m = meta ?: run { value = Triple(null, null, false); return@produceState }
        graph.metaCache.peek(m.type, m.id)?.let { value = Triple(key, it, false); return@produceState }
        value = Triple(key, null, true)
        if (debounceMs > 0) delay(debounceMs)
        value = Triple(key, graph.metaCache.get(session.effectiveAddons(), m.type, m.id), false)
    }
    return remember(session, key) {
        object : MetaState {
            override val value: com.vyrk.core.addon.MetaItem? get() = meta?.let { m -> com.vyrk.core.art.Artwork.matchingMeta(m,
                fetched.value.takeIf { it.first == key }?.second ?: graph.metaCache.peek(m.type, m.id)) }
            override val loading: Boolean get() = meta != null && value == null && (fetched.value.first != key || fetched.value.third)
        }
    }
}

interface MetaState : androidx.compose.runtime.State<com.vyrk.core.addon.MetaItem?> { val loading: Boolean }

/** TMDB backdrop size for this screen: original on screens wider than 1280 px (1080p and 4K), else w1280. */
fun backdropSize(screenWidthPx: Int): String = if (screenWidthPx > 1280) "original" else "w1280"

/**
 * Logo and backdrop candidates for a title, best first: the add-on's own artwork (asked for at a
 * size sharp enough for the hero), then TMDB's English-first pick when the build has a TMDB key
 * and the add-on gave none, then Cinemeta's by IMDb id. Each is tried in turn if one fails to load.
 */
@Composable
fun rememberTitleArt(meta: MetaPreview?, full: com.vyrk.core.addon.MetaItem?, debounceMs: Long = 0, metadataLoading: Boolean = false): TitleArt {
    val graph = LocalGraph.current
    val size = backdropSize(androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels)
    val safeFull = meta?.let { com.vyrk.core.art.Artwork.matchingMeta(it, full) }
    val titleKey = meta?.let { com.vyrk.core.art.Artwork.titleKey(it.type, it.id) }
    val lookupId = meta?.let { com.vyrk.core.art.Artwork.lookupId(it, safeFull) }
    // A supplied URL can be broken too: prepare a cached fallback for the focused title.
    val needTmdb = lookupId != null && graph.tmdb.enabled
    // Tagged with the id it was fetched for, like produceMeta: the previous title's TMDB logo must
    // never be offered for the next one in the frame before this restarts.
    val fetched by androidx.compose.runtime.produceState<Triple<String?, com.vyrk.core.tmdb.TmdbArtwork?, Boolean>>(Triple(null, null, false), titleKey, lookupId, needTmdb, size) {
        value = Triple(titleKey, null, needTmdb)
        val m = meta ?: return@produceState
        if (!needTmdb) return@produceState
        delay(maxOf(debounceMs, 300))
        value = Triple(titleKey, graph.tmdb.artwork(lookupId, m.type, size), false)
    }
    val tmdb = fetched.takeIf { it.first == titleKey }?.second
    val needFanart = lookupId != null && graph.fanart.enabled
    val fanart by androidx.compose.runtime.produceState<Triple<String?, List<String>, Boolean>>(Triple(null, emptyList(), false), titleKey, lookupId) {
        value = Triple(titleKey, emptyList(), needFanart)
        val m = meta ?: return@produceState
        val id = lookupId ?: return@produceState
        if (!graph.fanart.enabled) return@produceState
        delay(maxOf(debounceMs, 300))
        value = Triple(titleKey, graph.fanart.logos(id, m.type), false)
    }
    val fanartLogos = fanart.takeIf { it.first == titleKey }?.second.orEmpty()
    val prequel = meta?.let { com.vyrk.core.art.SeasonLogos.prequel(it, safeFull) }
    val inherit = prequel != null && lookupId == null && meta?.logo.isNullOrBlank() && safeFull?.logo.isNullOrBlank()
    val seasonLogos by androidx.compose.runtime.produceState<Pair<String?, List<String>?>>(null to null, titleKey, inherit, prequel, safeFull) {
        value = titleKey to if (inherit) null else emptyList()
        val m = meta ?: return@produceState
        if (!inherit) return@produceState
        delay(maxOf(debounceMs, 300))
        val urls = kotlinx.coroutines.withTimeoutOrNull(12_000) {
            com.vyrk.core.art.SeasonLogos.resolve(m, safeFull) { id -> graph.metaCache.get(graph.session.value?.effectiveAddons().orEmpty(), "series", id) }
        }.orEmpty()
        value = titleKey to urls
    }
    val inheritedLogos = seasonLogos.takeIf { it.first == titleKey }?.second.orEmpty()
    val logosLoading = metadataLoading ||
        (needTmdb && (fetched.first != titleKey || fetched.third)) ||
        (needFanart && (fanart.first != titleKey || fanart.third)) ||
        (inherit && (seasonLogos.first != titleKey || seasonLogos.second == null))
    val preview = meta ?: return TitleArt.NONE
    return remember(titleKey, preview.logo, preview.background, safeFull?.logo, safeFull?.background, lookupId, tmdb, fanartLogos, inheritedLogos, logosLoading, size) {
        TitleArt(
            logos = (com.vyrk.core.art.Artwork.logos(preview, safeFull, tmdb?.logo) + fanartLogos + inheritedLogos).distinct(),
            backdrops = com.vyrk.core.art.Artwork.backdrops(preview, safeFull, tmdb?.backdrop, size),
            logosLoading = logosLoading,
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
