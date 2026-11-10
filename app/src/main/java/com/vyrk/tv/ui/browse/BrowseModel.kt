package com.vyrk.tv.ui.browse

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vyrk.core.addon.AddonClient
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.AnimeBrowse
import com.vyrk.core.catalog.CatalogRef
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class RowKind { CONTINUE, TOP10, CATALOG }

@Stable
class RowState(val key: String, val kind: RowKind, val ref: CatalogRef?, initialTitle: String,
    val animeOnly: Boolean = false, val browseType: String? = null, val genre: String? = null,
) {
    internal var fetchedCount = 0
    internal var lastPageIds: List<String> = emptyList()
    var title by mutableStateOf(initialTitle)
    var items by mutableStateOf<List<MetaPreview>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(true)
    var loadedOnce by mutableStateOf(false)
    internal var job: Job? = null
}

/**
 * One [RowState] per catalogue for the whole session, shared by every tab. "Popular - Movies"
 * loaded on Home is the same row on Movies, so switching tabs shows it at once instead of
 * fetching it again.
 */
class RowStore {
    private val rows = HashMap<String, RowState>()
    fun row(ref: CatalogRef, kind: RowKind, animeOnly: Boolean = false, browseType: String? = null, genre: String? = null): RowState {
        val key = ref.key + kind + if (animeOnly) ":anime:$browseType:$genre" else ""
        rows[key]?.takeIf { it.ref?.addon?.transportUrl == ref.addon.transportUrl }?.let { return it }
        val title = if (kind == RowKind.TOP10) CatalogRules.top10Title(ref) ?: CatalogRules.rowTitle(ref) else CatalogRules.rowTitle(ref)
        return RowState(key, kind, ref, title, animeOnly, browseType, genre).also { rows[key] = it }
    }
}

