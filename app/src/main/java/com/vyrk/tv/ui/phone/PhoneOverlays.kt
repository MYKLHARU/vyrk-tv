package com.vyrk.tv.ui.phone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.addon.AddonUrls
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.Redact
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.catalog.DiscoveryRules
import com.vyrk.core.tmdb.PersonCredit
import com.vyrk.core.tmdb.TmdbClient
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.player.Playback
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.BrowseModel
import com.vyrk.tv.ui.browse.PosterState
import com.vyrk.tv.ui.browse.RowKind
import com.vyrk.tv.ui.browse.RowState
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.overlays.PersonPhoto
import com.vyrk.tv.ui.screens.tabLabel
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.launch

/** Everything opened over the tabs, in order: pages fill the screen, menus and prompts are sheets. */
@Composable
fun PhoneOverlays() {
    val app = LocalApp.current
    app.overlays.toList().forEach { o ->
        key(o) {
            val close = { app.overlays.remove(o); Unit }
            when (o) {
                is Overlay.Info -> PhonePage(close) { PhoneInfoPage(o, close) }
                is Overlay.SeeAll -> PhonePage(close) { PhoneSeeAll(o, close) }
                is Overlay.Person -> PhonePage(close) { PhonePerson(o, close) }
                is Overlay.ItemMenu -> PhoneItemMenu(o, close)
                is Overlay.InstallConfirm -> PhoneInstallConfirm(o, close)
                is Overlay.AddonQr -> PhoneAddonConfigure(o, close)
                is Overlay.Message -> PhoneSheet(true, close, o.title) {
                    Text(o.body, style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary)
                    Spacer(Modifier.height(16.dp))
                    PhoneButton(str(R.string.ok), close, style = PhoneButtonStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), tag = "message:ok")
                }
            }
        }
    }
}

/** Long press on a title: play, details, library, watched, Continue Watching, hide row. */
@Composable
private fun PhoneItemMenu(o: Overlay.ItemMenu, close: () -> Unit) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val session = LocalSession.current ?: return
    val library by session.library.collectAsState()
    val item = library[o.meta.id]
    val inCw = item?.isInContinueWatching == true
    val inLib = item?.inLibrary == true
    val removedMsg = str(R.string.removed_from_cw, o.meta.name)
    PhoneSheet(true, close, o.meta.name) {
        PhoneListRow(str(if (inCw) R.string.resume else R.string.play), { close(); Playback.start(activity, graph, session, o.meta) }, icon = R.drawable.ic_play, tag = "menu:play")
        PhoneListRow(str(R.string.more_info), { app.replaceTop(Overlay.Info(o.meta, null)) }, icon = R.drawable.ic_info, tag = "menu:info")
        PhoneListRow(str(if (inLib) R.string.remove_from_library else R.string.add_to_library), {
            if (inLib) session.removeFromLibrary(o.meta.id) else session.addToLibrary(o.meta); close()
        }, icon = if (inLib) R.drawable.ic_trash else R.drawable.ic_plus, tag = "menu:library")
        PhoneListRow(str(if (item?.watched == true) R.string.mark_unwatched else R.string.mark_watched), {
            session.markWatched(o.meta, item?.watched != true); close()
        }, icon = R.drawable.ic_check, tag = "menu:watched")
        if (inCw) PhoneListRow(str(R.string.remove_from_cw), {
            session.removeFromContinueWatching(o.meta.id); app.showToast(removedMsg); close()
        }, icon = R.drawable.ic_close, tag = "menu:remove_cw")
        val rowKey = o.origin?.rowKey
        if (rowKey != null && rowKey != com.vyrk.tv.ui.browse.CW_ROW) PhoneListRow(str(R.string.hide_row), {
            val catalogKey = rowKey.removeSuffix(RowKind.CATALOG.name).removeSuffix(RowKind.TOP10.name)
            session.updateSettings { it.copy(hiddenRows = it.hiddenRows + catalogKey) }; close()
        }, icon = R.drawable.ic_eye_off, tag = "menu:hide_row")
    }
}

