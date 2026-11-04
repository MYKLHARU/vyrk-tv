package com.vyrk.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.Redact
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.library.LibraryItem
import com.vyrk.tv.R
import com.vyrk.tv.data.AccountState
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.Origin
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.browse.Poster
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

private fun LibraryItem.toPreview() = MetaPreview(id = id, type = type, name = name, poster = poster)

@Composable
fun LibraryScreen(session: ProfileSession, onPlay: (MetaPreview, Origin?) -> Unit) {
    val app = LocalApp.current
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    val account by session.account.collectAsState()
    val syncing by session.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("recent") }
    var showHidden by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    val items = library.values.filter { it.inLibrary || (showHidden && it.id in sstate.hiddenContinueWatching) }
        .filter { if (showHidden) it.id in sstate.hiddenContinueWatching else true }
        .filter { when (filter) { "movie" -> it.type == "movie"; "series" -> it.type == "series"; else -> true } }
        .let { list ->
            when (sort) {
                "name" -> list.sortedBy { it.name.lowercase() }
                "unwatched" -> list.sortedWith(compareBy<LibraryItem> { it.watched }.thenByDescending { it.mtimeMs })
                else -> list.sortedByDescending { it.state.lastWatchedMs ?: it.mtimeMs }
            }
        }
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screenMargin, vertical = 12.dp)) {
        WrapRow {
            VyrkChip(str(R.string.filter_all), filter == "all", { filter = "all" }, Modifier.traceFocus("library:all"))
            VyrkChip(str(R.string.tab_movies), filter == "movie", { filter = "movie" }, Modifier.traceFocus("library:movies"))
            VyrkChip(str(R.string.shows), filter == "series", { filter = "series" }, Modifier.traceFocus("library:shows"))
            Spacer(Modifier.width(16.dp))
            VyrkChip(str(R.string.sort_recent), sort == "recent", { sort = "recent" }, Modifier.traceFocus("library:sort_recent"))
            VyrkChip(str(R.string.sort_name), sort == "name", { sort = "name" }, Modifier.traceFocus("library:sort_name"))
            VyrkChip(str(R.string.sort_unwatched), sort == "unwatched", { sort = "unwatched" }, Modifier.traceFocus("library:sort_unwatched"))
            Spacer(Modifier.width(16.dp))
            if (account is AccountState.Connected) VyrkButton(str(if (syncing) R.string.syncing else R.string.sync_now), icon = R.drawable.ic_refresh, onClick = { scope.launch { session.syncNow() } }, modifier = Modifier.traceFocus("library:sync"))
            if (sstate.hiddenContinueWatching.isNotEmpty()) VyrkButton(str(R.string.hidden_n, sstate.hiddenContinueWatching.size), onClick = { showHidden = !showHidden }, modifier = Modifier.traceFocus("library:hidden"))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (account) {
                is AccountState.Connected -> str(R.string.synced_with_account)
                is AccountState.Expired -> str(R.string.session_expired)
                else -> str(R.string.saved_on_device)
            }, style = Vyrk.type.labelM, color = if (account is AccountState.Expired) c.warning else c.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text(str(if (showHidden) R.string.no_hidden else R.string.library_empty), style = Vyrk.type.bodyL, color = c.textMuted)
        } else LazyVerticalGrid(GridCells.Adaptive(132.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                val m = it.toPreview()
                Poster(m, 118.dp, posterState(it, sstate.newEpisodes[it.id].orEmpty()),
                    onClick = {
                        if (showHidden) { session.restoreHidden(it.id); return@Poster }
                        app.push(Overlay.Info(m, Origin("library", "grid", i, it.id), focusResume = it.isInContinueWatching))
                    },
                    onLongClick = { app.push(Overlay.ItemMenu(m, Origin("library", "grid", i, it.id))) },
                    onPlayPause = {}, traceLabel = "library#$i")
            }
        }
    }
}

/**
 * Settings › Add-ons, top: Install from URL and Save to account, or the URL keyboard while typing.
 * [typing] is the URL so far, or null when not typing.
 */
