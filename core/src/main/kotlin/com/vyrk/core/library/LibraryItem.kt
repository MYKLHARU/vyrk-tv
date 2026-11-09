package com.vyrk.core.library

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.Video
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Stremio library item, field-for-field with stremio-core `LibraryItem` so items written here
 * read correctly in other Stremio apps and the reverse. Times are epoch milliseconds in memory
 * and RFC 3339 strings on the wire.
 */
data class LibraryItem(
    val id: String,
    val name: String,
    val type: String,
    val poster: String? = null,
    val posterShape: String = "poster",
    val removed: Boolean = false,
    val temp: Boolean = false,
    val ctimeMs: Long? = null,
    val mtimeMs: Long = 0,
    val state: LibraryItemState = LibraryItemState(),
    val behaviorHints: LibraryBehaviorHints = LibraryBehaviorHints(),
) {
    val isLive: Boolean get() = behaviorHints.isLive || com.vyrk.core.addon.isLiveType(type)

    /** stremio-core `is_in_continue_watching`. */
    val isInContinueWatching: Boolean
        get() = type != "other" && (!removed || temp) && state.timeOffset > 0

    /** Progress 0..1 for UI bars; 0 when unknown. */
    val progress: Float
        get() = if (state.timeOffset > 0 && state.duration > 0)
            (state.timeOffset.toDouble() / state.duration).coerceIn(0.0, 1.0).toFloat() else 0f

    val watched: Boolean get() = state.timesWatched > 0

    /** A visible member of the library (not removed and not a temporary playback item). */
    val inLibrary: Boolean get() = !removed && !temp

    /** stremio-core `should_sync` with the one-year window for recently removed items. */
    fun shouldSync(nowMs: Long): Boolean {
        val yearAgo = nowMs - 365L * 24 * 3600 * 1000
        val recentlyRemoved = removed && mtimeMs > yearAgo
        return type != "other" && (!removed || recentlyRemoved)
    }

    /** stremio-core `should_pull_notifications`. */
    val shouldPullNotifications: Boolean
        get() = !state.noNotif && !isLive && type != "other" && type != "movie" &&
            behaviorHints.defaultVideoId == null && !removed && !temp

    fun eqNoMtime(o: LibraryItem): Boolean = copy(mtimeMs = 0) == o.copy(mtimeMs = 0)

    fun watchedBitField(videos: List<Video>): WatchedBitField = state.watchedBitField(videos)

    fun toJson(): JsonObject = buildJsonObject {
        put("_id", id)
        put("name", name)
        put("type", type)
        put("poster", poster ?: "")
        put("posterShape", posterShape)
        put("removed", removed)
        put("temp", temp)
        put("_ctime", ctimeMs?.let { iso(it) } ?: "")
        put("_mtime", iso(mtimeMs))
        put("state", state.toJson())
        put("behaviorHints", behaviorHints.toJson())
    }

    companion object {
        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

        fun parseTime(el: JsonElement?): Long? {
            val p = el as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            p.longOrNull?.let { return it }
            val s = p.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        }

        fun fromJson(el: JsonElement): LibraryItem {
            val o = el as JsonObject
            val state = (o["state"] as? JsonObject)?.let { LibraryItemState.fromJson(it) } ?: LibraryItemState()
            return LibraryItem(
                id = o.s("_id") ?: error("missing _id"),
                name = o.s("name") ?: "",
                type = o.s("type") ?: error("missing type"),
                poster = o.s("poster")?.takeIf { it.isNotBlank() },
                posterShape = o.s("posterShape") ?: "poster",
                removed = o.b("removed") ?: false,
                temp = o.b("temp") ?: false,
                ctimeMs = parseTime(o["_ctime"]),
                mtimeMs = parseTime(o["_mtime"]) ?: error("missing _mtime"),
                state = state,
                behaviorHints = (o["behaviorHints"] as? JsonObject)?.let { LibraryBehaviorHints.fromJson(it) }
                    ?: LibraryBehaviorHints(),
            )
        }

        /** New temporary item created when playback starts on a title not in the library. */
        fun fromMeta(meta: MetaPreview, nowMs: Long): LibraryItem = LibraryItem(
            id = meta.id, name = meta.name, type = meta.type, poster = meta.poster,
            posterShape = meta.posterShape ?: "poster", removed = true, temp = true,
            ctimeMs = nowMs, mtimeMs = nowMs,
            state = LibraryItemState(lastWatchedMs = nowMs),
            behaviorHints = LibraryBehaviorHints(defaultVideoId = meta.behaviorHints.defaultVideoId, isLive = com.vyrk.core.addon.isLiveType(meta.type)),
        )
    }
}

