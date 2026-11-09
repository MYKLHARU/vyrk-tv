package com.vyrk.core.art

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview

/** Logo-only inheritance through explicit prequel links, never title-search guesses. */
object SeasonLogos {
    private val animeId = Regex("(?:kitsu|mal):\\d+")
    private val link = Regex("^stremio:///detail/series/((?:kitsu|mal):\\d+)$")
    private val season = Regex("(?i)\\s+(?:(?:\\d+(?:st|nd|rd|th)?|[ivx]+)\\s+season|season\\s+\\d+)\\s*$")
    private fun base(name: String) = name.replace(season, "").lowercase().filter { it.isLetterOrDigit() }

    fun prequel(meta: MetaPreview, full: MetaItem?): String? {
        if (meta.type != "series" || !animeId.matches(meta.id)) return null
        val links = Artwork.matchingMeta(meta, full)?.links.orEmpty() + meta.links
        return links.firstNotNullOfOrNull { candidate ->
            val prequel = candidate.category.equals("prequel", ignoreCase = true) ||
                (candidate.category.equals("Franchise", ignoreCase = true) && candidate.name.startsWith("Prequel:", ignoreCase = true))
            if (!prequel) null
            else link.matchEntire(candidate.url)?.groupValues?.get(1)?.takeUnless { it == meta.id }
        }
    }

    private fun responseMatches(parent: MetaItem, expectedId: String): Boolean =
        parent.id == expectedId || when (expectedId.substringBefore(':')) {
            "kitsu" -> animeId.matches(expectedId) && parent.kitsuId == expectedId.substringAfter(':')
            "mal" -> animeId.matches(expectedId) && parent.malId == expectedId.substringAfter(':')
            else -> false
        }

    fun sameSeries(child: MetaPreview, parent: MetaItem, expectedId: String): Boolean =
        responseMatches(parent, expectedId) && parent.type == child.type && base(child.name).isNotEmpty() &&
            base(child.name) == base(parent.name)

    suspend fun resolve(meta: MetaPreview, full: MetaItem?, fetch: suspend (String) -> MetaItem?): List<String> {
        var details = Artwork.matchingMeta(meta, full)
        val selected = meta.copy(name = details?.name?.takeIf { it.isNotBlank() } ?: meta.name)
        var current = selected
        val seen = mutableSetOf(meta.id)
        repeat(3) {
            val id = prequel(current, details) ?: return emptyList()
            if (!seen.add(id)) return emptyList()
            val parent = fetch(id) ?: return emptyList()
            if (!sameSeries(selected, parent, id)) return emptyList()
            val logos = Artwork.logos(parent.toPreview(), parent, null)
            if (logos.isNotEmpty()) return logos
            current = parent.toPreview(); details = parent
        }
        return emptyList()
    }
}