@Composable
private fun PhoneInstallConfirm(o: Overlay.InstallConfirm, close: () -> Unit) {
    val app = LocalApp.current
    val session = LocalSession.current ?: return
    val scope = rememberCoroutineScope()
    val c = Vyrk.colors
    val d = o.descriptor
    val installedMsg = d?.let { str(R.string.installed_addon, it.manifest.name) }.orEmpty()
    val needsServer = d?.let { CatalogRules.needsStreamingServer(it) } == true
    PhoneSheet(true, close, str(R.string.install_addon_title)) {
        if (o.external) Text(str(R.string.install_from_link), style = Vyrk.type.bodyM, color = c.warning)
        Spacer(Modifier.height(8.dp))
        when {
            o.error != null -> Text(str(R.string.install_fetch_failed, Redact.url(o.input)), style = Vyrk.type.bodyM, color = c.danger)
            d == null -> Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textSecondary)
            else -> {
                Text("${d.manifest.name}  ·  v${d.manifest.version}", style = Vyrk.type.titleL, color = c.text)
                d.manifest.description?.let { Text(it, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(8.dp))
                ChipFlow(spacing = 6.dp) {
                    d.manifest.resources.forEach { Tag(it.name) }
                    d.manifest.types.forEach { Tag(it, background = c.gold.copy(alpha = 0.16f), color = c.gold) }
                }
                Spacer(Modifier.height(8.dp))
                Text(Redact.url(d.transportUrl), style = Vyrk.type.labelM, color = c.textMuted)
                if (needsServer) Text(str(R.string.needs_streaming_server), style = Vyrk.type.bodyS, color = c.warning)
                if (AddonUrls.isUnencryptedPublic(d.transportUrl)) Text(str(R.string.addon_not_encrypted), style = Vyrk.type.bodyS, color = c.warning)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d != null && !needsServer) PhoneButton(str(R.string.install), {
                scope.launch { session.install(d); app.showToast(installedMsg) }
                close()
            }, style = PhoneButtonStyle.PRIMARY, modifier = Modifier.weight(1f), tag = "install:confirm")
            PhoneButton(str(R.string.cancel), close, modifier = Modifier.weight(1f), tag = "install:cancel")
        }
    }
}

/** On a phone the add-on's configuration page opens right here, in the browser. */
@Composable
private fun PhoneAddonConfigure(o: Overlay.AddonQr, close: () -> Unit) {
    val context = LocalContext.current
    val url = AddonUrls.configureUrl(o.descriptor.transportUrl) ?: o.descriptor.transportUrl
    PhoneSheet(true, close, str(R.string.configure_addon, o.descriptor.manifest.name)) {
        Text(Redact.url(url), style = Vyrk.type.labelM, color = Vyrk.colors.textMuted)
        Spacer(Modifier.height(16.dp))
        PhoneButton(str(R.string.configure), {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            close()
        }, icon = R.drawable.ic_globe, style = PhoneButtonStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), tag = "addon:configure_open")
        Spacer(Modifier.height(8.dp))
        PhoneButton(str(R.string.done), close, modifier = Modifier.fillMaxWidth())
    }
}

/** Posters in a grid that fits the screen's width; [minWidth] sets how many fit a row. */
@Composable
fun PosterGrid(
    items: List<MetaPreview>,
    stateOf: (MetaPreview) -> PosterState,
    onClick: (MetaPreview) -> Unit,
    onLongClick: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    minWidth: Dp = 104.dp,
    tagPrefix: String = "grid",
    onNearEnd: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val cols = ((maxWidth - PhoneMargin * 2 + 10.dp) / (minWidth + 10.dp)).toInt().coerceAtLeast(3)
        val w = (maxWidth - PhoneMargin * 2 - 10.dp * (cols - 1)) / cols
        LazyVerticalGrid(
            GridCells.Fixed(cols), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PhoneMargin, end = PhoneMargin, top = top, bottom = BottomBarHeight + nav + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            itemsIndexed(items, key = { _, m -> m.type + ":" + m.id }) { i, m ->
                if (onNearEnd != null && i >= items.size - 9) LaunchedEffect(items.size) { onNearEnd() }
                PhonePoster(m, w, stateOf(m), onClick = { onClick(m) }, onLongClick = { onLongClick(m) }, tag = "$tagPrefix#$i")
            }
            if (footer != null) item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    }
}

