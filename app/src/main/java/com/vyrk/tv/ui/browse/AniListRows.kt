package com.vyrk.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.anime.AniList
import com.vyrk.core.anime.AniListClient
import com.vyrk.core.anime.AniListKitsu
import com.vyrk.tv.R
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.AppState
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.glassPanel
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Anime tab's AniList rows, from AniList's public API with no account: four lists (Trending,
 * Popular this season, All-time popular, Top rated), then a row for each genre (and a few popular
 * tags) with what's popular in it now. Titles open through your add-ons by Kitsu ID like the rest
 * of the tab; with no add-on that opens Kitsu titles, the tab shows a card offering Anime Kitsu.
 */
@Stable
class AniListState {
    /** Every AniList row, in page order: the four lists, then the genres. */
    val shelves: List<Pair<AniList.Shelf, RowState>> =
        (AniList.main + AniList.filters.map { AniList.Shelf.Genre(it) }).map { it to RowState(rowKey(it), RowKind.CATALOG, null, "") }
    private val byKey = shelves.associate { it.second.key to it.first }
    /** Bumped by Retry on the four lists. */
    var attempt by mutableIntStateOf(0)
    /** Genre rows that came on screen (or were retried), waiting for their request. */
    internal var wanted by mutableStateOf(emptySet<String>())
    /** The add-ons the loaded rows were filtered for: other add-ons, other titles. */
    internal var loadedFor: Int? = null

    /**
     * A row came on screen: the four lists load with the page, a genre row with the next request
     * (with the genre rows after it). After a failure, this is Retry.
     */
    fun want(row: RowState) {
        when (byKey[row.key] ?: return) {
            is AniList.Shelf.Main -> if (row.error != null) attempt++
            is AniList.Shelf.Genre -> if (!row.loading && (!row.loadedOnce || row.error != null)) wanted = wanted + row.key
        }
    }

    companion object {
        private const val PREFIX = "anilist:"
        fun rowKey(s: AniList.Shelf) = PREFIX + when (s) {
            is AniList.Shelf.Main -> s.section.key
            is AniList.Shelf.Genre -> "genre:" + s.filter.slug
        }
        fun isAniListRow(row: RowState) = row.ref == null && row.key.startsWith(PREFIX)
    }
}

/** What a page draws for AniList: its rows, or the card offering Anime Kitsu. */
class AniListUi(val rows: List<RowState>, val showOffer: Boolean) {
    companion object { val NONE = AniListUi(emptyList(), showOffer = false) }
}

object AniListRows {
    /** Anime Kitsu's public instance: it opens Kitsu titles (and AniList and MyAnimeList IDs). */
    const val KITSU_ADDON_URL = "https://anime-kitsu.strem.fun/manifest.json"
    /** Titles newer than the bundled ID list looked up per load; the rest wait for the next one. */
    private const val LOOKUPS = 12
    /** Genre rows asked for in one request: the one on screen and the next ones. */
    const val GENRE_BATCH = 4

    /** Whether an add-on opens Kitsu titles: AniList's rows need one. */
    fun canOpen(addons: List<Descriptor>) = addons.any { it.manifest.supports("meta", "series", "kitsu:1") }

    /** The posters of [shelves], or the failure (`code` 0 when AniList could not be reached). */
    suspend fun load(graph: AppGraph, addons: List<Descriptor>, shelves: List<AniList.Shelf>): Result<Map<AniList.Shelf, List<MetaPreview>>> =
        withContext(Dispatchers.IO) {
            val what = if (shelves.all { it is AniList.Shelf.Main }) "main" else "genres"
            when (val r = graph.aniList.rows(shelves)) {
                is AniListClient.Result.Failed -> {
                    Diagnostics.trace("anilist failed $what code=${r.code} ${r.reason}")
                    Diagnostics.log("anilist", "$what failed (${r.code}): ${r.reason}")
                    Result.failure(IllegalStateException(r.reason))
                }
                is AniListClient.Result.Ok -> {
                    val ids = graph.aniListIds
                    lookUp(graph, addons, AniList.unmapped(r.rows, ids, LOOKUPS), ids)
                    val opens = { type: String, id: String -> addons.any { it.manifest.supports("meta", type, id) } }
                    val out = r.rows.mapValues { (_, media) -> AniList.previews(media, ids, opens) }
                    Diagnostics.trace("anilist ok $what " + shelves.joinToString(" ") { s ->
                        val name = when (s) { is AniList.Shelf.Main -> s.section.key; is AniList.Shelf.Genre -> s.filter.slug }
                        "$name=${out[s].orEmpty().size}/${r.rows[s].orEmpty().size}"
                    })
                    Result.success(out)
                }
            }
        }

