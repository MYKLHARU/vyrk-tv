package com.vyrk.tv.ui.overlays

import com.vyrk.tv.ui.components.glassPanel
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import com.vyrk.core.addon.AddonUrls
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.Redact
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileColors
import com.vyrk.tv.data.Tab
import com.vyrk.tv.player.Playback
import com.vyrk.tv.ui.AppState
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.browse.BrowseModel
import com.vyrk.tv.ui.browse.CW_ROW
import com.vyrk.tv.ui.browse.Poster
import com.vyrk.tv.ui.browse.RowKind
import com.vyrk.tv.ui.browse.RowState
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.QrCode
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.screens.AlphaKeyboard
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.ReprovideAppDensity
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

/** Overlays are separate windows so focus can't escape beneath them. */
@Composable
fun Overlays() {
    val app = LocalApp.current
    app.overlays.forEachIndexed { i, o ->
        androidx.compose.runtime.key(o) {
            OverlayWindow(onDismiss = { closeOverlay(app, o) }) {
                when (o) {
                    is Overlay.Info -> InfoPage(o)
                    is Overlay.ItemMenu -> com.vyrk.tv.ui.components.MenuColors { ItemMenu(o) }
                    is Overlay.InstallConfirm -> com.vyrk.tv.ui.components.MenuColors { InstallConfirm(o) }
                    is Overlay.AddonQr -> com.vyrk.tv.ui.components.MenuColors { AddonQr(o) }
                    is Overlay.Message -> com.vyrk.tv.ui.components.MenuColors { MessageBox(o) }
                    is Overlay.SeeAll -> SeeAll(o)
                    is Overlay.Person -> PersonPage(o)
                }
            }
        }
    }
}

fun closeOverlay(app: AppState, o: Overlay) {
    app.overlays.remove(o)
    val origin = when (o) { is Overlay.Info -> o.origin; is Overlay.ItemMenu -> o.origin; else -> null }
    if (origin != null && app.overlays.isEmpty()) app.restore = origin
}

@Composable
fun OverlayWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.setDimAmount(0f)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        val layer = remember { com.vyrk.tv.ui.FocusTrace.enter() }
        androidx.compose.runtime.DisposableEffect(layer) { onDispose { com.vyrk.tv.ui.FocusTrace.exit(layer) } }
        androidx.compose.runtime.CompositionLocalProvider(com.vyrk.tv.ui.LocalTraceLayer provides layer) {
            ReprovideAppDensity {
                // Dialog windows bypass the activity, so report their keys to the focus trace here.
                // OK only counts once this window has seen it pressed: a long press that opened the
                // window keeps repeating and releasing here, and must not click Resume or Play.
                var okArmed by remember { androidx.compose.runtime.mutableStateOf(false) }
                Box(Modifier.fillMaxSize().onPreviewKeyEvent {
                    com.vyrk.tv.ui.KeyBus.onKey(it.nativeKeyEvent)
                    val ne = it.nativeKeyEvent
                    val ok = ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || ne.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        ne.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || ne.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                    when {
                        !ok -> false
                        ne.action == android.view.KeyEvent.ACTION_DOWN && ne.repeatCount == 0 -> { okArmed = true; false }
                        else -> !okArmed
                    }
                }) { content() }
            }
        }
    }
}

@Composable
private fun CenterCard(width: androidx.compose.ui.unit.Dp = 520.dp, content: @Composable () -> Unit) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.width(width).background(c.panel, RoundedCornerShape(22.dp)).padding(26.dp)) { content() }
    }
}

// ---------------- Item menu ----------------