@Composable
private fun PhoneSeeAll(o: Overlay.SeeAll, close: () -> Unit) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val session = LocalSession.current ?: return
    val library by session.library.collectAsState()
    val state by session.state.collectAsState()
    val model = remember { BrowseModel(graph.addonClient, Tab.HOME, session.scope) }
    val row = remember { RowState(o.ref.key + "all", RowKind.CATALOG, o.ref, o.title, o.animeOnly, o.browseType, o.genre) }
    LaunchedEffect(Unit) { model.ensureLoaded(row) }
    Column(Modifier.fillMaxSize()) {
        PhoneTopBar(o.title, close)
        PosterGrid(row.items, { posterState(library[it.id], state.newEpisodes[it.id].orEmpty()) },
            onClick = { app.push(Overlay.Info(it, null)) }, onLongClick = { app.push(Overlay.ItemMenu(it, null)) },
            tagPrefix = "seeall", onNearEnd = { model.loadMore(row) },
            header = { if (row.items.isEmpty()) RowStatus(row, model) })
    }
}

@Composable
private fun RowStatus(row: RowState, model: BrowseModel) {
    val c = Vyrk.colors
    Column(Modifier.padding(vertical = 12.dp)) {
        when {
            row.error != null -> {
                Text(str(R.string.row_error), style = Vyrk.type.bodyM, color = c.textMuted)
                Spacer(Modifier.height(8.dp))
                PhoneButton(str(R.string.retry), { model.retry(row) })
            }
            row.loading || !row.loadedOnce -> Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textMuted)
            else -> Text(str(if (row.animeOnly) R.string.anime_no_matches else R.string.row_empty), style = Vyrk.type.bodyM, color = c.textMuted)
        }
    }
}