    /**
     * Titles newer than the bundled list: an add-on that reads AniList or MyAnimeList IDs (Anime
     * Kitsu does) answers with the title's `kitsu:` entry. Two at a time; misses are remembered.
     */
    private suspend fun lookUp(graph: AppGraph, addons: List<Descriptor>, missing: List<AniList.Media>, ids: AniListKitsu) {
        if (missing.isEmpty()) return
        val limit = Semaphore(2)
        coroutineScope {
            missing.map { m ->
                async {
                    limit.withPermit {
                        val type = AniList.type(m)
                        val asks = AniList.lookupIds(m).filter { id -> addons.any { it.manifest.supports("meta", type, id) } }
                        if (asks.isEmpty()) return@withPermit
                        var found: Int? = null
                        for (id in asks) {
                            found = AniListKitsu.fromId(withTimeoutOrNull(10_000) { graph.addonClient.firstMeta(addons, type, id) }?.id)
                            if (found != null) break
                        }
                        ids.learn(m.id, found)
                    }
                }
            }.awaitAll()
        }
    }

    /** Asks to add Anime Kitsu: the usual install confirmation, filled in once its manifest arrives. */
    fun offerKitsu(app: AppState, session: ProfileSession, scope: CoroutineScope) {
        val url = KITSU_ADDON_URL
        app.push(Overlay.InstallConfirm(null, url, external = false))
        scope.launch {
            val d = session.fetchManifest(url)
            val top = app.overlays.lastOrNull()
            if (top is Overlay.InstallConfirm && top.input == url) app.replaceTop(top.copy(descriptor = d.getOrNull(), error = d.exceptionOrNull()?.let { "fetch" }))
        }
    }
}

/**
 * AniList's rows for a page ([enabled] on the Anime tab). The four lists load with the page and the
 * first genre rows right after; the other genre rows as they come on screen, [AniListRows.GENRE_BATCH]
 * to a request. Rows show while they load, then only with titles; a failed request leaves Retry.
 */
