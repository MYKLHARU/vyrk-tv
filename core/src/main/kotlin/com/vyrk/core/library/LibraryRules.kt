package com.vyrk.core.library

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.Video

/**
 * Playback progress and watched-state rules ported from stremio-core
 * (src/models/player.rs `TimeChanged`, `item_state_update`; src/models/ctx/update_library.rs;
 * src/types/library/library_item.rs). Constants from src/constants.rs.
 */
object LibraryRules {
    const val WATCHED_THRESHOLD_COEF = 0.7
    const val CREDITS_THRESHOLD_COEF = 0.9
    const val PUSH_TO_LIBRARY_EVERY_MS = 180_000L

    /**
     * Applies a player time update. [watched] is the title's bitfield when the meta has videos.
     * Returns the updated item and whether it just crossed the watched threshold.
     */
    fun onTimeChanged(
        item: LibraryItem,
        streamVideoId: String,
        timeMs: Long,
        durationMs: Long,
        nowMs: Long,
        watched: WatchedBitField? = null,
    ): Pair<LibraryItem, Boolean> {
        var s = item.state
        val videoId = if (item.isLive) item.id else streamVideoId
        s = s.copy(lastWatchedMs = nowMs)
        if (s.videoId != videoId) {
            s = s.copy(
                videoId = videoId,
                overallTimeWatched = s.overallTimeWatched + s.timeWatched,
                timeWatched = 0,
                flaggedWatched = 0,
            )
        } else {
            val delta = (timeMs - s.timeOffset).coerceAtLeast(0)
            s = s.copy(timeWatched = s.timeWatched + delta, overallTimeWatched = s.overallTimeWatched + delta)
        }
        if (timeMs > s.timeOffset) s = s.copy(timeOffset = timeMs, duration = durationMs)

        var crossed = false
        if (!item.isLive && s.flaggedWatched == 0 && s.duration > 0 &&
            s.timeWatched.toDouble() > s.duration * WATCHED_THRESHOLD_COEF
        ) {
            crossed = true
            s = s.copy(flaggedWatched = 1, timesWatched = s.timesWatched + 1)
            if (watched != null) {
                watched.setVideo(videoId, true)
                s = s.copy(watched = watched.toString())
            }
        }
        var removed = item.removed
        var temp = item.temp
        if (temp && s.timesWatched == 0) removed = true
        if (removed) temp = true
        // Live channels never keep a VOD resume point.
        if (item.isLive) s = s.copy(timeOffset = 0, videoId = item.id)
        return item.copy(state = s, removed = removed, temp = temp) to crossed
    }

    /**
     * Applied when playback ends or the player closes: once past the credits threshold (or when
     * marked watched) the resume point clears and the pointer advances to [nextVideo].
     */
    fun onPlaybackEnded(item: LibraryItem, nextVideo: Video?, markedWatched: Boolean = false): LibraryItem {
        if (item.isLive) return item.copy(state = item.state.copy(timeOffset = 0, videoId = item.id))
        val s = item.state
        if (markedWatched || s.timeOffset.toDouble() > s.duration * CREDITS_THRESHOLD_COEF) {
            var next = item.copy(state = s.copy(timeOffset = 0))
            if (nextVideo != null) next = advanceToVideo(next, nextVideo.id)
            return next
        }
        return item
    }

    fun advanceToVideo(item: LibraryItem, videoId: String): LibraryItem {
        val s = item.state
        return item.copy(
            state = s.copy(
                videoId = videoId,
                overallTimeWatched = s.overallTimeWatched + s.timeWatched,
                timeWatched = 0,
                flaggedWatched = 0,
                timeOffset = 1,
            ),
        )
    }

    /**
     * Remove from Continue Watching: stremio-core `RewindLibraryItem`. The resume point goes to
     * zero; the library entry and watched history stay. The caller stamps mtime and saves.
     */
    fun rewind(item: LibraryItem): LibraryItem {
        var s = item.state.copy(timeOffset = 0)
        if (item.isLive) s = s.copy(lastWatchedMs = null)
        return item.copy(state = s)
    }

    /** `UpdateLibraryItem`: every local change refreshes mtime before saving and pushing. */
    fun touch(item: LibraryItem, nowMs: Long): LibraryItem = item.copy(mtimeMs = nowMs)

    fun addToLibrary(item: LibraryItem, nowMs: Long): LibraryItem =
        item.copy(removed = false, temp = false, ctimeMs = item.ctimeMs ?: nowMs)

