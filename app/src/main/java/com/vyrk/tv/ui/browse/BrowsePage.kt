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
import kotlinx.coroutines.launch

const val CW_ROW = "cw"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowsePage(session: ProfileSession, model: BrowseModel, tabKey: String, onPlay: (MetaPreview, Origin?) -> Unit) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    val listState = rememberLazyListState()
    val flags = remember { PageFocusFlags() }
    val pageSpec = remember { PageSpec(flags) }
    val watchReq = remember { FocusRequester() }
    val top10Req = remember { FocusRequester() }
    val isHome = model.tab == Tab.HOME

    LaunchedEffect(addons, settings.hiddenRows) { model.rebuild(session.effectiveAddons(), settings.hiddenRows) }

    // Continue Watching follows the live library at once.
    val cwItems = remember(library, sstate.hiddenContinueWatching) {
        LibraryRules.continueWatching(library.values).filter { it.id !in sstate.hiddenContinueWatching }
            .map { MetaPreview(id = it.id, type = it.type, name = it.name, poster = it.poster) }
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
    val heroItems by remember(model.rows) {
        derivedStateOf { model.rows.firstOrNull { r -> r.items.any { it.background != null } }?.items?.filter { it.background != null }?.take(6).orEmpty() }
    }
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    var focusedPoster by remember { mutableStateOf<MetaPreview?>(null) }
    var focusedRowKey by remember { mutableStateOf<String?>(null) }
    val heroMeta = heroItems.getOrNull(heroIndex)
    val heroFull by produceMeta(session, heroMeta)
    val headerFull by produceMeta(session, focusedPoster, debounceMs = 250)
    val heroEnabled = settings.heroEnabled && heroItems.isNotEmpty() && model.tab != Tab.LIVE
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Rotation every 9 s, paused while focused, under overlays/previews, scrolled away, in the background or with Reduce motion.
    LaunchedEffect(heroItems.size, settings.heroRotation, settings.reduceMotion) {
        if (heroItems.size < 2 || !settings.heroRotation || settings.reduceMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(9_000)
                val atTop = listState.firstVisibleItemIndex == 0 && focusedPoster == null
                if (!heroFocused && app.overlays.isEmpty() && atTop) heroIndex = (heroIndex + 1) % heroItems.size
            }
        }
    }

    // Initial focus once per screen: restored poster (restart) or the hero's Watch now.
    var initialDone by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<Origin?>(null) }
    // Decide only once the first catalogue has answered, so the hero's presence is known.
    val firstLoaded = model.built && (model.rows.firstOrNull()?.loadedOnce == true || model.rows.isEmpty())
    LaunchedEffect(firstLoaded, rows.size) {

        if (initialDone || !firstLoaded) return@LaunchedEffect
        val mem = sstate.focus[tabKey]
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
    LaunchedEffect(app.focusHeroTick) {
        if (app.focusHeroTick > 0 && heroEnabled) { listState.scrollToItem(0); runCatching { watchReq.requestFocus() } }
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
        listState.scrollToItem(idx + headerItems)
    }


    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = maxHeight + Dimens.navHeight
        val heroH = (screenH * (if (isHome) 0.66f else 0.72f)) - Dimens.navHeight
        val headerH = screenH * 0.56f - Dimens.navHeight
        val density = LocalDensity.current
        val rowTitleH = 30.dp
        flags.posterTarget.set(with(density) { (headerH + rowTitleH).toPx() })

        // Backdrop: hero artwork, or the focused poster's artwork under the header.
        val backdropUrl = if (focusedPoster != null) (headerFull?.background ?: focusedPoster?.background) else heroFull?.background ?: heroMeta?.background
        val showBackdrop = focusedPoster == null && heroEnabled && listState.firstVisibleItemIndex == 0
        if (showBackdrop) Backdrop(backdropUrl, Modifier.fillMaxWidth().height(heroH + 40.dp))

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentPadding = PaddingValues(bottom = 200.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.rowGap),
            ) {
                if (heroEnabled) item(key = "hero") {
                    Hero(
                        items = heroItems, index = heroIndex.coerceAtMost(heroItems.lastIndex), full = heroFull, height = heroH,
                        hasProgress = heroMeta?.let { library[it.id]?.isInContinueWatching } == true,
                        watchReq = watchReq,
                        onWatch = { heroMeta?.let { onPlay(it, null) } },
                        onInfo = { heroMeta?.let { app.push(Overlay.Info(it, null)) } },
                        onTrailer = { heroMeta?.let { app.preview = it } },
                        onPrev = { heroIndex = (heroIndex - 1 + heroItems.size) % heroItems.size },
                        onNext = { heroIndex = (heroIndex + 1) % heroItems.size },
                        onFocusChanged = { f ->
                            heroFocused = f; flags.heroFocused.set(f)
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
                            flags.top10CardFocused.set(f)
                            if (f) { focusedPoster = null; scope.launch { listState.animateScrollToItem(0) } }
                        },
                    )
                }
                items(rows, key = { it.key }) { row ->
                    val origin = { i: Int, m: MetaPreview -> Origin(tabKey, row.key, i, m.id) }
                    BrowseRow(
                        row = row,
                        posterWidth = settings.posterSize.dp,
                        library = library,
                        newEpisodes = sstate.newEpisodes,
                        callbacks = PosterCallbacks(
                            onClick = { m, i ->
                                val inCw = library[m.id]?.isInContinueWatching == true
                                app.push(Overlay.Info(m, origin(i, m), focusResume = row.kind == RowKind.CONTINUE || inCw))
                            },
                            onLongClick = { m, i -> app.push(Overlay.ItemMenu(m, origin(i, m))) },
                            onPlayPause = { m, _ -> if (settings.previews != PreviewMode.OFF) app.preview = if (app.preview?.id == m.id) null else m },
                            onFocused = { m, i ->
                                if (app.preview != null && app.preview?.id != m.id) app.preview = null
                                focusedPoster = m; focusedRowKey = row.key
                                flags.heroFocused.set(false); flags.top10CardFocused.set(false)
                                session.rememberFocus(tabKey, FocusMemory(row.key, i, m.id))
                                if (row.kind != RowKind.CONTINUE && i >= row.items.size - 6) model.loadMore(row)
                            },
                        ),
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
        app.preview?.let { pm ->
            androidx.activity.compose.BackHandler { app.preview = null }
            com.vyrk.tv.ui.preview.PreviewCard(pm, sound = settings.previewSound, modifier = Modifier.padding(top = 12.dp, end = Dimens.screenMargin))
        }
        // Title header over the list while a poster has focus.
        val fp = focusedPoster
        if (fp != null && !heroFocused) {
            // Opaque: rows above the focused one scroll beneath it.
            Box(Modifier.fillMaxWidth().height(headerH).background(com.vyrk.tv.ui.theme.Vyrk.colors.background)) {
                Backdrop(headerFull?.background ?: fp.background, Modifier.fillMaxSize())
                TitleHeader(fp, headerFull, headerH, trailerPlaying = false)
                if (settings.previews == PreviewMode.AUTOMATIC && app.overlays.isEmpty()) {
                    HeaderTrailer(meta = fp, full = headerFull, sound = settings.previewSound, modifier = Modifier.padding(end = Dimens.screenMargin, top = 12.dp))
                }
            }
        }
    }
}

@Composable
fun produceMeta(session: ProfileSession, meta: MetaPreview?, debounceMs: Long = 0): androidx.compose.runtime.State<com.vyrk.core.addon.MetaItem?> {
    val graph = LocalGraph.current
    return androidx.compose.runtime.produceState(initialValue = meta?.let { graph.metaCache.peek(it.type, it.id) }, meta?.id) {
        val m = meta ?: run { value = null; return@produceState }
        graph.metaCache.peek(m.type, m.id)?.let { value = it; return@produceState }
        value = null
        if (debounceMs > 0) delay(debounceMs)
        value = graph.metaCache.get(session.effectiveAddons(), m.type, m.id)
    }
}
