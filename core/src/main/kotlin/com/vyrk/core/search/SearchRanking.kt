package com.vyrk.core.search

import com.vyrk.core.addon.MetaPreview
import java.text.Normalizer

/** Merges search results from several catalogues and ranks them per type. */
object SearchRanking {
    fun normalise(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Higher is better. Exact > prefix > all tokens > some tokens; earlier source rank breaks ties. */
    fun score(query: String, name: String): Int {
        val q = normalise(query)
        val n = normalise(name)
        if (q.isEmpty() || n.isEmpty()) return 0
        if (n == q) return 1000
        if (n.startsWith("$q ") || n.startsWith(q)) return 800
        val qt = q.split(' ').filter { it.isNotEmpty() }
        val nt = n.split(' ').toSet()
        val hits = qt.count { t -> nt.any { it == t } }
        val prefixHits = qt.count { t -> nt.any { it.startsWith(t) } }
        return when {
            hits == qt.size -> 600 + (if (n.contains(q)) 50 else 0) - ((nt.size - qt.size).coerceIn(0, 20) * 5)
            prefixHits == qt.size -> 500
            hits > 0 -> 200 + hits * 50
            n.contains(q) -> 150
            else -> 0
        }
    }

    data class Source(val addonOrder: Int, val metas: List<MetaPreview>)

    /** Returns results grouped by type ("movie", "series", …) in stable, ranked order. */
    fun merge(query: String, sources: List<Source>, perTypeLimit: Int = 40): Map<String, List<MetaPreview>> {
        data class Hit(val meta: MetaPreview, val score: Int, val order: Int, val pos: Int)
        val best = LinkedHashMap<String, Hit>()
        for (src in sources.sortedBy { it.addonOrder }) {
            src.metas.forEachIndexed { pos, m ->
                val key = "${m.type}:${m.id}"
                val h = Hit(m, score(query, m.name), src.addonOrder, pos)
                val cur = best[key]
                if (cur == null || h.score > cur.score) best[key] = h
            }
        }
        return best.values
            .filter { it.score > 0 }
            .groupBy { it.meta.type }
            .mapValues { (_, hits) ->
                hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.pos }.thenBy { it.order })
                    .take(perTypeLimit).map { it.meta }
            }
    }
}
