package com.vyrk.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.catalog.DiscoveryRules
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.browse.*
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.overlays.OverlayWindow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus

/** An add-on catalogue grid. Filters apply on selection, never merely on focus. */
@Composable
fun DiscoveryScreen(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val settings by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val library by session.library.collectAsState()
    val state by session.state.collectAsState()
    var type by remember(session) { mutableStateOf("movie") }
    var catalogKey by remember(session, settings.animeMode) { mutableStateOf<String?>(null) }
    var selectedGenre by remember(session, settings.animeMode) { mutableStateOf<String?>(null) }
    val catalogs = remember(addons, settings.hiddenRows, type, settings.animeMode) {
        DiscoveryRules.catalogs(session.effectiveAddons(), type, settings.animeMode, settings.hiddenRows)
    }
    val ref = catalogs.firstOrNull { it.key == catalogKey } ?: catalogs.firstOrNull()
    val genre = ref?.let { DiscoveryRules.genre(it, settings.animeMode, selectedGenre) }
    val model = remember(session) { BrowseModel(graph.addonClient, Tab.DISCOVER, session.scope) }
    val row = remember(ref, settings.animeMode, type, genre) { ref?.let {
        RowState("discover:${it.key}:$type:$genre", RowKind.CATALOG, it, it.name, settings.animeMode, type, genre)
    } }
    DisposableEffect(row) { row?.let(model::ensureLoaded); onDispose { row?.job?.cancel() } }
    val controls = remember { List(3) { FocusRequester() } }
    var menu by remember { mutableStateOf<Int?>(null) }
    var returnTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(returnTo, menu) {
        if (menu == null) returnTo?.let { index ->
            withFrameNanos { }; runCatching { controls[index].requestFocus() }; returnTo = null
        }
    }
    fun closeMenu() { returnTo = menu; menu = null }
    val c = Vyrk.colors
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screenMargin, vertical = 12.dp)) {
        Text(str(R.string.tab_discover), style = Vyrk.type.headlineL, color = c.text)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().focusProperties { onEnter = { controls[0].requestFocus() } }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VyrkButton(tabLabel(if (type == "movie") Tab.MOVIES else Tab.SERIES) + " ▾", { menu = 0 },
                modifier = Modifier.focusRequester(controls[0]).traceFocus("discover:type"))
            VyrkButton((ref?.name ?: str(R.string.discover_catalog)) + " ▾", { menu = 1 },
                enabled = catalogs.isNotEmpty(), modifier = Modifier.weight(1f).focusRequester(controls[1]).traceFocus("discover:catalog"))
            VyrkButton((genre ?: str(R.string.anime_all_genres)) + " ▾", { menu = 2 },
                enabled = ref?.let { DiscoveryRules.genres(it, settings.animeMode).isNotEmpty() } == true,
                modifier = Modifier.focusRequester(controls[2]).traceFocus("discover:genre"))
        }
        Spacer(Modifier.height(8.dp))
        ref?.let { Text(it.addon.manifest.name, style = Vyrk.type.labelL, color = c.textMuted) }
        Spacer(Modifier.height(12.dp))
        if (ref == null) Text(str(R.string.discover_no_catalogs), color = c.textMuted)
        else if (row != null) key(row) {
            Column(Modifier.weight(1f)) {
                if (row.loading) Text(str(R.string.loading), color = c.textMuted)
                if (row.loadedOnce && row.items.isEmpty() && !row.loading && row.error == null)
                    Text(str(R.string.anime_no_matches), color = c.textMuted)
                LazyVerticalGrid(GridCells.Adaptive(130.dp), modifier = Modifier.fillMaxSize()
                    .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Up) controls[0].requestFocus() } },
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
                    itemsIndexed(row.items, key = { _, meta -> meta.type + ":" + meta.id }) { index, meta ->
                        // Load near the tail; sparse pages retain an explicit action instead of spinning.
                        if (index >= row.items.size - 8) LaunchedEffect(row.items.size) { model.loadMore(row) }
                        Poster(meta, 118.dp, posterState(library[meta.id], state.newEpisodes[meta.id].orEmpty()),
                            onClick = { app.push(Overlay.Info(meta, null)) },
                            onLongClick = { app.push(Overlay.ItemMenu(meta, null)) }, onPlayPause = {},
                            traceLabel = "discover:poster#$index")
                    }
                    if (row.error != null || row.hasMore && row.loadedOnce) item(key = "discover-page") {
                        VyrkButton(str(if (row.error != null) R.string.retry else R.string.anime_load_more),
                            { if (row.error != null) model.retry(row) else model.loadMore(row) },
                            enabled = !row.loading, modifier = Modifier.traceFocus("discover:page"))
                    }
                }
            }
        }
    }
    menu?.let { which ->
        val values: List<Pair<String?, String>> = when (which) {
            0 -> listOf("movie" to tabLabel(Tab.MOVIES), "series" to tabLabel(Tab.SERIES))
            1 -> catalogs.map { it.key to "${it.name} · ${it.addon.manifest.name}" }
            else -> ref?.let { r ->
                (if (r.catalog.requiresExtra("genre")) emptyList() else listOf(null to str(R.string.anime_all_genres))) +
                    DiscoveryRules.genres(r, settings.animeMode).map { it to it }
            }.orEmpty()
        }
        val selected = when (which) { 0 -> type; 1 -> ref?.key; else -> genre }
        val title = str(when (which) { 0 -> R.string.discover_type; 1 -> R.string.discover_catalog; else -> R.string.discover_genre })
        OverlayWindow(onDismiss = ::closeMenu) {
            val first = remember { FocusRequester() }
            val start = values.indexOfFirst { it.first == selected }.coerceAtLeast(0)
            val list = rememberLazyListState(start)
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
            Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
                Column(Modifier.width(520.dp).heightIn(max = 420.dp).background(c.panel, RoundedCornerShape(22.dp)).padding(24.dp)) {
                    Text(title, style = Vyrk.type.headlineS, color = c.text)
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(values) { index, (value, label) ->
                            VyrkButton((if (value == selected) "✓ " else "") + label, onClick = {
                                when (which) {
                                    0 -> { type = value!!; catalogKey = null; selectedGenre = null }
                                    1 -> { catalogKey = value; selectedGenre = null }
                                    else -> selectedGenre = value
                                }
                                closeMenu()
                            }, modifier = Modifier.fillMaxWidth().then(if (index == start) Modifier.focusRequester(first) else Modifier)
                                .traceFocus("discover:option:$which#$index"))
                        }
                    }
                }
            }
        }
    }
}
