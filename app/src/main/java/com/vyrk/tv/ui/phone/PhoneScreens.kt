package com.vyrk.tv.ui.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.account.ApiError
import com.vyrk.core.account.LinkCode
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.search.SearchRanking
import com.vyrk.tv.R
import com.vyrk.tv.data.AccountState
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.Profile
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.browse.posterState
import com.vyrk.tv.ui.components.AmbientBackground
import com.vyrk.tv.ui.components.EmptyState
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.Wordmark
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.vyrk.tv.ui.tryRequestFocus

// ---------------- Search ----------------

/** Search: a text field with the phone's keyboard (and voice), recent searches, results by type. */
@Composable
fun PhoneSearch(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    var query by remember { mutableStateOf(app.searchQuery) }
    var results by remember { mutableStateOf<Map<String, List<MetaPreview>>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val c = Vyrk.colors
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    val hasVoice = remember { graph.context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }
    // Opened with a name (from a person or the cast): search it straight away; otherwise the keyboard comes up.
    LaunchedEffect(Unit) {
        val preset = app.searchQuery
        app.searchQuery = ""
        if (preset.isBlank() && !app.startVoice) { delay(150); focus.tryRequestFocus(); keyboard?.show() }
    }
    // A query handed over while Search is open (Assistant) replaces the current one.
    LaunchedEffect(app.searchQuery) { if (app.searchQuery.isNotBlank()) { query = app.searchQuery; app.searchQuery = "" } }
    LaunchedEffect(app.startVoice) {
        if (!app.startVoice) return@LaunchedEffect
        app.startVoice = false
        if (hasVoice) runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
    }
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
    fun rememberQuery() {
        val q = query.trim()
        if (q.length >= 2 && results.isNotEmpty()) session.updateState { s -> s.copy(recentSearches = (listOf(q) + s.recentSearches.filterNot { it.equals(q, true) }).take(10)) }
    }
    DisposableEffect(Unit) { onDispose { rememberQuery() } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.padding(horizontal = PhoneMargin, vertical = 10.dp).fillMaxWidth().height(52.dp)
                .background(c.raised, RoundedCornerShape(26.dp)).border(1.dp, c.gold.copy(alpha = 0.35f), RoundedCornerShape(26.dp))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(R.drawable.ic_search, null, Modifier.size(20.dp), tint = c.gold)
            Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(str(R.string.search_hint), style = Vyrk.type.titleM, color = c.textMuted, maxLines = 1)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = Vyrk.type.titleM.copy(color = c.text), cursorBrush = SolidColor(c.gold),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); rememberQuery() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search:field"),
                )
            }
            if (query.isNotEmpty()) PhoneIconButton(R.drawable.ic_close, str(R.string.clear), { query = "" }, filled = false, size = 44.dp, tag = "search:clear")
            else if (hasVoice) PhoneIconButton(R.drawable.ic_mic, str(R.string.voice_search), {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            }, filled = false, size = 44.dp, tag = "search:voice")
        }
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = BottomBarHeight + nav + 24.dp)) {
            val q = query.trim()
            if (q.length < 2 && sstate.recentSearches.isNotEmpty()) {
                item(key = "recent") {
                    Column(Modifier.padding(horizontal = PhoneMargin, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(str(R.string.recent_searches), style = Vyrk.type.titleM, color = c.textSecondary, modifier = Modifier.weight(1f))
                            PhoneButton(str(R.string.clear), { session.updateState { it.copy(recentSearches = emptyList()) } }, style = PhoneButtonStyle.GHOST, height = 40.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        ChipFlow { sstate.recentSearches.forEach { r -> PhoneChip(r, false, { query = r; keyboard?.hide() }, tag = "search:recent") } }
                    }
                }
            }
            if (q.length >= 2 && !searching && results.isEmpty()) item(key = "none") {
                Text(str(R.string.no_results, q), style = Vyrk.type.bodyL, color = c.textMuted, modifier = Modifier.padding(PhoneMargin))
            }
            if (searching && results.isEmpty()) item(key = "searching") {
                Text(str(R.string.searching), style = Vyrk.type.bodyL, color = c.textMuted, modifier = Modifier.padding(PhoneMargin))
            }
            val order = listOf("movie", "series") + results.keys.filter { it != "movie" && it != "series" }
            order.forEach { type ->
                val list = results[type] ?: return@forEach
                item(key = "type:$type") {
                    Column(Modifier.padding(top = 14.dp)) {
                        PhoneSectionHeader(CatalogRules.typeLabel(type))
                        Spacer(Modifier.height(4.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = PhoneMargin), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(list, key = { _, m -> m.type + m.id }) { i, m ->
                                PhonePoster(m, 112.dp, posterState(library[m.id], sstate.newEpisodes[m.id].orEmpty()),
                                    onClick = { keyboard?.hide(); rememberQuery(); app.push(Overlay.Info(m, null)) },
                                    onLongClick = { app.push(Overlay.ItemMenu(m, null)) }, tag = "search:$type#$i")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Library ----------------

private fun LibraryItem.toPreview() = MetaPreview(id = id, type = type, name = name, poster = poster)

@Composable
fun PhoneLibrary(session: ProfileSession) {
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
    val byId = items.associateBy { it.id }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    PosterGrid(items.map { it.toPreview() }, { posterState(byId[it.id], sstate.newEpisodes[it.id].orEmpty()) },
        onClick = { m -> if (showHidden) session.restoreHidden(m.id) else app.push(Overlay.Info(m, null)) },
        onLongClick = { m -> app.push(Overlay.ItemMenu(m, null)) },
        top = statusTop + 8.dp, tagPrefix = "library",
        header = {
            Column(Modifier.padding(bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.tab_library), style = Vyrk.type.displayS, color = c.text, modifier = Modifier.weight(1f))
                    if (account is AccountState.Connected) PhoneIconButton(R.drawable.ic_refresh, str(if (syncing) R.string.syncing else R.string.sync_now),
                        { scope.launch { session.syncNow() } }, active = syncing, tag = "library:sync")
                }
                Text(
                    when (account) {
                        is AccountState.Connected -> str(R.string.synced_with_account)
                        is AccountState.Expired -> str(R.string.session_expired)
                        else -> str(R.string.saved_on_device)
                    }, style = Vyrk.type.labelM, color = if (account is AccountState.Expired) c.warning else c.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PhoneChip(str(R.string.filter_all), filter == "all", { filter = "all" }, tag = "library:all") }
                    item { PhoneChip(str(R.string.tab_movies), filter == "movie", { filter = "movie" }, tag = "library:movies") }
                    item { PhoneChip(str(R.string.shows), filter == "series", { filter = "series" }, tag = "library:shows") }
                    if (sstate.hiddenContinueWatching.isNotEmpty()) item {
                        PhoneChip(str(R.string.hidden_n, sstate.hiddenContinueWatching.size), showHidden, { showHidden = !showHidden }, tag = "library:hidden")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PhoneChip(str(R.string.sort_recent), sort == "recent", { sort = "recent" }) }
                    item { PhoneChip(str(R.string.sort_name), sort == "name", { sort = "name" }) }
                    item { PhoneChip(str(R.string.sort_unwatched), sort == "unwatched", { sort = "unwatched" }) }
                }
                if (items.isEmpty()) EmptyState(R.drawable.ic_library, str(if (showHidden) R.string.no_hidden else R.string.library_empty), Modifier.padding(top = 40.dp))
            }
        },
    )
}

// ---------------- Sign-in ----------------

/**
 * Stremio sign-in on a phone: the same link code as on a TV, but the link opens right here in the
 * browser (no QR code to scan). VYRK keeps checking and finishes by itself once you approve.
 */
@Composable
fun PhonePairing(profileId: String?, firstRun: Boolean = false) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<LinkCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val errNetwork = str(R.string.pairing_error)
    BackHandler(enabled = !firstRun) { app.screen = Screen.Main }
    LaunchedEffect(attempt) {
        error = null; link = null
        val code = runCatching { graph.accountApi.createLink() }.getOrElse { error = errNetwork; return@LaunchedEffect }
        link = code
        while (isActive) {
            delay(3_000)
            val key = try { graph.accountApi.readLink(code.code) } catch (e: ApiError) { error = errNetwork; return@LaunchedEffect } catch (e: Exception) { null }
            if (key != null) {
                val profile: Profile = graph.profiles.value.let { ps -> profileId?.let { id -> ps.firstOrNull { it.id == id } } ?: ps.firstOrNull() }
                    ?: graph.createProfile(graph.context.getString(R.string.default_profile_name), 0)
                    ?: graph.profiles.value.first()
                val s = graph.openProfile(profile)
                try {
                    s.connectAccount(key) { email -> graph.updateProfile(profile.id) { it.copy(accountEmail = email, name = if (profileId == null) email.substringBefore('@').take(16) else it.name) } }
                } catch (e: Exception) {
                    Diagnostics.log("account", "connect failed: ${e.message}")
                    error = errNetwork; return@LaunchedEffect
                }
                graph.updateDevice { it.copy(firstRunDone = true) }
                app.screen = Screen.Main
                return@LaunchedEffect
            }
        }
    }
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.heightIn(min = 24.dp).height(56.dp))
            Wordmark(30.dp)
            Spacer(Modifier.height(8.dp))
            Text(str(R.string.tagline), style = Vyrk.type.titleM, color = c.gold)
            Spacer(Modifier.height(48.dp))
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(str(R.string.pairing_title), style = Vyrk.type.displayS, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(str(R.string.phone_sign_in_body), style = Vyrk.type.bodyL, color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                val l = link
                PhoneButton(str(R.string.phone_open_sign_in), {
                    if (l != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.link))) }
                }, icon = R.drawable.ic_globe, style = PhoneButtonStyle.PRIMARY, enabled = l != null, modifier = Modifier.fillMaxWidth(), height = 54.dp, tag = "pairing:open")
                Spacer(Modifier.height(16.dp))
                // The code too, for signing in on another device.
                if (l != null) {
                    Text(str(R.string.pairing_step1) + " " + l.link.removePrefix("https://") + " " + str(R.string.pairing_step2), style = Vyrk.type.bodyS, color = c.textMuted, textAlign = TextAlign.Center)
                    Text(l.code, style = Vyrk.type.displayS, color = c.text, modifier = Modifier.padding(top = 4.dp).testTag("pairing:code"))
                }
                Spacer(Modifier.height(8.dp))
                Text(if (error != null) error!! else str(R.string.pairing_waiting), style = Vyrk.type.bodyM, color = if (error != null) c.danger else c.textMuted, textAlign = TextAlign.Center)
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    PhoneButton(str(R.string.try_again), { attempt++ }, tag = "pairing:retry")
                }
                Spacer(Modifier.height(28.dp))
                if (firstRun) PhoneButton(str(R.string.use_without_account), {
                    busy = true
                    scope.launch {
                        val p = graph.profiles.value.firstOrNull() ?: graph.createProfile(graph.context.getString(R.string.default_profile_name), 0)!!
                        graph.updateDevice { it.copy(firstRunDone = true) }
                        graph.openProfile(p)
                        app.screen = Screen.Main
                    }
                }, style = PhoneButtonStyle.OUTLINE, enabled = !busy, modifier = Modifier.fillMaxWidth(), tag = "welcome:guest")
                else PhoneButton(str(R.string.cancel), { app.screen = Screen.Main }, modifier = Modifier.fillMaxWidth(), tag = "pairing:cancel")
                if (firstRun) {
                    Spacer(Modifier.height(18.dp))
                    Text(str(R.string.not_affiliated), style = Vyrk.type.labelM, color = c.textMuted, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

