package com.vyrk.tv.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.tv.BuildConfig
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.player.Playback
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.browse.BrowseModel
import com.vyrk.tv.ui.browse.HERO_COUNT
import com.vyrk.tv.ui.browse.HERO_SOURCE_ROWS
import com.vyrk.tv.ui.browse.HeroLogo
import com.vyrk.tv.ui.browse.RankNumber
import com.vyrk.tv.ui.browse.RowKind
import com.vyrk.tv.ui.browse.RowState
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.browse.produceMeta
import com.vyrk.tv.ui.browse.rememberTitleArt
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.Wordmark
import com.vyrk.tv.ui.screens.tabIcon
import com.vyrk.tv.ui.screens.tabLabel
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Poster width in rows on phones. */
private val PosterW = 112.dp

/**
 * A catalogue page: the brand and the page strip at the top, the featured titles (swipe between
 * them), Continue Watching on Home, then the catalogue rows. Discover is its own page.
 */
@Composable
fun PhoneBrowse(session: ProfileSession, tabs: List<Tab>, model: (Tab) -> BrowseModel) {
    val app = LocalApp.current
    val tab = app.tab.takeIf { it in tabs } ?: Tab.HOME
    val listStates = remember { mutableMapOf<Tab, androidx.compose.foundation.lazy.LazyListState>() }
    val listState = listStates.getOrPut(tab) { androidx.compose.foundation.lazy.LazyListState() }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(tab) {
            if (tab == Tab.DISCOVER) PhoneDiscover(session, topPadding = PhoneHeaderHeight)
            else CataloguePage(session, model(tab), listState)
        }
        // The header darkens as the page scrolls under it.
        val scrolled by remember(listState) { derivedStateOf { tab == Tab.DISCOVER || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120 } }
        PhoneHeader(tabs, tab, solid = scrolled) { app.tab = it }
    }
}

/** Height of the brand and page strip under the status bar. */
val PhoneHeaderHeight = 104.dp

