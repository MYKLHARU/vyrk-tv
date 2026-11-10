package com.vyrk.core.catalog

import com.vyrk.core.addon.MetaPreview

/** Anime browsing never rewrites a title's provider ID or playback type. */
object AnimeBrowse {
    val genres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Isekai", "Music",
        "Romance", "Sci-Fi", "Seinen", "Shojo", "Shonen", "Slice of Life", "Sports", "Supernatural", "Thriller")

    private fun normalized(value: String): String = when (val n = value.lowercase().filter { it.isLetterOrDigit() }) {
        "sciencefiction", "scifi" -> "scifi"
        "shoujo" -> "shojo"
        "shounen" -> "shonen"
        else -> n
    }

    fun matchesGenre(tags: List<String>, genre: String?): Boolean =
        genre == null || tags.any { normalized(it) == normalized(genre) }

    /** Use the provider's spelling. Missing advertised options fall back to title tags. */
    fun providerGenre(ref: CatalogRef, genre: String?): String? {
        if (genre == null || !ref.catalog.supportsExtra("genre")) return null
        val options = ref.catalog.extra.firstOrNull { it.name == "genre" }?.options.orEmpty()
        return if (options.isEmpty()) genre else options.firstOrNull { normalized(it) == normalized(genre) }
    }

    fun matches(meta: MetaPreview, type: String? = null, genre: String? = null,
                trustedCatalog: Boolean = false, serverFiltered: Boolean = false): Boolean =
        (trustedCatalog || CatalogRules.isAnime(meta) || tags(meta).any { it.equals("Anime", true) }) &&
            (type == null || meta.type == type || type == "series" && meta.type == "anime") &&
            (serverFiltered || matchesGenre(tags(meta), genre))

    private fun tags(meta: MetaPreview): List<String> = meta.genres.ifEmpty { meta.genre }
        .ifEmpty { meta.links.filter { it.category.equals("Genres", true) }.map { it.name } }
}