@Composable
private fun ItemMenu(o: Overlay.ItemMenu) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val session = LocalSession.current ?: return
    val library by session.library.collectAsState()
    val item = library[o.meta.id]
    val inCw = item?.isInContinueWatching == true
    val inLib = item?.inLibrary == true
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val removedMsg = str(R.string.removed_from_cw, o.meta.name)
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.padding(end = 60.dp).width(340.dp).glassPanel(RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(o.meta.name, style = Vyrk.type.headlineS, color = c.text, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            val full = Modifier.fillMaxWidth()
            VyrkButton(str(if (inCw) R.string.resume else R.string.play), icon = R.drawable.ic_play, style = ButtonStyle.BRAND, modifier = full.focusRequester(first).traceFocus("menu:play"), onClick = {
                app.overlays.remove(o); Playback.start(activity, graph, session, o.meta, origin = o.origin); o.origin?.let { app.restore = it }
            })
            if (inCw) VyrkButton(str(R.string.remove_from_cw), icon = R.drawable.ic_close, modifier = full.traceFocus("menu:remove_cw"), onClick = {
                // Update the list before the menu closes, then the toast; focus goes to the neighbour.
                session.removeFromContinueWatching(o.meta.id)
                app.showToast(removedMsg)
                app.overlays.remove(o)
                val origin = o.origin
                if (origin != null) {
                    val remainingCw = session.library.value.values.count { it.isInContinueWatching }
                    if (origin.rowKey == CW_ROW && remainingCw == 0) app.focusHeroTick++
                    else app.restore = origin.copy(itemId = "")
                }
            })
            VyrkButton(str(R.string.more_info), icon = R.drawable.ic_info, modifier = full.traceFocus("menu:info"), onClick = { app.replaceTop(Overlay.Info(o.meta, o.origin)) })
            VyrkButton(str(R.string.preview), icon = R.drawable.ic_trailer, modifier = full.traceFocus("menu:preview"), onClick = { closeOverlay(app, o); app.preview = o.meta })
            VyrkButton(str(if (inLib) R.string.remove_from_library else R.string.add_to_library), icon = if (inLib) R.drawable.ic_trash else R.drawable.ic_plus, modifier = full.traceFocus("menu:library"), onClick = {
                if (inLib) session.removeFromLibrary(o.meta.id) else session.addToLibrary(o.meta)
                closeOverlay(app, o)
            })
            VyrkButton(str(if (item?.watched == true) R.string.mark_unwatched else R.string.mark_watched), icon = R.drawable.ic_check, modifier = full.traceFocus("menu:watched"), onClick = {
                session.markWatched(o.meta, item?.watched != true); closeOverlay(app, o)
            })
            val rowKey = o.origin?.rowKey
            if (rowKey != null && rowKey != CW_ROW) VyrkButton(str(R.string.hide_row), modifier = full.traceFocus("menu:hide_row"), onClick = {
                val catalogKey = rowKey.removeSuffix(RowKind.CATALOG.name).removeSuffix(RowKind.TOP10.name)
                session.updateSettings { it.copy(hiddenRows = it.hiddenRows + catalogKey) }
                app.overlays.remove(o)
            })
            VyrkButton(str(R.string.close), modifier = full.traceFocus("menu:close"), onClick = { closeOverlay(app, o) })
        }
    }
}

// ---------------- Install confirmation ----------------

@Composable
private fun InstallConfirm(o: Overlay.InstallConfirm) {
    val app = LocalApp.current
    val session = LocalSession.current ?: return
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val d = o.descriptor
    LaunchedEffect(d != null, o.error) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    val installedMsg = d?.let { str(R.string.installed_addon, it.manifest.name) }.orEmpty()
    val needsServer = d?.let { CatalogRules.needsStreamingServer(it) } == true
    CenterCard(560.dp) {
        Text(str(R.string.install_addon_title), style = Vyrk.type.headlineL, color = Vyrk.colors.text)
        Spacer(Modifier.height(6.dp))
        if (o.external) Text(str(R.string.install_from_link), style = Vyrk.type.bodyM, color = Vyrk.colors.warning)
        Spacer(Modifier.height(12.dp))
        when {
            o.error != null -> Text(str(R.string.install_fetch_failed, Redact.url(o.input)), style = Vyrk.type.bodyM, color = Vyrk.colors.danger)
            d == null -> Text(str(R.string.loading), style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary)
            else -> {
                Text("${d.manifest.name}  ·  v${d.manifest.version}", style = Vyrk.type.titleL, color = Vyrk.colors.text)
                d.manifest.description?.let { Text(it, style = Vyrk.type.bodyS, color = Vyrk.colors.textSecondary, maxLines = 4) }
                Spacer(Modifier.height(8.dp))
                WrapRow(spacing = 6.dp) {
                    d.manifest.resources.forEach { Tag(it.name) }
                    d.manifest.types.forEach { Tag(it, background = Vyrk.colors.gold.copy(alpha = 0.16f), color = Vyrk.colors.gold) }
                }
                Spacer(Modifier.height(8.dp))
                Text(Redact.url(d.transportUrl), style = Vyrk.type.labelM, color = Vyrk.colors.textMuted)
                if (needsServer) Text(str(R.string.needs_streaming_server), style = Vyrk.type.bodyS, color = Vyrk.colors.warning)
                if (com.vyrk.core.addon.AddonUrls.isUnencryptedPublic(d.transportUrl)) Text(str(R.string.addon_not_encrypted), style = Vyrk.type.bodyS, color = Vyrk.colors.warning)
            }
        }
        Spacer(Modifier.height(18.dp))
        WrapRow {
            if (d != null && !needsServer) VyrkButton(str(R.string.install), style = ButtonStyle.BRAND, modifier = Modifier.focusRequester(first).traceFocus("install:confirm"), onClick = {
                scope.launch { session.install(d); app.showToast(installedMsg) }
                app.overlays.remove(o)
            })
            VyrkButton(str(R.string.cancel), modifier = (if (d == null || needsServer) Modifier.focusRequester(first) else Modifier).traceFocus("install:cancel"), onClick = { app.overlays.remove(o) })
        }
    }
}