@Composable
private fun PhoneHeader(tabs: List<Tab>, current: Tab, solid: Boolean, onSelect: (Tab) -> Unit) {
    val c = Vyrk.colors
    val shade = c.shade
    val bg by androidx.compose.animation.core.animateFloatAsState(if (solid) 0.94f else 0f, label = "header")
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(shade.copy(alpha = maxOf(0.75f, bg)), shade.copy(alpha = maxOf(0.35f, bg)), shade.copy(alpha = bg * 0.9f))))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = PhoneMargin), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(20.dp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = PhoneMargin), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.key }) { t ->
                PhoneChip(tabLabel(t), t == current, { onSelect(t) }, tag = "tab:${t.key}", icon = tabIcon(t))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CataloguePage(session: ProfileSession, model: BrowseModel, listState: androidx.compose.foundation.lazy.LazyListState) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val settings by session.settings.collectAsState()
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    val isHome = model.tab == Tab.HOME

    // Continue Watching from the live library, its gaps filled from the title's full details.
    val cwLibrary = remember(library, sstate.hiddenContinueWatching) {
        LibraryRules.continueWatching(library.values).filter { it.id !in sstate.hiddenContinueWatching }
    }
    val cwMetas by remember(cwLibrary) { derivedStateOf { graph.metaCache.version; cwLibrary.count { graph.metaCache.peek(it.type, it.id) != null } } }
    val cwItems = remember(cwLibrary, cwMetas) {
        cwLibrary.map { li ->
            val full = graph.metaCache.peek(li.type, li.id)
            MetaPreview(id = li.id, type = li.type, name = li.name.ifBlank { full?.name.orEmpty() },
                poster = li.poster ?: full?.poster ?: com.vyrk.core.art.Artwork.metahubPoster(li.id),
                background = full?.background, logo = full?.logo, genres = full?.allGenres.orEmpty())
        }.filter {
            // Home never shows anime, Continue Watching included.
            val full = graph.metaCache.peek(it.type, it.id)
            !isHome || full?.malId == null && full?.kitsuId == null && !com.vyrk.core.catalog.AnimeBrowse.isAnimeTitle(it)
        }
    }
    // Every title's details: to fill blank cards, and to tell anime apart.
    LaunchedEffect(cwLibrary.map { it.id }) {
        val addons = session.effectiveAddons()
        cwLibrary.take(24).forEach { graph.metaCache.get(addons, it.type, it.id) }
    }

    // Featured titles: on Home a mix taken in turn from the first rows, elsewhere the first row with artwork.
    val heroCandidates by remember(model.rows) {
        derivedStateOf {
            fun art(r: RowState) = r.items.filter { it.background != null }
            if (!isHome) model.rows.firstOrNull { art(it).isNotEmpty() }?.let { art(it).take(HERO_COUNT) }.orEmpty()
            else if (!model.rows.take(HERO_SOURCE_ROWS).all { it.loadedOnce }) emptyList()
            else {
                val pools = model.rows.map(::art).filter { it.isNotEmpty() }
                val picked = LinkedHashMap<String, MetaPreview>()
                var i = 0
                while (picked.size < HERO_COUNT && pools.any { i < it.size }) {
                    pools.forEach { p -> p.getOrNull(i)?.let { m -> if (picked.size < HERO_COUNT) picked.putIfAbsent(m.id, m) } }
                    i++
                }
                picked.values.toList()
            }
        }
    }
    LaunchedEffect(heroCandidates.map { it.id }) { model.lockHero(heroCandidates) }
    val heroItems = model.heroItems.ifEmpty { heroCandidates }
    val heroOn = settings.heroEnabled && model.tab != Tab.LIVE
    val showTop10Card = isHome && !model.hasTop10Catalogs && !settings.top10CardDismissed && BuildConfig.TOP10_ADDON_URL.isNotBlank()

    val recRows = com.vyrk.tv.ui.browse.rememberRecommendationRows(session, enabled = isHome)
    val airingRows = com.vyrk.tv.ui.browse.rememberAiringRows(session, enabled = model.tab == Tab.ANIME || (isHome && model.animeOnly))
    val bottom = bottomContentPadding()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = bottom) {
        if (heroOn) item(key = "hero") {
            if (heroItems.isEmpty()) Box(Modifier.fillMaxWidth().height(PhoneHeaderHeight + 260.dp))
            else PhoneHero(session, model, heroItems)
        } else item(key = "header-space") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(PhoneHeaderHeight)) }
        if (showTop10Card) item(key = "top10") {
            Top10Suggestion(
                onAdd = {
                    val url = BuildConfig.TOP10_ADDON_URL
                    app.push(Overlay.InstallConfirm(null, url, external = false))
                    scope.launch {
                        val d = session.fetchManifest(url)
                        val top = app.overlays.lastOrNull()
                        if (top is Overlay.InstallConfirm && top.input == url) app.replaceTop(top.copy(descriptor = d.getOrNull(), error = d.exceptionOrNull()?.let { "fetch" }))
                    }
                },
                onNotNow = { session.updateSettings { it.copy(top10CardDismissed = true) } },
            )
        }
        if (isHome && cwItems.isNotEmpty()) item(key = "cw") {
            Column(Modifier.padding(top = 20.dp)) {
                PhoneSectionHeader(str(R.string.continue_watching))
                Spacer(Modifier.height(4.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = PhoneMargin), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(cwItems, key = { _, m -> m.type + m.id }) { i, m ->
                        ContinueCard(m, library[m.id], tag = "cw#$i",
                            onClick = { Playback.start(activity, graph, session, m) },
                            onLongClick = { app.push(Overlay.ItemMenu(m, null)) })
                    }
                }
            }
        }
        if (model.built && model.rows.isEmpty()) item(key = "empty") {
            Text(str(R.string.row_empty), style = Vyrk.type.bodyL, color = Vyrk.colors.textMuted, modifier = Modifier.padding(PhoneMargin))
        }
        items(airingRows + model.rows.take(2) + recRows + model.rows.drop(2), key = { it.key }) { row ->
            LaunchedEffect(row) { model.ensureLoaded(row) }
            CatalogueRow(row, model, library, sstate.newEpisodes)
        }
    }
}

