package com.vyrk.tv.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.search.SearchRanking
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.browse.Poster
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.VyrkIconButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Alphabetical keyboard: seven columns a–z, then 123, space, backspace and clear. */
@Composable
fun AlphaKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    digitsOnly: Boolean = false,
    requestInitialFocus: Boolean = false,
    firstKey: FocusRequester = remember { FocusRequester() },
) {
    var digits by remember { mutableStateOf(digitsOnly) }
    LaunchedEffect(digitsOnly) { digits = digitsOnly }
    LaunchedEffect(requestInitialFocus) { if (requestInitialFocus) runCatching { firstKey.requestFocus() } }
    val keys = if (digits) "1234567890".toList() else "abcdefghijklmnopqrstuvwxyz".toList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(7).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, ch ->
                    VyrkButton(ch.toString(), onClick = { onChar(ch) }, horizontalPadding = 0.dp, modifier = Modifier.width(40.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(firstKey) else Modifier).traceFocus("key:$ch"))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!digitsOnly) VyrkButton(if (digits) "abc" else "123", onClick = { digits = !digits }, modifier = Modifier.width(64.dp).traceFocus("key:mode"))
            if (!digitsOnly) VyrkButton(str(R.string.key_space), onClick = { onChar(' ') }, modifier = Modifier.width(92.dp).traceFocus("key:space"))
            VyrkIconButton(R.drawable.ic_backspace, str(R.string.key_backspace), onBackspace, modifier = Modifier.traceFocus("key:backspace"))
            VyrkButton(str(R.string.key_clear), onClick = onClear, modifier = Modifier.traceFocus("key:clear"))
        }
    }
}

@Composable
fun SearchScreen(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    var query by remember { mutableStateOf(app.searchQuery) }
    var results by remember { mutableStateOf<Map<String, List<MetaPreview>>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    val c = Vyrk.colors

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    val hasVoice = remember { graph.context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }

    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() }; app.searchQuery = "" }
    // Debounced, cancellable, bounded (AddonClient limits concurrency to 8).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyMap(); searching = false; return@LaunchedEffect }
        delay(350)
        searching = true
        val refs = CatalogRules.searchCatalogs(session.effectiveAddons())
        val sources = coroutineScope {
            refs.map { ref ->
                async {
                    when (val r = graph.addonClient.catalog(ref.addon, ref.catalog.type, ref.catalog.id, listOf("search" to q))) {
                        is AddonResult.Ok -> SearchRanking.Source(ref.addonOrder, r.value.metas)
                        else -> null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        results = SearchRanking.merge(q, sources)
        searching = false
    }
    // Remember the query when leaving Search or opening a result, never partial words.
    fun remember() {
        val q = query.trim()
        if (q.length >= 2 && results.isNotEmpty()) session.updateState { s -> s.copy(recentSearches = (listOf(q) + s.recentSearches.filterNot { it.equals(q, true) }).take(10)) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { remember() } }

    Row(
        Modifier.fillMaxSize().padding(start = Dimens.screenMargin, top = 12.dp)
            .onPreviewKeyEvent { e ->
                // A hardware keyboard types straight into the query.
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val cp = e.utf16CodePoint
                when {
                    e.key == Key.Backspace -> { query = query.dropLast(1); true }
                    e.key == Key.Spacebar -> { query += ' '; true }
                    cp in 33..126 -> { query += cp.toChar(); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.width(330.dp)) {
            Box(Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(query.ifEmpty { str(R.string.search_hint) }, style = Vyrk.type.titleL, color = if (query.isEmpty()) c.textMuted else c.text, maxLines = 1)
            }
            Spacer(Modifier.height(12.dp))
            AlphaKeyboard(onChar = { query += it }, onBackspace = { query = query.dropLast(1) }, onClear = { query = "" }, firstKey = firstKey)
            if (hasVoice) {
                Spacer(Modifier.height(8.dp))
                VyrkButton(str(R.string.voice_search), icon = R.drawable.ic_mic, onClick = {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                }, modifier = Modifier.traceFocus("search:voice"))
            }
            if (sstate.recentSearches.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.recent_searches), style = Vyrk.type.labelL, color = c.textMuted, modifier = Modifier.weight(1f))
                    VyrkButton(str(R.string.clear), onClick = { session.updateState { it.copy(recentSearches = emptyList()) } }, modifier = Modifier.traceFocus("search:clear_recent"))
                }
                Spacer(Modifier.height(6.dp))
                WrapRow(spacing = 6.dp) { sstate.recentSearches.forEach { r -> VyrkChip(r, false, { query = r }, Modifier.traceFocus("search:recent")) } }
            }
        }
        Spacer(Modifier.width(24.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(Dimens.rowGap)) {
            if (query.trim().length >= 2 && !searching && results.isEmpty()) item {
                Text(str(R.string.no_results, query.trim()), style = Vyrk.type.bodyL, color = c.textMuted)
            }
            if (searching && results.isEmpty()) item { Text(str(R.string.searching), style = Vyrk.type.bodyL, color = c.textMuted) }
            val order = listOf("movie", "series") + results.keys.filter { it != "movie" && it != "series" }
            order.forEach { type ->
                val list = results[type] ?: return@forEach
                item(key = type) {
                    Column {
                        Text(CatalogRules.typeLabel(type), style = Vyrk.type.titleL, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 40.dp, top = 6.dp, bottom = 6.dp)) {
                            itemsIndexed(list, key = { _, m -> m.type + m.id }) { i, m ->
                                Poster(m, 112.dp, posterState(library[m.id], sstate.newEpisodes[m.id].orEmpty()),
                                    onClick = { remember(); app.push(Overlay.Info(m, null, focusResume = library[m.id]?.isInContinueWatching == true)) },
                                    onLongClick = { app.push(Overlay.ItemMenu(m, null)) }, onPlayPause = {},
                                    traceLabel = "search:$type#$i")
                            }
                        }
                    }
                }
            }
        }
    }
}
