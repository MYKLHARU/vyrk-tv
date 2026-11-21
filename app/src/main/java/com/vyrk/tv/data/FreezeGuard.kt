package com.vyrk.tv.data

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers a source that was starting when the TV froze, so VYRK never gets stuck on it twice.
 *
 * Before a source starts, VYRK writes down which one it is (synchronously, so it's on disk even if
 * the power is pulled a moment later). When the picture appears, playback fails cleanly or the
 * player closes, the note is cleared. If VYRK next starts after the TV has restarted (the clock
 * since boot went backwards) and the note is still there, the TV froze or lost power while that
 * source was starting: it's remembered, that exact file is skipped from then on, other files of
 * the same kind are tried last, and VYRK says so once.
 */
class FreezeGuard(context: Context) {
    data class Frozen(val key: String, val kind: String, val title: String, val label: String)

    private val prefs = context.applicationContext.getSharedPreferences("vyrk_freeze", Context.MODE_PRIVATE)
    @Volatile private var keys: Set<String> = emptySet()
    @Volatile private var kinds: Set<String> = emptySet()

    /** The freeze found at this launch, for a one-time notice; null when there was none. */
    @Volatile var foundAtLaunch: Frozen? = null
        private set

    init {
        load()
        val pending = prefs.getString(PENDING, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (pending != null) {
            val rebooted = SystemClock.elapsedRealtime() < pending.optLong("since_boot", Long.MAX_VALUE)
            if (rebooted) {
                val f = Frozen(pending.optString("key"), pending.optString("kind"), pending.optString("title"), pending.optString("label"))
                if (f.key.isNotEmpty()) { remember(f); foundAtLaunch = f }
            }
            prefs.edit().remove(PENDING).commit()
        }
    }

    fun frozenKeys(): Set<String> = keys
    fun frozenKinds(): Set<String> = kinds

    /** Called just before a source starts. Written synchronously: the next thing might be a frozen TV. */
    fun starting(key: String, kind: String, title: String, label: String) {
        val o = JSONObject().put("key", key).put("kind", kind).put("title", title).put("label", label)
            .put("since_boot", SystemClock.elapsedRealtime())
        prefs.edit().putString(PENDING, o.toString()).commit()
    }

    /** The picture came up, playback failed cleanly, or the player closed. */
    fun settled() {
        if (prefs.contains(PENDING)) prefs.edit().remove(PENDING).apply()
    }

    fun noticeShown() { foundAtLaunch = null }

    /** Forget every frozen source (Settings). */
    fun clear() {
        prefs.edit().remove(FROZEN).apply()
        keys = emptySet(); kinds = emptySet()
    }

    val count: Int get() = keys.size

    private fun remember(f: Frozen) {
        val list = JSONArray(prefs.getString(FROZEN, "[]"))
        list.put(JSONObject().put("key", f.key).put("kind", f.kind).put("title", f.title).put("label", f.label))
        // Keep the latest 50.
        val trimmed = JSONArray((maxOf(0, list.length() - 50) until list.length()).map { list.get(it) })
        prefs.edit().putString(FROZEN, trimmed.toString()).commit()
        load()
    }

    private fun load() {
        val list = runCatching { JSONArray(prefs.getString(FROZEN, "[]")) }.getOrNull() ?: JSONArray()
        val k = HashSet<String>(); val d = HashSet<String>()
        for (i in 0 until list.length()) { val o = list.optJSONObject(i) ?: continue; k += o.optString("key"); o.optString("kind").takeIf { it.isNotEmpty() }?.let { d += it } }
        keys = k; kinds = d
    }

    private companion object {
        const val PENDING = "pending"
        const val FROZEN = "frozen"
    }
}