/** The featured titles: swipe between them; they move on by themselves unless that is turned off. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneHero(session: ProfileSession, model: BrowseModel, items: List<MetaPreview>) {
    val app = LocalApp.current
    val settings by session.settings.collectAsState()
    val pager = rememberPagerState(initialPage = model.heroIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    LaunchedEffect(pager.currentPage) { model.heroIndex = pager.currentPage }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(items.size, settings.heroRotation, settings.reduceMotion) {
        if (items.size < 2 || !settings.heroRotation || settings.reduceMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(8_000)
                if (app.overlays.isEmpty() && !pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Tall enough for the artwork to read, short enough to show the first row below.
        // Landscape: never taller than the screen, so the title and Watch now stay in view.
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val h = minOf((maxWidth * 1.25f).coerceIn(440.dp, 620.dp), screenH * 0.92f)
        HorizontalPager(pager, Modifier.fillMaxWidth().height(h), key = { items[it].type + items[it].id }) { page ->
            HeroPage(session, items[page], h)
        }
        if (items.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 16.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Vyrk.colors.gold else Vyrk.colors.text.copy(alpha = 0.3f)))
            }
        }
    }
}

@Composable
private fun HeroPage(session: ProfileSession, meta: MetaPreview, height: Dp) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val library by session.library.collectAsState()
    val details = produceMeta(session, meta)
    val full by details
    val art = rememberTitleArt(meta, full, metadataLoading = details.loading)
    val c = Vyrk.colors
    val shade = c.shade
    Box(Modifier.fillMaxWidth().height(height).press({ app.push(Overlay.Info(meta, null)) }, pressedScale = 1f, tag = "hero")) {
        // Portrait screens crop the landscape backdrop around its centre; the poster is the fallback.
        val urls = (art.backdrops + listOfNotNull(meta.poster)).distinct()
        var attempt by remember(urls) { mutableIntStateOf(0) }
        urls.getOrNull(attempt)?.let { u ->
            AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { if (urls.getOrNull(attempt) == u) attempt += 1 })
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to shade.copy(alpha = 0.55f), 0.22f to Color.Transparent, 0.48f to Color.Transparent, 0.78f to c.background.copy(alpha = 0.86f), 1f to c.background,
        )))
        // On wide screens (landscape, tablets) the title and buttons keep a phone's width.
        Column(Modifier.align(Alignment.BottomStart).widthIn(max = 560.dp).fillMaxWidth().padding(start = PhoneMargin, end = PhoneMargin, bottom = 22.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp)) {
                HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), full?.name ?: meta.name, art.logos,
                    maxWidth * 0.78f, 96.dp, Vyrk.type.cinematicM, Modifier.fillMaxHeight(), loading = art.logosLoading)
            }
            Spacer(Modifier.height(10.dp))
            val facts = listOfNotNull(
                (full?.releaseInfo ?: meta.releaseInfo)?.takeIf { it.isNotBlank() }?.take(4),
                (full?.allGenres ?: meta.genres).take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                com.vyrk.core.addon.RuntimeText.format(full?.runtime ?: meta.runtime),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(facts.joinToString("  ·  "), style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                (full?.imdbRating ?: meta.imdbRating)?.takeIf { it.isNotBlank() }?.let { com.vyrk.tv.ui.components.Tag("IMDb $it", color = c.onGold, background = c.gold) }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val inCw = library[meta.id]?.isInContinueWatching == true
                PhoneButton(str(if (inCw) R.string.resume else R.string.watch_now), { Playback.start(activity, graph, session, meta) },
                    icon = R.drawable.ic_play, style = PhoneButtonStyle.PRIMARY, modifier = Modifier.weight(1f), tag = "hero:watch")
                val inLib = library[meta.id]?.inLibrary == true
                PhoneIconButton(if (inLib) R.drawable.ic_check else R.drawable.ic_plus, str(if (inLib) R.string.in_library else R.string.add_to_library),
                    { if (inLib) session.removeFromLibrary(meta.id) else session.addToLibrary(meta) }, active = inLib, tag = "hero:library")
                PhoneIconButton(R.drawable.ic_info, str(R.string.more_info), { app.push(Overlay.Info(meta, null)) }, tag = "hero:info")
            }
        }
    }
}

/** A catalogue row: its title and See all, then posters; more load as you reach the end. */
@Composable
private fun CatalogueRow(row: RowState, model: BrowseModel, library: Map<String, LibraryItem>, newEpisodes: Map<String, Set<String>>) {
    val app = LocalApp.current
    val c = Vyrk.colors
    val top10 = row.kind == RowKind.TOP10
    Column(Modifier.padding(top = 20.dp)) {
        val ref = row.ref
        PhoneSectionHeader(row.title, action = if (ref != null && !top10) str(R.string.see_all) else null, onAction = ref?.let { r ->
            { app.push(Overlay.SeeAll(row.title, r, row.animeOnly, row.browseType, row.genre)) }
        })
        Spacer(Modifier.height(4.dp))
        when {
            row.items.isEmpty() && row.error != null -> Row(Modifier.padding(horizontal = PhoneMargin), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.row_error), style = Vyrk.type.bodyM, color = c.textMuted, modifier = Modifier.weight(1f))
                PhoneButton(str(R.string.retry), { model.retry(row) }, height = 40.dp)
            }
            row.items.isEmpty() && row.loadedOnce && !row.loading ->
                Text(str(R.string.row_empty), style = Vyrk.type.bodyM, color = c.textMuted, modifier = Modifier.padding(horizontal = PhoneMargin))
            row.items.isEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = PhoneMargin), horizontalArrangement = Arrangement.spacedBy(10.dp), userScrollEnabled = false) {
                items(6) { Box(Modifier.width(PosterW).height(PosterW * 1.5f).clip(RoundedCornerShape(10.dp)).background(c.raised.copy(alpha = 0.6f))) }
            }
            else -> {
                val state = rememberLazyListState()
                val nearEnd by remember(state, row) { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= row.items.size - 6 } }
                LaunchedEffect(nearEnd, row.items.size) { if (nearEnd) model.loadMore(row) }
                LazyRow(state = state, contentPadding = PaddingValues(horizontal = PhoneMargin), horizontalArrangement = Arrangement.spacedBy(if (top10) 4.dp else 10.dp)) {
                    itemsIndexed(row.items, key = { _, m -> m.type + ":" + m.id }) { i, m ->
                        val poster = @Composable {
                            PhonePoster(m, PosterW, posterState(library[m.id], newEpisodes[m.id].orEmpty(), row.labels[m.id]),
                                onClick = { app.push(Overlay.Info(m, null)) }, onLongClick = { app.push(Overlay.ItemMenu(m, null)) },
                                tag = "poster:${row.key}#$i")
                        }
                        if (top10) Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.offset(x = 10.dp)) { RankNumber(i + 1, PosterW * 1.05f) }
                            poster()
                        } else poster()
                    }
                }
            }
        }
    }
}

