package com.vyrk.core.catalog

import com.vyrk.core.addon.Descriptor
import kotlinx.serialization.Serializable

/**
 * A row the user made: one add-on catalogue, optionally narrowed to one genre, shown on [tab].
 * The add-on is found by its manifest id, so reinstalling or reconfiguring it keeps the row.
 */
@Serializable
data class CustomRow(
    val id: String,
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null,
    val tab: String = "anime",
)

object CustomRows {
    const val KEY_PREFIX = "custom:"

    fun key(row: CustomRow) = KEY_PREFIX + row.id

    /** Catalogues a row can come from: anime ones only, none that need search, and a required genre must list its options. */
    fun catalogs(addons: List<Descriptor>): List<CatalogRef> =
        addons.flatMapIndexed { order, d -> d.manifest.catalogs.map { CatalogRef(d, it, order) } }
            .filter { ref ->
                val c = ref.catalog
                CatalogRules.isAnimeCatalog(ref) && c.type != "tv" && c.type != "channel" && c.requiredExtras.all { it == "genre" } &&
                    (!c.requiresExtra("genre") || genres(ref).isNotEmpty())
            }.distinctBy { it.key }

    private fun providerGenres(ref: CatalogRef) = ref.catalog.extra.firstOrNull { it.name == "genre" }
        ?.options.orEmpty().filter { it.isNotBlank() }.distinct()

    /** The only genres offered for custom rows, in this order. */
    val GENRES = listOf(
        "Action", "Adventure", "Asia", "Children", "Comedy", "Demon", "Drama", "Earth", "Ecchi", "Fantasy",
        "Fantasy World", "Harem", "Historical", "Horror", "Isekai", "Japan", "Josei", "Magic", "Martial Arts", "Mecha",
        "Military", "Music", "Mystery", "Present", "Psychological", "Romance", "School Life", "Science Fiction", "Seinen", "Shoujo",
        "Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life", "Sports", "Supernatural", "Superpower", "Thriller", "Violence", "Yaoi",
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Genres to offer: those of [GENRES] the add-on lists, in that order and in the add-on's own
     * spelling (that's what is sent). An anime catalogue that takes any genre without listing them gets all of [GENRES].
     */
    fun genres(ref: CatalogRef): List<String> {
        val own = providerGenres(ref)
        if (own.isEmpty()) return if (ref.catalog.supportsExtra("genre") && CatalogRules.isAnimeCatalog(ref)) GENRES else emptyList()
        val byName = own.associateBy { norm(it) }
        return GENRES.mapNotNull { byName[norm(it)] }
    }

    /** Whether "All" (no genre) is allowed: not when the add-on requires one. */
    fun allowsAll(ref: CatalogRef) = !ref.catalog.requiresExtra("genre")

    /** Custom rows live on the Anime tab only. */
    const val TAB = "anime"

    fun tabs(@Suppress("UNUSED_PARAMETER") ref: CatalogRef): List<String> = listOf(TAB)

    fun resolve(row: CustomRow, addons: List<Descriptor>): CatalogRef? =
        catalogs(addons).firstOrNull { it.addon.manifest.id == row.addonId && it.catalog.type == row.type && it.catalog.id == row.catalogId }

    fun of(id: String, ref: CatalogRef, genre: String?, tab: String) =
        CustomRow(id, ref.addon.manifest.id, ref.catalog.type, ref.catalog.id, genre?.takeIf { it in genres(ref) }, tab)

    /** A genre as shown: the list's spelling ("Science Fiction"), whatever the add-on calls it. */
    fun label(genre: String): String = GENRES.firstOrNull { norm(it) == norm(genre) } ?: genre

    /** "Romance · Most Popular", or the catalogue's usual title without a genre. */
    fun title(row: CustomRow, ref: CatalogRef): String =
        row.genre?.let { "${label(it)} · ${ref.name}" } ?: CatalogRules.rowTitle(ref)

    private fun same(r: CustomRow, ref: CatalogRef, genre: String?, tab: String) =
        r.addonId == ref.addon.manifest.id && r.type == ref.catalog.type && r.catalogId == ref.catalog.id && r.genre == genre && r.tab == tab

    /** Whether the tick box for [genre] (null: the whole catalogue) is ticked. */
    fun has(rows: List<CustomRow>, ref: CatalogRef, genre: String?, tab: String) = rows.any { same(it, ref, genre, tab) }

    /** Ticking adds the row at the end; unticking removes it. */
    fun toggle(rows: List<CustomRow>, ref: CatalogRef, genre: String?, tab: String, newId: String): List<CustomRow> =
        if (has(rows, ref, genre, tab)) rows.filterNot { same(it, ref, genre, tab) } else rows + of(newId, ref, genre, tab)

    /** This tab's rows that still resolve, in the user's order. */
    fun forTab(rows: List<CustomRow>, tab: String, addons: List<Descriptor>): List<Pair<CustomRow, CatalogRef>> =
        rows.filter { it.tab == tab && tab == TAB }.mapNotNull { r -> resolve(r, addons)?.let { r to it } }

    fun move(rows: List<CustomRow>, id: String, by: Int): List<CustomRow> {
        val i = rows.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in rows.indices) return rows
        return rows.toMutableList().apply { add(j, removeAt(i)) }
    }
}
