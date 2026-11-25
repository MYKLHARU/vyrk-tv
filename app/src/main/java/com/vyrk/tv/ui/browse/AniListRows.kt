package com.vyrk.tv.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.vyrk.tv.ui.components.SectionHeading
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
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
 * The Anime tab's AniList section: a genre bar and four rows (Trending, Popular this season,
 * All-time popular, Top rated) from AniList's public API, with no account. Titles open through
 * your add-ons by Kitsu ID like the rest of the tab; with no add-on that opens Kitsu titles, the
 * section is a card offering Anime Kitsu instead.
 */
@Stable
class AniListState {
    /** The genre (or tag) the rows are narrowed to; null for all. Kept while the app runs. */
    var filter by mutableStateOf<AniList.Filter?>(null)
    /** Bumped by Retry. */
    var attempt by mutableIntStateOf(0)
    /** The add-ons the loaded rows were filtered for: other add-ons, other rows. */
    internal var loadedFor: Int? = null
    private val rows = HashMap<String, RowState>()

    fun row(section: AniList.Section, filter: AniList.Filter?): RowState =
        rowKey(section, filter).let { key -> rows.getOrPut(key) { RowState(key, RowKind.CATALOG, null, "") } }

    fun retry() { attempt++ }

    companion object {
        private const val PREFIX = "anilist:"
        fun rowKey(section: AniList.Section, filter: AniList.Filter?) =
            PREFIX + section.key + (filter?.let { ":" + slug(it) } ?: "")
        fun isAniListRow(row: RowState) = row.ref == null && row.key.startsWith(PREFIX)
        /** A focus label for a filter: `anilist:genre:slice-of-life`. */
        fun slug(f: AniList.Filter) = f.name.lowercase().replace(' ', '-')
    }
}

/** What a page draws for AniList: the genre bar and rows, or the card offering Anime Kitsu. */
class AniListUi(val state: AniListState, val rows: List<RowState>, val showBar: Boolean, val showOffer: Boolean) {
    companion object { val NONE = AniListUi(AniListState(), emptyList(), showBar = false, showOffer = false) }
}

object AniListRows {
    /** Anime Kitsu's public instance: it opens Kitsu titles (and AniList and MyAnimeList IDs). */
    const val KITSU_ADDON_URL = "https://anime-kitsu.strem.fun/manifest.json"
    /** Titles newer than the bundled ID list looked up per load; the rest wait for the next one. */
    private const val LOOKUPS = 12

    /** Whether an add-on opens Kitsu titles: AniList's rows need one. */
    fun canOpen(addons: List<Descriptor>) = addons.any { it.manifest.supports("meta", "series", "kitsu:1") }

    /** The rows' posters, by section, or the failure (`code` 0 when AniList could not be reached). */
    suspend fun load(graph: AppGraph, addons: List<Descriptor>, filter: AniList.Filter?): Result<Map<AniList.Section, List<MetaPreview>>> =
        withContext(Dispatchers.IO) {
            val name = filter?.name ?: "all"
            when (val r = graph.aniList.rows(filter)) {
                is AniListClient.Result.Failed -> {
                    Diagnostics.trace("anilist failed filter=$name code=${r.code} ${r.reason}")
                    Diagnostics.log("anilist", "rows failed (${r.code}): ${r.reason}")
                    Result.failure(IllegalStateException(r.reason))
                }
                is AniListClient.Result.Ok -> {
                    val ids = graph.aniListIds
                    lookUp(graph, addons, AniList.unmapped(r.rows, ids, LOOKUPS), ids)
                    val opens = { type: String, id: String -> addons.any { it.manifest.supports("meta", type, id) } }
                    val out = r.rows.mapValues { (_, media) -> AniList.previews(media, ids, opens) }
                    Diagnostics.trace("anilist ok filter=$name " + AniList.Section.entries.joinToString(" ") { "${it.key}=${out[it].orEmpty().size}/${r.rows[it].orEmpty().size}" })
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
 * AniList's rows for a page ([enabled] on the Anime tab). Rows show while they load, then only
 * with titles; a failed request leaves one row with Retry.
 */
@Composable
fun rememberAniList(session: ProfileSession, model: BrowseModel, enabled: Boolean): AniListUi {
    val graph = LocalGraph.current
    val addons by session.addons.collectAsState()
    val settings by session.settings.collectAsState()
    val list = remember(addons) { session.effectiveAddons() }
    val canOpen = remember(list) { AniListRows.canOpen(list) }
    val state = model.aniList
    val filter = state.filter
    val sections = AniList.Section.entries.map { it to state.row(it, filter) }
    val titles = mapOf(
        AniList.Section.TRENDING to str(R.string.anilist_trending),
        AniList.Section.SEASON to str(R.string.anilist_season),
        AniList.Section.POPULAR to str(R.string.anilist_popular),
        AniList.Section.TOP to str(R.string.anilist_top),
    )
    sections.forEach { (s, row) -> row.title = filter?.let { "${titles.getValue(s)} · ${it.name}" } ?: titles.getValue(s) }
    val addonsKey = remember(list) { list.joinToString { it.transportUrl }.hashCode() }
    LaunchedEffect(enabled, canOpen, filter, state.attempt, addonsKey) {
        if (!enabled || !canOpen) return@LaunchedEffect
        val rows = sections.map { it.second }
        if (state.loadedFor == addonsKey && rows.all { it.loadedOnce && it.error == null }) return@LaunchedEffect
        rows.forEach { it.loading = true; it.error = null }
        try {
            val result = AniListRows.load(graph, list, filter)
            result.onSuccess { bySection -> sections.forEach { (s, row) -> row.items = bySection[s].orEmpty() } }
                .onFailure { e -> rows.forEach { it.items = emptyList() }; rows.first().error = e.message ?: "AniList" }
            state.loadedFor = addonsKey
            rows.forEach { it.loadedOnce = true }
        } finally {
            rows.forEach { it.loading = false }
        }
    }
    if (!enabled) return AniListUi.NONE
    // Hide row (title menu) hides that row for every genre.
    val hidden = settings.hiddenRows
    val rows = if (!canOpen) emptyList() else sections.mapIndexedNotNull { i, (s, row) ->
        val base = AniListState.rowKey(s, null)
        row.takeIf { (!it.loadedOnce || it.items.isNotEmpty() || i == 0 && it.error != null) && hidden.none { h -> h == base || h.startsWith("$base:") } }
    }
    return AniListUi(state, rows, showBar = canOpen, showOffer = !canOpen)
}

/** AniList's genres (and a few tags) above its rows. Focus here keeps the page at the top. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AniListGenreBar(state: AniListState, allReq: FocusRequester, onFocus: (Boolean) -> Unit) {
    var active by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().onFocusChanged { active = it.hasFocus; onFocus(it.hasFocus) }) {
        SectionHeading(str(R.string.anilist_genres), active = active, rule = true,
            modifier = Modifier.padding(start = Dimens.screenMargin, end = Dimens.screenMargin, bottom = 10.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides ComfortZoneSpec) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.screenMargin, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "all") {
                    VyrkChip(str(R.string.anime_all_genres), state.filter == null, { state.filter = null }, Modifier.focusRequester(allReq).traceFocus("anilist:genre:all"))
                }
                items(AniList.filters, key = { it.name }) { f ->
                    VyrkChip(f.name, state.filter == f, { state.filter = f }, Modifier.traceFocus("anilist:genre:${AniListState.slug(f)}"))
                }
            }
        }
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