@Composable
fun rememberAniList(session: ProfileSession, model: BrowseModel, enabled: Boolean): AniListUi {
    val graph = LocalGraph.current
    val addons by session.addons.collectAsState()
    val settings by session.settings.collectAsState()
    val list = remember(addons) { session.effectiveAddons() }
    val canOpen = remember(list) { AniListRows.canOpen(list) }
    val state = model.aniList
    val titles = mapOf(
        AniList.Section.TRENDING to str(R.string.anilist_trending),
        AniList.Section.SEASON to str(R.string.anilist_season),
        AniList.Section.POPULAR to str(R.string.anilist_popular),
        AniList.Section.TOP to str(R.string.anilist_top),
    )
    state.shelves.forEach { (s, row) ->
        row.title = when (s) { is AniList.Shelf.Main -> titles.getValue(s.section); is AniList.Shelf.Genre -> s.filter.name }
    }
    val main = state.shelves.filter { it.first is AniList.Shelf.Main }
    val genres = state.shelves.filter { it.first is AniList.Shelf.Genre }
    val addonsKey = remember(list) { list.joinToString { it.transportUrl }.hashCode() }
    // The four lists, with the page; then the first genre rows, so they're ready when you get there.
    LaunchedEffect(enabled, canOpen, state.attempt, addonsKey) {
        if (!enabled || !canOpen) return@LaunchedEffect
        if (state.loadedFor != addonsKey) {
            // Other add-ons open other titles: genre rows already shown are filled again (AniList's answer is kept).
            state.wanted = state.wanted + genres.filter { it.second.loadedOnce }.map { it.second.key }
        } else if (main.all { it.second.loadedOnce && it.second.error == null }) return@LaunchedEffect
        if (fill(graph, list, main, state)) {
            state.loadedFor = addonsKey
            state.wanted = state.wanted + genres.take(AniListRows.GENRE_BATCH).filter { !it.second.loadedOnce }.map { it.second.key }
        }
    }
    // Genre rows as they come on screen, with the ones after them.
    LaunchedEffect(enabled, canOpen, addonsKey) {
        if (!enabled || !canOpen) return@LaunchedEffect
        snapshotFlow { state.wanted }.collect { wanted ->
            if (wanted.isEmpty()) return@collect
            val first = genres.indexOfFirst { it.second.key in wanted }
            val batch = (genres.filter { it.second.key in wanted } +
                genres.drop(first.coerceAtLeast(0)).filter { !it.second.loadedOnce && !it.second.loading }).distinct().take(AniListRows.GENRE_BATCH)
            state.wanted = wanted - batch.map { it.second.key }.toSet()
            fill(graph, list, batch, state)
        }
    }
    if (!enabled) return AniListUi.NONE
    // Hide row (title menu) hides a row for good; v0.1.67's genre-narrowed keys hide their list.
    val hidden = settings.hiddenRows
    val rows = if (!canOpen) emptyList() else state.shelves.mapIndexedNotNull { i, (s, row) ->
        val shown = !row.loadedOnce || row.items.isNotEmpty() || row.error != null && (s is AniList.Shelf.Genre || i == 0)
        row.takeIf { shown && hidden.none { h -> h == row.key || h.startsWith(row.key + ":") } }
    }
    return AniListUi(rows, showOffer = !canOpen)
}

/**
 * Fills [rows] from one request. A failure shows on the first of the four lists (they fail
 * together) or on each genre row; rows cut off midway are asked for again. True when it loaded.
 */
private suspend fun fill(graph: AppGraph, addons: List<Descriptor>, rows: List<Pair<AniList.Shelf, RowState>>, state: AniListState): Boolean {
    if (rows.isEmpty()) return true
    rows.forEach { (_, r) -> r.loading = true; r.error = null }
    var done = false
    try {
        val result = AniListRows.load(graph, addons, rows.map { it.first })
        result.onSuccess { bySelf -> rows.forEach { (s, r) -> r.items = bySelf[s].orEmpty() } }
            .onFailure { e ->
                val reason = e.message ?: "AniList"
                rows.forEachIndexed { i, (s, r) -> r.items = emptyList(); r.error = if (s is AniList.Shelf.Genre || i == 0) reason else null }
            }
        rows.forEach { (_, r) -> r.loadedOnce = true }
        done = true
        return result.isSuccess
    } finally {
        rows.forEach { (_, r) -> r.loading = false }
        if (!done) state.wanted = state.wanted + rows.filter { it.first is AniList.Shelf.Genre }.map { it.second.key }
    }
}

/** With no add-on that opens Kitsu titles: what AniList's rows need, and a button to add Anime Kitsu. */
@Composable
fun AniListOffer(onAdd: () -> Unit, onFocus: (Boolean) -> Unit) {
    val c = Vyrk.colors
    Row(
        Modifier.padding(horizontal = Dimens.screenMargin).fillMaxWidth()
            .glassPanel()
            .padding(horizontal = 26.dp, vertical = 18.dp)
            .onFocusChanged { onFocus(it.hasFocus) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Icon(R.drawable.ic_trending, null, Modifier.size(40.dp), tint = c.gold)
        Column(Modifier.weight(1f)) {
            Text(str(R.string.anilist_offer_title), style = Vyrk.type.headlineM, color = c.text)
            Box(Modifier.height(4.dp))
            Text(str(R.string.anilist_offer_body), style = Vyrk.type.bodyM, color = c.textSecondary)
            Box(Modifier.height(12.dp))
            VyrkButton(str(R.string.anilist_add_kitsu), onClick = onAdd, style = ButtonStyle.PRIMARY, shape = Dimens.pillShape,
                icon = R.drawable.ic_plus, modifier = Modifier.traceFocus("anilist:add_kitsu"))
        }
    }
}
