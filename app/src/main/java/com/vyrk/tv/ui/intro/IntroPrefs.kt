package com.vyrk.tv.ui.intro

import android.content.Context

/**
 * What the opening needs before the app's settings have loaded (Reduce motion and the opening
 * sound, copied here), and the title Home featured last (its id and backdrop), which Home opens
 * on so its first frame already has its picture.
 */
object IntroPrefs {
    private const val FILE = "vyrk_intro"

    /** The remembered featured title: its id, its artwork key (as the wallpaper uses) and its backdrop. */
    data class Featured(val id: String, val key: String, val backdrop: String)

    fun featured(context: Context): Featured? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val id = p.getString("featured_id", null) ?: return null
        val key = p.getString("featured_key", null) ?: return null
        val url = p.getString("featured_backdrop", null) ?: return null
        return Featured(id, key, url)
    }

    /** Home calls this whenever its featured title changes. */
    fun setFeatured(context: Context, id: String, key: String, backdrop: String) {
        if (featured(context) == Featured(id, key, backdrop)) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("featured_id", id).putString("featured_key", key).putString("featured_backdrop", backdrop).apply()
    }

    /** [candidates] with the remembered featured title first, so Home opens on the same title as last time. */
    fun <T> preferFeatured(context: Context, candidates: List<T>, id: (T) -> String): List<T> {
        val saved = featured(context)?.id ?: return candidates
        val i = candidates.indexOfFirst { id(it) == saved }
        return if (i <= 0) candidates else listOf(candidates[i]) + candidates.filterIndexed { j, _ -> j != i }
    }

    /** Reduce motion and the opening sound, copied here so the opening can decide before the app's settings load. */
    fun mirror(context: Context, reduceMotion: Boolean, sound: Boolean) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.getBoolean("reduce_motion", false) == reduceMotion && p.getBoolean("sound", false) == sound) return
        p.edit().putBoolean("reduce_motion", reduceMotion).putBoolean("sound", sound).apply()
    }

    fun reduceMotion(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("reduce_motion", false)

    /** Off unless switched on in Settings › Opening sound. */
    fun sound(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("sound", false)
}