// ---------------- PIN ----------------

@Composable
private fun AddonQr(o: Overlay.AddonQr) {
    val app = LocalApp.current
    val url = AddonUrls.configureUrl(o.descriptor.transportUrl) ?: o.descriptor.transportUrl
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    CenterCard(620.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(220.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { QrCode(url, 204.dp) }
            Column(Modifier.weight(1f)) {
                Text(str(R.string.configure_addon, o.descriptor.manifest.name), style = Vyrk.type.headlineM, color = Vyrk.colors.text)
                Spacer(Modifier.height(8.dp))
                Text(str(R.string.configure_addon_body), style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                Text(Redact.url(url), style = Vyrk.type.labelM, color = Vyrk.colors.textMuted)
                Spacer(Modifier.height(16.dp))
                VyrkButton(str(R.string.done), style = ButtonStyle.BRAND, modifier = Modifier.focusRequester(first), onClick = { app.overlays.remove(o) })
            }
        }
    }
}

@Composable
private fun MessageBox(o: Overlay.Message) {
    val app = LocalApp.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    CenterCard {
        Text(o.title, style = Vyrk.type.headlineM, color = Vyrk.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(o.body, style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary)
        Spacer(Modifier.height(16.dp))
        VyrkButton(str(R.string.ok), style = ButtonStyle.BRAND, modifier = Modifier.focusRequester(first), onClick = { app.overlays.remove(o) })
    }
}

@Composable
private fun SeeAll(o: Overlay.SeeAll) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val session = LocalSession.current ?: return
    val library by session.library.collectAsState()
    val state by session.state.collectAsState()
    val model = remember { BrowseModel(graph.addonClient, Tab.HOME, session.scope) }
    val row = remember { RowState(o.ref.key + "all", RowKind.CATALOG, o.ref, o.title, o.animeOnly, o.browseType, o.genre) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { model.ensureLoaded(row) }
    LaunchedEffect(row.items.isNotEmpty()) { if (row.items.isNotEmpty()) runCatching { first.requestFocus() } }
    val c = Vyrk.colors
    Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 48.dp, vertical = 30.dp)) {
        Text(o.title, style = Vyrk.type.headlineL, color = c.text)
        Spacer(Modifier.height(16.dp))
        if (row.items.isEmpty() && row.loadedOnce && !row.loading) {
            Text(str(if (o.animeOnly) R.string.anime_no_matches else R.string.row_empty), color = c.textMuted)
            if (row.error != null) VyrkButton(str(R.string.retry), onClick = { model.retry(row) })
            else if (row.hasMore) VyrkButton(str(R.string.anime_load_more), onClick = { model.loadMore(row) })
        }
        LazyVerticalGrid(GridCells.Adaptive(130.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(row.items, key = { _, m -> m.type + m.id }) { i, m ->
                if (i >= row.items.size - 8) LaunchedEffect(row.items.size) { model.loadMore(row) }
                Poster(m, 118.dp, posterState(library[m.id], state.newEpisodes[m.id].orEmpty()),
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { app.push(Overlay.Info(m, null)) }, onLongClick = { app.push(Overlay.ItemMenu(m, null)) }, onPlayPause = {},
                    traceLabel = "seeall#$i")
            }
            if (row.animeOnly && row.items.isNotEmpty() && o.ref.catalog.supportsExtra("skip")) item(key = "catalog-page") {
                VyrkButton(str(if (row.hasMore) R.string.anime_load_more else R.string.close),
                    onClick = { if (row.hasMore) model.loadMore(row) else closeOverlay(app, o) },
                    modifier = Modifier.traceFocus("seeall:page"))
            }
        }
    }
}