/** A Continue Watching card: the title's backdrop and logo, what's left, and a progress bar. Tap resumes. */
@Composable
private fun ContinueCard(meta: MetaPreview, item: LibraryItem?, tag: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Vyrk.colors
    val w = 232.dp
    val h = 130.dp
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.width(w).height(h).press(onClick, onLongClick, tag = tag).clip(shape)
            .background(Brush.linearGradient(listOf(c.raised, c.panel))).border(1.dp, Color.White.copy(alpha = 0.07f), shape),
    ) {
        val urls = remember(meta.id, meta.background, meta.poster) {
            listOfNotNull(com.vyrk.core.art.Artwork.forBackdrop(meta.background, "w780"), com.vyrk.core.art.Artwork.metahubBackground(meta.id), meta.poster).distinct()
        }
        var attempt by remember(urls) { mutableIntStateOf(0) }
        urls.getOrNull(attempt)?.let { u -> AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { if (urls.getOrNull(attempt) == u) attempt += 1 }) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to c.shade.copy(alpha = 0.92f))))
        Box(Modifier.align(Alignment.TopStart).padding(10.dp).width(w * 0.55f).height(h * 0.38f), contentAlignment = Alignment.BottomStart) {
            val logos = remember(meta.type, meta.id, meta.logo) { com.vyrk.core.art.Artwork.logos(meta, null, null) }
            HeroLogo(com.vyrk.core.art.Artwork.titleKey(meta.type, meta.id), meta.name, logos, w * 0.55f, h * 0.38f, Vyrk.type.titleM, Modifier.fillMaxHeight())
        }
        Box(Modifier.align(Alignment.Center).size(40.dp).background(c.shade.copy(alpha = 0.55f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(R.drawable.ic_play, null, Modifier.padding(start = 2.dp).size(16.dp), tint = Color.White)
        }
        val st = item?.state
        val episode = st?.let { LibraryRules.episodeLabel(item.id, it.videoId) }
        val leftMin = if (st != null && st.duration > 0 && st.duration > st.timeOffset) ((st.duration - st.timeOffset) / 60_000L).toInt() else null
        val left = leftMin?.takeIf { it > 0 }?.let { m -> str(R.string.time_left, if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m") }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 9.dp)) {
            listOfNotNull(episode, left).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = Vyrk.type.labelM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val progress = if (item?.isInContinueWatching == true) item.progress else 0f
            Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                if (progress > 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
    }
}

/** On Home without Top 10 rows: offers the Streaming Catalogs add-on that provides them. */
@Composable
private fun Top10Suggestion(onAdd: () -> Unit, onNotNow: () -> Unit) {
    val c = Vyrk.colors
    PhoneCard(Modifier.padding(start = PhoneMargin, end = PhoneMargin, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_trending, null, Modifier.size(22.dp), tint = c.gold)
            Spacer(Modifier.width(10.dp))
            Text(str(R.string.top10_card_title), style = Vyrk.type.titleL, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        Text(str(R.string.top10_card_body), style = Vyrk.type.bodyS, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneButton(str(R.string.top10_add), onAdd, style = PhoneButtonStyle.PRIMARY, icon = R.drawable.ic_plus, height = 42.dp, tag = "top10:add")
            PhoneButton(str(R.string.not_now), onNotNow, style = PhoneButtonStyle.GHOST, height = 42.dp, tag = "top10:not_now")
        }
    }
}

