package com.vyrk.core.library

import com.vyrk.core.addon.Video
import kotlinx.serialization.json.JsonPrimitive

/** Season order and the episode to watch next, shared by the TV and phone title pages. */
object EpisodeGuide {
    /** Seasons in order, with specials (season 0) last. */
    fun seasons(videos: List<Video>): List<Int> =
        videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })

    /** When the episode airs or aired, in epoch milliseconds, or null if the add-on gave no usable date. */
    fun releasedAt(v: Video): Long? = v.released?.let { LibraryItem.parseTime(JsonPrimitive(it)) }

    /**
     * Up next: the episode in progress, else the first aired, unwatched episode after the last
     * watched one. Specials are never up next.
     */
    fun upNextId(videos: List<Video>, item: LibraryItem?, watched: WatchedBitField?, nowMs: Long): String? {
        val ordered = videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        item?.state?.videoId?.takeIf { id -> item.isInContinueWatching && ordered.any { it.id == id } }?.let { return it }
        val last = ordered.indexOfLast { watched?.getVideo(it.id) == true }
        if (last < 0) return null
        return ordered.drop(last + 1).firstOrNull { watched?.getVideo(it.id) != true && (releasedAt(it) ?: 0L) <= nowMs }?.id
    }

    /**
     * The season a title page opens at: the one with the last played episode, else the up-next
     * episode's, else the first numbered season, else specials.
     */
    fun startSeason(videos: List<Video>, item: LibraryItem?, upNextId: String?): Int {
        fun seasonOf(id: String?) = id?.let { vid -> videos.firstOrNull { it.id == vid }?.season }
        val seasons = seasons(videos)
        return seasonOf(item?.state?.videoId) ?: seasonOf(upNextId) ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
    }
}
