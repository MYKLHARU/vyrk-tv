package com.vyrk.core.catalog

import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.ManifestCatalog
import com.vyrk.core.addon.MetaPreview

/** One browsable catalogue: an add-on plus a catalogue that needs no extra input. */
data class CatalogRef(
    val addon: Descriptor,
    val catalog: ManifestCatalog,
    val addonOrder: Int,
) {
    val key: String get() = "${addon.manifest.id}/${catalog.type}/${catalog.id}"
    val name: String get() = catalog.name?.takeIf { it.isNotBlank() } ?: catalog.id
}

object CatalogRules {
    /** Streaming services recognised in catalogue names for ranked Top 10 rows, with aliases. */
    val SERVICES: List<Pair<String, List<String>>> = listOf(
        "Netflix" to listOf("netflix"),
        "Disney+" to listOf("disney+", "disney plus", "disneyplus", "disney"),
        "Prime Video" to listOf("prime video", "amazon prime", "amazon prime video", "amazon video"),
        "Max" to listOf("hbo max", "max", "hbo"),
        "Apple TV+" to listOf("apple tv+", "apple tv plus", "apple tv", "appletv"),
        "Paramount+" to listOf("paramount+", "paramount plus", "paramount"),
        "Peacock" to listOf("peacock"),
        "Hulu" to listOf("hulu"),
        "Crunchyroll" to listOf("crunchyroll"),
        "Hotstar" to listOf("hotstar", "jiohotstar"),
        "Starz" to listOf("starz"),
    )

    // Compiled once: building a Regex per catalogue and alias cost about 1.2 s on the UI thread of
    // an NVIDIA SHIELD (Android's ICU regex compiler is slow) each time a tab's rows were built.
    private val nonWord = Regex("""[^\p{L}\p{N}+]+""")
    /** Aliases as whole words: lowercased, punctuation folded to spaces, padded (" disney plus "). */
    private val serviceWords: List<Pair<String, List<String>>> =
        SERVICES.map { (service, aliases) -> service to aliases.map { " " + it.lowercase().replace(nonWord, " ").trim() + " " } }

    /** The service a catalogue is named after, or null. */
    fun streamingService(catalogName: String?): String? {
        if (catalogName.isNullOrBlank()) return null
        val n = " " + catalogName.lowercase().replace(nonWord, " ") + " "
        return serviceWords.firstOrNull { (_, words) -> words.any { n.contains(it) } }?.first
    }

    const val TOP_N = 10

    fun typeLabel(type: String): String = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        "anime" -> "Anime"
        "tv" -> "Live"
        "channel" -> "Channels"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** Home rows read "<catalogue> - <type>". */
    fun rowTitle(ref: CatalogRef): String = "${ref.name} - ${typeLabel(ref.catalog.type)}"

    /** Catalogues usable as browse rows: no required extras besides genre-with-options. */
    fun browseCatalogs(addons: List<Descriptor>): List<CatalogRef> =
        addons.flatMapIndexed { order, d ->
            d.manifest.catalogs.filter { c -> c.requiredExtras.isEmpty() }.map { CatalogRef(d, it, order) }
        }

    fun searchCatalogs(addons: List<Descriptor>): List<CatalogRef> =
        addons.flatMapIndexed { order, d ->
            d.manifest.catalogs.filter { c -> c.supportsExtra("search") && (c.requiredExtras - "search").isEmpty() }
                .map { CatalogRef(d, it, order) }
        }

    /**
     * Home order: Top 10 service rows first (in add-on order), then everything else.
     * Returns (top10 rows, other rows).
     */
    fun homeRows(addons: List<Descriptor>): Pair<List<CatalogRef>, List<CatalogRef>> {
        val all = browseCatalogs(addons).filter { it.catalog.type != "other" }
        val (top, rest) = all.partition { streamingService(it.name) != null }
        return top to rest
    }

    fun top10Title(ref: CatalogRef): String? =
        streamingService(ref.name)?.let { "Top 10 on $it · ${typeLabel(ref.catalog.type)}" }

    private val animePrefixes = listOf("kitsu:", "mal:", "anilist:", "anidb:")
    private val animeName = Regex("""(?i)\b(anime|animes)\b""")
    private val animeCatalogId = Regex("^(mal|kitsu|anilist|anidb)[.:/]", RegexOption.IGNORE_CASE)

    /** Anime only from reliable signals: the type, known anime ID prefixes, or explicit naming. */
    fun isAnime(meta: MetaPreview): Boolean =
        meta.type == "anime" || animePrefixes.any { meta.id.startsWith(it) }

    fun isAnimeCatalog(ref: CatalogRef): Boolean =
        ref.catalog.type == "anime" || animeName.containsMatchIn(ref.name) ||
            animeName.containsMatchIn(ref.catalog.id.replace('/', ' ').replace('.', ' ').replace('_', ' ')) ||
            animeCatalogId.containsMatchIn(ref.catalog.id) ||
            ref.addon.manifest.idPrefixes?.let { p -> p.isNotEmpty() && p.all { x -> animePrefixes.any { x.startsWith(it) } } } == true

    fun hasLiveCatalogs(addons: List<Descriptor>): Boolean =
        addons.any { d -> d.manifest.catalogs.any { it.type == "tv" || it.type == "channel" } }

    /** Add-ons that need Stremio's local streaming server (p2p or local-only transports). */
    fun needsStreamingServer(d: Descriptor): Boolean {
        val u = d.transportUrl.lowercase()
        return d.manifest.behaviorHints.p2p && !d.manifest.hasResource("catalog") && !d.manifest.hasResource("meta") ||
            u.startsWith("http://127.0.0.1:11470") || u.startsWith("http://localhost:11470")
    }
}