/** A person: photo, facts and biography, then what they're known for as posters. */
@Composable
private fun PhonePerson(o: Overlay.Person, close: () -> Unit) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf<com.vyrk.core.tmdb.Person?>(null) }
    var credits by remember { mutableStateOf<List<PersonCredit>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var dept by remember { mutableStateOf("all") }
    var bioOpen by remember { mutableStateOf(false) }
    val cantOpen = str(R.string.cant_open_title)
    LaunchedEffect(o.id) {
        person = graph.tmdb.person(o.id)
        credits = graph.tmdb.personCredits(o.id)
        failed = person == null
    }
    val c = Vyrk.colors
    val list = credits.orEmpty()
    val depts = list.map { it.department }.distinct().sortedBy { if (it == "Acting") 0 else 1 }
    val shown = list.filter { dept == "all" || it.department == dept }.take(60)
    val previews = shown.map { pc -> MetaPreview(id = "tmdb:${pc.type}:${pc.tmdbId}", type = pc.type, name = pc.title, poster = TmdbClient.image(pc.posterPath, "w342"), releaseInfo = pc.year) }
    Column(Modifier.fillMaxSize()) {
        PhoneTopBar(null, close)
        PosterGrid(previews, { PosterState(0f, false, false) }, tagPrefix = "person:credit",
            onClick = { m ->
                val pc = shown.getOrNull(previews.indexOf(m)) ?: return@PosterGrid
                scope.launch {
                    val imdb = graph.tmdb.imdbId(pc.tmdbId, pc.type)
                    if (imdb != null) app.push(Overlay.Info(MetaPreview(id = imdb, type = pc.type, name = pc.title, poster = m.poster, releaseInfo = pc.year), null))
                    else { app.showToast(cantOpen); app.overlays.clear(); app.searchQuery = pc.title; app.section = Section.SEARCH }
                }
            },
            onLongClick = {},
            header = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonPhoto(o.name, person?.profilePath, 96.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person?.name ?: o.name, style = Vyrk.type.headlineL, color = c.text)
                            person?.knownFor?.let { Text(str(R.string.known_for, it), style = Vyrk.type.labelL, color = c.accent) }
                            person?.birthday?.let { b -> Text(str(R.string.born_on, listOfNotNull(b, person?.placeOfBirth).joinToString(" · ")), style = Vyrk.type.bodyS, color = c.textSecondary) }
                            person?.deathday?.let { Text(str(R.string.died_on, it), style = Vyrk.type.bodyS, color = c.textSecondary) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        failed -> Text(str(R.string.person_unavailable), style = Vyrk.type.bodyM, color = c.textMuted)
                        person == null -> Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textMuted)
                        else -> person?.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                            Text(bio, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = if (bioOpen) 60 else 6, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.press({ bioOpen = !bioOpen }, pressedScale = 1f))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (depts.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { PhoneChip(str(R.string.filter_all), dept == "all", { dept = "all" }) }
                        items(depts.take(5)) { d -> PhoneChip(d, dept == d, { dept = d }) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            },
            footer = { Text(TmdbClient.ATTRIBUTION, style = Vyrk.type.labelS, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) },
        )
    }
}

/** Discover: a type, a catalogue and a genre, chosen from sheets, over the catalogue's posters. */
@Composable
fun PhoneDiscover(session: ProfileSession, topPadding: Dp) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val settings by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val library by session.library.collectAsState()
    val state by session.state.collectAsState()
    var type by remember(session) { mutableStateOf("movie") }
    var catalogKey by remember(session) { mutableStateOf<String?>(null) }
    var selectedGenre by remember(session) { mutableStateOf<String?>(null) }
    val catalogs = remember(addons, settings.hiddenRows, type) { DiscoveryRules.catalogs(session.effectiveAddons(), type, false, settings.hiddenRows) }
    val ref = catalogs.firstOrNull { it.key == catalogKey } ?: catalogs.firstOrNull()
    val genre = ref?.let { DiscoveryRules.genre(it, false, selectedGenre) }
    val model = remember(session) { BrowseModel(graph.addonClient, Tab.DISCOVER, session.scope) }
    val row = remember(ref, type, genre) { ref?.let { RowState("discover:${it.key}:$type:$genre", RowKind.CATALOG, it, it.name, false, type, genre) } }
    DisposableEffect(row) { row?.let(model::ensureLoaded); onDispose { row?.job?.cancel() } }
    var menu by remember { mutableStateOf<Int?>(null) }
    val c = Vyrk.colors
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    PosterGrid(row?.items.orEmpty(), { posterState(library[it.id], state.newEpisodes[it.id].orEmpty()) },
        onClick = { app.push(Overlay.Info(it, null)) }, onLongClick = { app.push(Overlay.ItemMenu(it, null)) },
        top = statusTop + topPadding, tagPrefix = "discover", onNearEnd = { row?.let(model::loadMore) },
        header = {
            Column(Modifier.padding(bottom = 6.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PhoneChip(tabLabel(if (type == "movie") Tab.MOVIES else Tab.SERIES) + "  ▾", true, { menu = 0 }, tag = "discover:type") }
                    if (catalogs.isNotEmpty()) item { PhoneChip((ref?.name ?: str(R.string.discover_catalog)) + "  ▾", false, { menu = 1 }, tag = "discover:catalog") }
                    if (ref?.let { DiscoveryRules.genres(it, false).isNotEmpty() } == true) item {
                        PhoneChip((genre ?: str(R.string.anime_all_genres)) + "  ▾", genre != null, { menu = 2 }, tag = "discover:genre")
                    }
                }
                ref?.let { Text(it.addon.manifest.name, style = Vyrk.type.labelM, color = c.textMuted, modifier = Modifier.padding(top = 6.dp)) }
                if (ref == null) Text(str(R.string.discover_no_catalogs), style = Vyrk.type.bodyM, color = c.textMuted, modifier = Modifier.padding(top = 12.dp))
                else if (row != null && row.items.isEmpty()) RowStatus(row, model)
            }
        },
    )
    val which = menu
    val values: List<Pair<String?, String>> = when (which) {
        0 -> listOf("movie" to tabLabel(Tab.MOVIES), "series" to tabLabel(Tab.SERIES))
        1 -> catalogs.map { it.key to "${it.name} · ${it.addon.manifest.name}" }
        2 -> ref?.let { r -> (if (r.catalog.requiresExtra("genre")) emptyList() else listOf(null to str(R.string.anime_all_genres))) + DiscoveryRules.genres(r, false).map { it to it } }.orEmpty()
        else -> emptyList()
    }
    val selected = when (which) { 0 -> type; 1 -> ref?.key; else -> genre }
    val title = when (which) { 0 -> str(R.string.discover_type); 1 -> str(R.string.discover_catalog); 2 -> str(R.string.discover_genre); else -> null }
    PhoneSheet(which != null, { menu = null }, title) {
        values.forEachIndexed { i, (value, label) ->
            PhoneListRow(label, {
                when (which) {
                    0 -> if (type != value) { type = value!!; catalogKey = null; selectedGenre = null }
                    1 -> if (ref?.key != value) { catalogKey = value; selectedGenre = null }
                    else -> selectedGenre = value
                }
                menu = null
            }, selected = value == selected, tag = "discover:option#$i")
        }
    }
}