data class LibraryBehaviorHints(
    val defaultVideoId: String? = null,
    val featuredVideoId: String? = null,
    val hasScheduledVideos: Boolean = false,
    val isLive: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("defaultVideoId", defaultVideoId)
        put("featuredVideoId", featuredVideoId)
        put("hasScheduledVideos", hasScheduledVideos)
        if (isLive) put("isLive", true)
    }

    companion object {
        fun fromJson(o: JsonObject) = LibraryBehaviorHints(
            defaultVideoId = o.s("defaultVideoId"),
            featuredVideoId = o.s("featuredVideoId"),
            hasScheduledVideos = o.b("hasScheduledVideos") ?: false,
            isLive = o.b("isLive") ?: false,
        )
    }
}

data class LibraryItemState(
    val lastWatchedMs: Long? = null,
    val timeWatched: Long = 0,
    val timeOffset: Long = 0,
    val overallTimeWatched: Long = 0,
    val timesWatched: Int = 0,
    val flaggedWatched: Int = 0,
    val duration: Long = 0,
    val videoId: String? = null,
    val watched: String? = null,
    val noNotif: Boolean = false,
) {
    /** stremio-core `watched_bitfield`: videos sorted by season, episode, then release time. */
    fun watchedBitField(videos: List<Video>): WatchedBitField {
        val ids = sortedVideoIds(videos)
        val w = watched ?: return WatchedBitField.fromArray(emptyList(), ids)
        return runCatching { WatchedBitField.constructAndResize(w, ids) }
            .getOrElse { WatchedBitField.fromArray(emptyList(), ids) }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("lastWatched", lastWatchedMs?.let { LibraryItem.iso(it) })
        put("timeWatched", timeWatched)
        put("timeOffset", timeOffset)
        put("overallTimeWatched", overallTimeWatched)
        put("timesWatched", timesWatched)
        put("flaggedWatched", flaggedWatched)
        put("duration", duration)
        put("video_id", videoId)
        put("watched", watched)
        put("noNotif", noNotif)
    }

    companion object {
        fun sortedVideoIds(videos: List<Video>): List<String> = videos.sortedWith(
            compareBy<Video>(
                { it.seriesInfo?.season?.toLong() ?: Long.MIN_VALUE },
                { it.seriesInfo?.episode?.toLong() ?: Long.MIN_VALUE },
                { LibraryItem.parseTime(it.released?.let { r -> JsonPrimitive(r) }) ?: Long.MIN_VALUE },
            ),
        ).map { it.id }

        fun fromJson(o: JsonObject) = LibraryItemState(
            lastWatchedMs = LibraryItem.parseTime(o["lastWatched"]),
            timeWatched = o.l("timeWatched"),
            timeOffset = o.l("timeOffset"),
            overallTimeWatched = o.l("overallTimeWatched"),
            timesWatched = o.l("timesWatched").toInt(),
            flaggedWatched = o.l("flaggedWatched").toInt(),
            duration = o.l("duration"),
            videoId = o.s("video_id")?.takeIf { it.isNotBlank() },
            watched = o.s("watched")?.takeIf { it.isNotBlank() },
            noNotif = o.b("noNotif") ?: false,
        )
    }
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.l(k: String): Long {
    val p = this[k] as? JsonPrimitive ?: return 0
    return p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0
}

/** Used by the season guide: the next released episode after [videoId] (season 0 skipped). */
fun MetaItem.nextVideo(videoId: String, nowMs: Long): Video? {
    if (com.vyrk.core.addon.isLiveType(type)) return null
    val sorted = videos.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    val idx = sorted.indexOfFirst { it.id == videoId }
    if (idx < 0) return null
    val current = sorted[idx]
    val next = sorted.getOrNull(idx + 1) ?: return null
    if ((current.season ?: 0) != 0 && (next.season ?: 0) == 0) return null
    val released = LibraryItem.parseTime(next.released?.let { JsonPrimitive(it) })
    if (released != null && released > nowMs) return null
    return next
}
