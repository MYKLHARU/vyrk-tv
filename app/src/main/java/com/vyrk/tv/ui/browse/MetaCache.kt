package com.vyrk.tv.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.vyrk.core.addon.AddonClient
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaItem
import java.util.concurrent.ConcurrentHashMap

/** Session metadata expires and is invalidated when the active providers change. */
class MetaCache(
    private val client: AddonClient,
    private val currentAddons: (() -> List<Descriptor>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60_000,
    /** A fetch finished: requested type and ID, and the answer (null when no add-on had one). */
    private val onFetched: ((String, String, MetaItem?) -> Unit)? = null,
) {
    private data class Entry(val meta: MetaItem, val providers: List<Descriptor>?, val saved: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    fun peek(type: String, id: String): MetaItem? = cached("$type:$id", currentAddons?.invoke())
    private fun cached(key: String, addons: List<Descriptor>?): MetaItem? {
        val e = cache[key] ?: return null
        if (clock() - e.saved >= ttlMs || (addons != null && e.providers != null && e.providers != addons)) return null
        return e.meta
    }
    suspend fun get(addons: List<Descriptor>, type: String, id: String): MetaItem? {
        val key = "$type:$id"
        cached(key, addons)?.let { return it }
        val m = client.firstMeta(addons, type, id)
        onFetched?.invoke(type, id, m)
        if (m == null) return null
        cache[key] = Entry(m, addons.toList(), clock()); version += 1
        return m
    }
    fun put(m: MetaItem) {
        cache["${m.type}:${m.id}"] = Entry(m, currentAddons?.invoke()?.toList(), clock()); version += 1
    }
}
