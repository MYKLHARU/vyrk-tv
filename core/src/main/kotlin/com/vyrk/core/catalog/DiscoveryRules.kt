package com.vyrk.core.catalog

import com.vyrk.core.addon.Descriptor

/** Discover can satisfy genre input, but must never request search-only catalogues. */
object DiscoveryRules {
    fun catalogs(addons: List<Descriptor>, type: String, animeOnly: Boolean, hidden: Set<String> = emptySet()): List<CatalogRef> =
        addons.flatMapIndexed { order, addon ->
            addon.manifest.catalogs.map { CatalogRef(addon, it, order) }
        }.filter { ref ->
            val c = ref.catalog
            (c.type == type || c.type == "anime") &&
                (!animeOnly || CatalogRules.isAnimeCatalog(ref)) && ref.key !in hidden &&
                c.requiredExtras.all { it == "genre" } &&
                (!c.requiresExtra("genre") || providerGenres(ref).isNotEmpty())
        }.distinctBy { it.key }

    private fun providerGenres(ref: CatalogRef) = ref.catalog.extra.firstOrNull { it.name == "genre" }
        ?.options.orEmpty().filter { it.isNotBlank() }.distinct()

    fun genres(ref: CatalogRef, animeOnly: Boolean): List<String> =
        if (ref.catalog.requiresExtra("genre") || !animeOnly) providerGenres(ref) else AnimeBrowse.genres

    /** A required genre never sends an invalid unfiltered request. Stale selections reset. */
    fun genre(ref: CatalogRef, animeOnly: Boolean, selected: String?): String? {
        val options = genres(ref, animeOnly)
        return selected?.takeIf { it in options } ?: if (ref.catalog.requiresExtra("genre")) options.firstOrNull() else null
    }
}
