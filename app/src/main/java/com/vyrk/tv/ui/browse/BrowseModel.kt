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
import com.vyrk.core.catalog.CatalogRef
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class RowKind { CONTINUE, TOP10, CATALOG }

@Stable
class RowState(val key: String, val kind: RowKind, val ref: CatalogRef?, initialTitle: String) {
    var title by mutableStateOf(initialTitle)
    var items by mutableStateOf<List<MetaPreview>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(true)
    var loadedOnce by mutableStateOf(false)
    internal var job: Job? = null
}

/** Full metas for headers and the hero, shared by every screen. */
class MetaCache(private val client: AddonClient) {
    private val cache = ConcurrentHashMap<String, MetaItem>()
    fun peek(type: String, id: String): MetaItem? = cache["$type:$id"]
    suspend fun get(addons: List<Descriptor>, type: String, id: String): MetaItem? {
        cache["$type:$id"]?.let { return it }
        val m = client.firstMeta(addons, type, id) ?: return null
        cache["$type:$id"] = m
        return m
    }
    fun put(m: MetaItem) { cache["${m.type}:${m.id}"] = m }
}

/** Catalogue rows for one tab, loaded lazily and paged with the `skip` extra. */
class BrowseModel(
    private val session: ProfileSession,
    private val client: AddonClient,
    val tab: Tab,
    private val scope: CoroutineScope,
) {
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

    fun rebuild(addons: List<Descriptor>, hiddenRows: Set<String>) {
        val (top, rest) = CatalogRules.homeRows(addons)
        hasTop10Catalogs = top.isNotEmpty()
        val catalogs: List<Pair<CatalogRef, RowKind>> = when (tab) {
            Tab.HOME -> top.map { it to RowKind.TOP10 } + rest.filter { it.catalog.type != "tv" && it.catalog.type != "channel" }.map { it to RowKind.CATALOG }
            Tab.MOVIES -> (top + rest).filter { it.catalog.type == "movie" }.map { it to RowKind.CATALOG }
            Tab.SERIES -> (top + rest).filter { it.catalog.type == "series" }.map { it to RowKind.CATALOG }
            Tab.ANIME -> (top + rest).filter { CatalogRules.isAnimeCatalog(it) }.map { it to RowKind.CATALOG }
            Tab.LIVE -> rest.filter { it.catalog.type == "tv" || it.catalog.type == "channel" }.map { it to RowKind.CATALOG }
            else -> emptyList()
        }
        val visible = catalogs.filter { it.first.key !in hiddenRows }
        val sig = visible.joinToString("|") { it.first.key + it.second } + "#" + addons.joinToString { it.transportUrl.hashCode().toString() }
        if (sig == signature) { built = true; return }
        signature = sig
        val old = rows.associateBy { it.key }
        rows = visible.map { (ref, kind) ->
            old[ref.key + kind]?.takeIf { it.ref?.addon?.transportUrl == ref.addon.transportUrl }
                ?: RowState(ref.key + kind, kind, ref, if (kind == RowKind.TOP10) CatalogRules.top10Title(ref) ?: CatalogRules.rowTitle(ref) else CatalogRules.rowTitle(ref))
        }
        built = true
    }

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
            val skip = if (reset) 0 else row.items.size
            val extra = if (skip > 0) listOf("skip" to skip.toString()) else emptyList()
            when (val r = client.catalog(ref.addon, ref.catalog.type, ref.catalog.id, extra)) {
                is AddonResult.Ok -> {
                    val incoming = r.value.metas
                    val merged = if (reset) incoming else (row.items + incoming).distinctBy { it.type + ":" + it.id }
                    row.items = if (row.kind == RowKind.TOP10) merged.take(CatalogRules.TOP_N) else merged
                    row.hasMore = row.kind != RowKind.TOP10 && incoming.isNotEmpty() && ref.catalog.supportsExtra("skip") && merged.size > (if (reset) 0 else skip)
                }
                is AddonResult.Failed -> row.error = r.reason
            }
            row.loading = false
            row.loadedOnce = true
        }
    }
}
