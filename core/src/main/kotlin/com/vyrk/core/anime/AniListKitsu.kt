package com.vyrk.core.anime

import java.util.concurrent.ConcurrentHashMap

/**
 * AniList ID → Kitsu ID. Most come from the snapshot bundled with the app
 * (`assets/anilist-kitsu.txt`, made by tools/gen_anime_ids.py); titles newer than it are learnt at
 * run time (from an add-on's answer) and kept for the session, misses included.
 */
class AniListKitsu private constructor(private val keys: IntArray, private val values: IntArray) {
    private val learnt = ConcurrentHashMap<Int, Int>()

    val size: Int get() = keys.size

    /** The Kitsu ID, or null when it is not known (yet). */
    fun kitsu(anilist: Int): Int? {
        val i = keys.binarySearch(anilist)
        if (i >= 0) return values[i]
        return learnt[anilist]?.takeIf { it > 0 }
    }

    /** True once a look-up for this title has been tried and failed this session. */
    fun isKnownMiss(anilist: Int): Boolean = learnt[anilist] == MISS

    /** Keep a Kitsu ID found at run time, or null to remember that none was found. */
    fun learn(anilist: Int, kitsu: Int?) {
        if (keys.binarySearch(anilist) >= 0) return
        learnt[anilist] = kitsu?.takeIf { it > 0 } ?: MISS
    }

    companion object {
        private const val MISS = 0
        val EMPTY = AniListKitsu(IntArray(0), IntArray(0))

        /** Lines of `<anilist> <kitsu>`; `#` comments, blank and malformed lines are skipped. */
        fun parse(text: String): AniListKitsu {
            val pairs = ArrayList<Long>(20_000)
            text.lineSequence().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEach
                val a = line.substring(0, sp).trim().toIntOrNull() ?: return@forEach
                val k = line.substring(sp + 1).trim().toIntOrNull() ?: return@forEach
                if (a > 0 && k > 0) pairs += (a.toLong() shl 32) or k.toLong()
            }
            pairs.sort()
            // One entry per AniList ID (the first after sorting).
            val keys = IntArray(pairs.size)
            val values = IntArray(pairs.size)
            var n = 0
            for (p in pairs) {
                val a = (p ushr 32).toInt()
                if (n > 0 && keys[n - 1] == a) continue
                keys[n] = a; values[n] = p.toInt(); n++
            }
            return AniListKitsu(keys.copyOf(n), values.copyOf(n))
        }

        /** The Kitsu ID in an add-on's ID for a title (`kitsu:1376`), or null. */
        fun fromId(id: String?): Int? =
            id?.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
    }
}