@Composable
fun AddonActions(session: ProfileSession, typing: String?, onTyping: (String?) -> Unit) {
    val app = LocalApp.current
    val account by session.account.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Vyrk.colors
    val savedMsg = str(R.string.saved_to_account)
    // When the keyboard closes, its keys leave the screen: give focus back to Install from URL.
    val installReq = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(typing == null) { if (typing == null && refocus) { refocus = false; runCatching { installReq.requestFocus() } } }
    if (typing == null) {
        WrapRow(spacing = 6.dp) {
            VyrkButton(str(R.string.install_from_url), icon = R.drawable.ic_plus, style = ButtonStyle.PRIMARY, onClick = { refocus = true; onTyping("") },
                modifier = Modifier.focusRequester(installReq).traceFocus("addons:install_url"))
            if (account is AccountState.Connected) VyrkButton(str(R.string.save_to_account), onClick = { scope.launch { if (session.saveAddonsToAccount()) app.showToast(savedMsg) } }, modifier = Modifier.traceFocus("addons:save"))
        }
        return
    }
    Column(Modifier.fillMaxWidth().background(c.glass, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(str(R.string.addon_url), style = Vyrk.type.labelL, color = c.textMuted)
        Text(typing.ifEmpty { "https://…/manifest.json" }, style = Vyrk.type.titleM, color = if (typing.isEmpty()) c.textMuted else c.text, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        UrlKeyboard(onChar = { onTyping(typing + it) }, onBackspace = { onTyping(typing.dropLast(1)) }, onClear = { onTyping("") })
        Spacer(Modifier.height(10.dp))
        WrapRow {
            VyrkButton(str(R.string.next), style = ButtonStyle.PRIMARY, onClick = {
                val input = typing; onTyping(null)
                val url = com.vyrk.core.addon.AddonUrls.normaliseInstallUrl(input) ?: input
                app.push(Overlay.InstallConfirm(null, url, external = false))
                scope.launch {
                    val d = session.fetchManifest(input)
                    val top = app.overlays.lastOrNull()
                    if (top is Overlay.InstallConfirm && top.input == url) app.replaceTop(top.copy(descriptor = d.getOrNull(), error = d.exceptionOrNull()?.let { "fetch" }))
                }
            }, modifier = Modifier.traceFocus("addons:url_next"))
            VyrkButton(str(R.string.cancel), onClick = { onTyping(null) }, modifier = Modifier.traceFocus("addons:url_cancel"))
        }
    }
}

/** One installed add-on in Settings › Add-ons: details, tags and its actions. */
@Composable
fun AddonCard(session: ProfileSession, d: com.vyrk.core.addon.Descriptor, index: Int, lastIndex: Int) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val c = Vyrk.colors
    val refreshedMsg = str(R.string.addon_refreshed)
    val m = d.manifest
    val skipped = CatalogRules.needsStreamingServer(d)
    Column(Modifier.fillMaxWidth().background(c.glass, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(c.raised.copy(alpha = 0.7f), RoundedCornerShape(10.dp))) {
                m.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${m.name}  ·  v${m.version}", style = Vyrk.type.titleM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Redact.url(d.transportUrl), style = Vyrk.type.labelS, color = c.textMuted)
            }
        }
        m.description?.let { Text(it, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(6.dp))
        WrapRow(spacing = 5.dp) {
            m.resources.forEach { Tag(it.name) }
            m.types.forEach { Tag(it, background = c.accent.copy(alpha = 0.18f), color = c.accent) }
        }
        if (skipped) Text(str(R.string.needs_streaming_server), style = Vyrk.type.bodyS, color = c.warning, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        WrapRow(spacing = 6.dp) {
            if (index > 0) VyrkButton(str(R.string.move_up), icon = R.drawable.ic_up, onClick = { scope.launch { session.move(m.id, -1) } }, modifier = Modifier.traceFocus("addon:$index:up"))
            if (index < lastIndex) VyrkButton(str(R.string.move_down), icon = R.drawable.ic_down, onClick = { scope.launch { session.move(m.id, 1) } }, modifier = Modifier.traceFocus("addon:$index:down"))
            if (m.behaviorHints.configurable) VyrkButton(str(R.string.configure), icon = R.drawable.ic_qr, onClick = { app.push(Overlay.AddonQr(d)) }, modifier = Modifier.traceFocus("addon:$index:configure"))
            VyrkButton(str(R.string.refresh), icon = R.drawable.ic_refresh, onClick = { scope.launch { if (session.refreshAddon(m.id)) app.showToast(refreshedMsg) } }, modifier = Modifier.traceFocus("addon:$index:refresh"))
            if (!d.flags.protected) VyrkButton(str(R.string.remove), icon = R.drawable.ic_trash, onClick = { scope.launch { session.remove(m.id) } }, modifier = Modifier.traceFocus("addon:$index:remove"))
        }
    }
}

/** Keyboard for URLs: letters, digits and URL punctuation. */
@Composable
fun UrlKeyboard(onChar: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val keys = "abcdefghijklmnopqrstuvwxyz0123456789:/.-_?=&%".toList()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        keys.chunked(8).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEachIndexed { i, ch ->
                    VyrkButton(ch.toString(), onClick = { onChar(ch) }, horizontalPadding = 0.dp, modifier = Modifier.width(36.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier).traceFocus("urlkey:$ch"))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VyrkButton("https://", onClick = { "https://".forEach(onChar) })
            VyrkButton("/manifest.json", onClick = { "/manifest.json".forEach(onChar) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VyrkButton(str(R.string.key_backspace), onClick = onBackspace)
            VyrkButton(str(R.string.key_clear), onClick = onClear)
        }
    }
}
