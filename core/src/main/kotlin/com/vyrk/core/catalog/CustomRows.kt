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

    /** Catalogues a row can come from: none that need search, and a required genre must list its options. */
    fun catalogs(addons: List<Descriptor>): List<CatalogRef> =
        addons.flatMapIndexed { order, d -> d.manifest.catalogs.map { CatalogRef(d, it, order) } }
            .filter { ref ->
                val c = ref.catalog
                c.type != "tv" && c.type != "channel" && c.requiredExtras.all { it == "genre" } &&
                    (!c.requiresExtra("genre") || providerGenres(ref).isNotEmpty())
            }.distinctBy { it.key }

    private fun providerGenres(ref: CatalogRef) = ref.catalog.extra.firstOrNull { it.name == "genre" }
        ?.options.orEmpty().filter { it.isNotBlank() }.distinct()

    /** Genres to offer: the add-on's own list; an anime catalogue that takes any genre gets the usual anime ones. */
    fun genres(ref: CatalogRef): List<String> {
        val own = providerGenres(ref)
        if (own.isNotEmpty() || !ref.catalog.supportsExtra("genre")) return own
        return if (CatalogRules.isAnimeCatalog(ref)) AnimeBrowse.genres else emptyList()
    }

    /** Whether "All" (no genre) is allowed: not when the add-on requires one. */
    fun allowsAll(ref: CatalogRef) = !ref.catalog.requiresExtra("genre")

    /**
     * Tabs a row from [ref] can go on. Anime catalogues live on the Anime tab (Home never shows
     * anime); others can go on Home or the tab for their type.
     */
    fun tabs(ref: CatalogRef): List<String> = when {
        CatalogRules.isAnimeCatalog(ref) -> listOf("anime")
        ref.catalog.type == "movie" -> listOf("home", "movies")
        ref.catalog.type == "series" -> listOf("home", "series")
        else -> listOf("home")
    }

    fun resolve(row: CustomRow, addons: List<Descriptor>): CatalogRef? =
        catalogs(addons).firstOrNull { it.addon.manifest.id == row.addonId && it.catalog.type == row.type && it.catalog.id == row.catalogId }

    fun of(id: String, ref: CatalogRef, genre: String?, tab: String) =
        CustomRow(id, ref.addon.manifest.id, ref.catalog.type, ref.catalog.id, genre?.takeIf { it in genres(ref) }, tab)

    /** "Romance · Most Popular", or the catalogue's usual title without a genre. */
    fun title(row: CustomRow, ref: CatalogRef): String =
        row.genre?.let { "$it · ${ref.name}" } ?: CatalogRules.rowTitle(ref)

    /** This tab's rows that still resolve, in the user's order. */
    fun forTab(rows: List<CustomRow>, tab: String, addons: List<Descriptor>): List<Pair<CustomRow, CatalogRef>> =
        rows.filter { it.tab == tab }.mapNotNull { r -> resolve(r, addons)?.let { r to it } }

    fun move(rows: List<CustomRow>, id: String, by: Int): List<CustomRow> {
        val i = rows.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in rows.indices) return rows
        return rows.toMutableList().apply { add(j, removeAt(i)) }
    }
}