    fun removeFromLibrary(item: LibraryItem): LibraryItem = item.copy(removed = true)

    /** `mark_as_watched` for a whole title (movies, or a series flag). */
    fun markWatched(item: LibraryItem, isWatched: Boolean, nowMs: Long): LibraryItem {
        val s = item.state
        return if (isWatched) {
            item.copy(state = s.copy(timesWatched = s.timesWatched + 1, lastWatchedMs = nowMs, timeOffset = 0))
        } else {
            item.copy(state = s.copy(timesWatched = 0))
        }
    }

    /** `mark_videos_as_watched` plus `last_watched` advancement to the newest release marked. */
    fun markVideosWatched(item: LibraryItem, meta: MetaItem, videos: List<Video>, isWatched: Boolean): LibraryItem {
        val wb = item.state.watchedBitField(meta.videos)
        videos.forEach { wb.setVideo(it.id, isWatched) }
        var s = item.state.copy(watched = wb.toString())
        if (isWatched) {
            val released = videos.lastOrNull()?.released?.let { LibraryItem.parseTime(kotlinx.serialization.json.JsonPrimitive(it)) }
            val lw = s.lastWatchedMs
            s = s.copy(
                lastWatchedMs = when {
                    lw != null && released != null && lw < released -> released
                    lw == null -> released
                    else -> lw
                },
            )
        }
        return item.copy(state = s)
    }

    /** Episodes before (and including) [video] in watch order, for "mark all previous". */
    fun videosUpTo(meta: MetaItem, video: Video): List<Video> {
        val ids = LibraryItemState.sortedVideoIds(meta.videos)
        val idx = ids.indexOf(video.id)
        if (idx < 0) return listOf(video)
        val wanted = ids.subList(0, idx + 1).toSet()
        return meta.videos.filter { it.id in wanted && (it.season ?: 0) != 0 || it.id == video.id }
    }

    /** Continue Watching row order: newest activity first. */
    fun continueWatching(items: Collection<LibraryItem>): List<LibraryItem> =
        items.filter { it.isInContinueWatching }
            .sortedWith(compareByDescending<LibraryItem> { it.state.lastWatchedMs ?: it.mtimeMs }.thenBy { it.id })

    /** stremio-core LibraryBucket::merge_items: newer-or-equal mtime wins. */
    fun merge(local: Map<String, LibraryItem>, incoming: Collection<LibraryItem>): Map<String, LibraryItem> {
        val out = local.toMutableMap()
        for (n in incoming) {
            val cur = out[n.id]
            if (cur == null || n.mtimeMs >= cur.mtimeMs) out[n.id] = n
        }
        return out
    }
}

/** Plan computed from datastoreMeta: which IDs to pull and which local items to push. */
data class SyncPlan(val pull: List<String>, val push: List<String>)

object LibrarySync {
    fun plan(local: Map<String, LibraryItem>, remote: List<com.vyrk.core.account.RemoteMeta>, nowMs: Long): SyncPlan {
        val remoteMap = remote.associate { it.id to it.mtimeMs }
        val pull = remote.filter { r -> local[r.id]?.let { r.mtimeMs > it.mtimeMs } ?: true }.map { it.id }
        val push = local.values.filter { item ->
            item.shouldSync(nowMs) && (remoteMap[item.id]?.let { item.mtimeMs > it } ?: true)
        }.map { it.id }
        return SyncPlan(pull.sorted(), push.sorted())
    }
}

/**
 * Pending/confirmed bookkeeping for pushes. Each local change bumps a revision; a push captures
 * the revisions it sent, and on success only clears entries whose revision didn't move while
 * the request was in flight.
 */
class PendingChanges {
    private val revisions = LinkedHashMap<String, Long>()
    private var counter = 0L

    @Synchronized
    fun mark(id: String): Long { counter += 1; revisions[id] = counter; return counter }

    @Synchronized
    fun snapshot(): Map<String, Long> = LinkedHashMap(revisions)

    @Synchronized
    fun confirm(sent: Map<String, Long>) {
        for ((id, rev) in sent) if (revisions[id] == rev) revisions.remove(id)
    }

    @Synchronized
    fun ids(): Set<String> = revisions.keys.toSet()

    @Synchronized
    fun restore(ids: Collection<String>) { ids.forEach { if (it !in revisions) mark(it) } }
}