/** Catalogue rows for one tab, loaded lazily and paged with the `skip` extra. */
class BrowseModel(
    private val client: AddonClient,
    val tab: Tab,
    private val scope: CoroutineScope,
    private val store: RowStore = RowStore(),
    val animeOnly: Boolean = false,
    val genre: String? = null,
) {
    // Page state kept with the model, so leaving a tab and coming back finds it as it was:
    // scroll position, each row's scroll, the selected title under the header.
    val listState = androidx.compose.foundation.lazy.LazyListState()
    private val rowListStates = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun rowListState(key: String) = rowListStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
    var focusedPoster by mutableStateOf<MetaPreview?>(null)
    var focusedRowKey by mutableStateOf<String?>(null)
    /** Image URLs already requested ahead of time. */
    internal val prefetchedImages = HashSet<String>()
    var rows by mutableStateOf<List<RowState>>(emptyList())
        private set
    var hasTop10Catalogs by mutableStateOf(false)
        private set
    private var signature = ""
    /** True once rows have been built from the add-on list at least once. */
    var built by mutableStateOf(false)
        private set

    /**
     * Featured titles, locked once chosen so the hero stays put across overlays, playback and tab
     * switches. Replaced only when none of the locked titles are offered any more.
     */
    var heroItems by mutableStateOf<List<com.vyrk.core.addon.MetaPreview>>(emptyList())
        private set
    var heroIndex by androidx.compose.runtime.mutableIntStateOf(0)
    fun lockHero(candidates: List<com.vyrk.core.addon.MetaPreview>) {
        if (candidates.isEmpty()) return
        val ids = candidates.mapTo(HashSet()) { it.id }
        if (heroItems.isEmpty() || heroItems.none { it.id in ids }) { heroItems = candidates; heroIndex = 0 }
    }

    private var builtFrom: Pair<List<Descriptor>, Set<String>>? = null

    fun rebuild(addons: List<Descriptor>, hiddenRows: Set<String>) {
        // The page asks again when it first shows; same add-ons and hidden rows mean same rows.
        val input = addons to hiddenRows
        if (built && input == builtFrom) return
        builtFrom = input
        val (allTop, allRest) = CatalogRules.homeRows(addons)
        val requiredGenre = if (animeOnly && genre != null) addons.flatMapIndexed { i, d ->
            d.manifest.catalogs.filter { it.requiredExtras.isNotEmpty() && it.requiredExtras.all { e -> e == "genre" } }
                .map { CatalogRef(d, it, i) }.filter { AnimeBrowse.providerGenre(it, genre) != null }
        } else emptyList()
        val top = allTop.filter { !animeOnly || CatalogRules.isAnimeCatalog(it) }
        val rest = (allRest + requiredGenre).filter { !animeOnly || CatalogRules.isAnimeCatalog(it) }
        hasTop10Catalogs = top.isNotEmpty()
        val catalogs: List<Pair<CatalogRef, RowKind>> = when (tab) {
            Tab.HOME -> top.map { it to RowKind.TOP10 } + rest.filter { it.catalog.type != "tv" && it.catalog.type != "channel" }.map { it to RowKind.CATALOG }
            Tab.MOVIES -> (top + rest).filter { it.catalog.type == "movie" || animeOnly && it.catalog.type == "anime" }.map { it to RowKind.CATALOG }
            Tab.SERIES -> (top + rest).filter { it.catalog.type == "series" || animeOnly && it.catalog.type == "anime" }.map { it to RowKind.CATALOG }
            Tab.ANIME -> (top + rest).filter { CatalogRules.isAnimeCatalog(it) }.map { it to RowKind.CATALOG }
            Tab.LIVE -> rest.filter { it.catalog.type == "tv" || it.catalog.type == "channel" }.map { it to RowKind.CATALOG }
            else -> emptyList()
        }
        val visible = catalogs.filter { it.first.key !in hiddenRows }
        val sig = visible.joinToString("|") { it.first.key + it.second } + "#" + addons.joinToString { it.transportUrl.hashCode().toString() }
        if (sig == signature) { built = true; return }
        signature = sig
        rows = visible.map { (ref, kind) -> store.row(ref, kind, animeOnly, when (tab) { Tab.MOVIES -> "movie"; Tab.SERIES -> "series"; else -> null }, genre) }
        built = true
    }

    /**
     * Starts the first [count] rows now instead of when each scrolls into view, so the hero has
     * its titles and the rows below are ready before focus reaches them. Requests share the
     * add-on client's concurrency limit; rows already loaded or loading are skipped.
     */
    fun prefetch(count: Int) { rows.take(count).forEach { ensureLoaded(it) } }

    fun ensureLoaded(row: RowState) {
        if (row.loadedOnce || row.loading) return
        load(row, reset = true)
    }

    fun retry(row: RowState) = load(row, reset = true)

    fun loadMore(row: RowState) {
        if (row.loading || !row.hasMore || row.kind == RowKind.TOP10) return
        val ref = row.ref ?: return
        if (!ref.catalog.supportsExtra("skip")) return
        load(row, reset = false)
    }

    private fun load(row: RowState, reset: Boolean) {
        val ref = row.ref ?: return
        row.job?.cancel()
        row.loading = true
        row.error = null
        row.job = scope.launch {
            val skip = if (reset) 0 else row.fetchedCount
            val serverGenre = AnimeBrowse.providerGenre(ref, row.genre)
            val extra = buildList {
                if (skip > 0) add("skip" to skip.toString())
                serverGenre?.let { add("genre" to it) }
            }
            when (val r = client.catalog(ref.addon, ref.catalog.type, ref.catalog.id, extra)) {
                is AddonResult.Ok -> {
                    val raw = r.value.metas
                    val ids = raw.map { it.type + ":" + it.id }
                    val repeatedPage = !reset && ids == row.lastPageIds
                    row.lastPageIds = ids
                    row.fetchedCount = skip + raw.size
                    val incoming = raw.filter { AnimeBrowse.matches(it, row.browseType, row.genre,
                        trustedCatalog = !row.animeOnly || CatalogRules.isAnimeCatalog(ref), serverFiltered = serverGenre != null) }
                    val merged = (if (reset) incoming else row.items + incoming).distinctBy { it.type + ":" + it.id }
                    row.items = if (row.kind == RowKind.TOP10) merged.take(CatalogRules.TOP_N) else merged
                    row.hasMore = row.kind != RowKind.TOP10 && raw.isNotEmpty() && !repeatedPage && ref.catalog.supportsExtra("skip")
                }
                is AddonResult.Failed -> row.error = r.reason
            }
            row.loading = false
            row.loadedOnce = true
        }
    }
}
