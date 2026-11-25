package com.vyrk.core.anime

/**
 * Kitsu / MyAnimeList ID → IMDb ID (else TMDB ID), bundled with the app (`assets/anime-art-ids.txt`,
 * made by tools/gen_anime_ids.py), so anime titles get logos and backdrops even when their add-on
 * gives no IMDb ID. Only for artwork: titles keep their own IDs for details, streams and the library.
 */
class AnimeArtIds private constructor(
    private val kitsuKeys: IntArray, private val kitsuRefs: Array<String>,
    private val malKeys: IntArray, private val malRefs: Array<String>,
) {
    val size: Int get() = kitsuKeys.size + malKeys.size

    /** "tt…", "tv:N" or "movie:N" for a `kitsu:…` or `mal:…` ID (episode IDs too), or null. */
    fun ref(id: String): String? {
        val prefix = id.substringBefore(':', "")
        val n = id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return null
        val (keys, refs) = when (prefix) { "kitsu" -> kitsuKeys to kitsuRefs; "mal" -> malKeys to malRefs; else -> return null }
        val i = keys.binarySearch(n)
        return if (i >= 0) refs[i] else null
    }

    /**
     * The ID artwork lookups take (`tt…`, or `tmdb:N` as TMDB and fanart.tv read it) for an anime
     * title of [type]. A TMDB ID is used only for its own kind: a show's for a series, a film's for a film.
     */
    fun lookupId(id: String, type: String): String? {
        val r = ref(id) ?: return null
        return when {
            r.startsWith("tt") -> r
            r.startsWith("tv:") && type == "series" -> "tmdb:" + r.removePrefix("tv:")
            r.startsWith("movie:") && type == "movie" -> "tmdb:" + r.removePrefix("movie:")
            else -> null
        }
    }

    companion object {
        val EMPTY = AnimeArtIds(IntArray(0), emptyArray(), IntArray(0), emptyArray())
        private val ref = Regex("""tt\d{5,10}|(tv|movie):\d+""")

        /** Lines of `<kitsu or -> <mal or -> <ref>`; `#` comments and malformed lines are skipped. */
        fun parse(text: String): AnimeArtIds {
            val kitsu = java.util.TreeMap<Int, String>()
            val mal = java.util.TreeMap<Int, String>()
            text.lineSequence().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val parts = line.trim().split(' ')
                if (parts.size != 3 || !ref.matches(parts[2])) return@forEach
                parts[0].toIntOrNull()?.takeIf { it > 0 }?.let { kitsu.putIfAbsent(it, parts[2]) }
                parts[1].toIntOrNull()?.takeIf { it > 0 }?.let { mal.putIfAbsent(it, parts[2]) }
            }
            return AnimeArtIds(kitsu.keys.toIntArray(), kitsu.values.toTypedArray(), mal.keys.toIntArray(), mal.values.toTypedArray())
        }
    }
}
