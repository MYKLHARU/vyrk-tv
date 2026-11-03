package com.vyrk.core.library

import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaItem
import kotlinx.serialization.json.JsonPrimitive

/**
 * New-episode rules ported from stremio-core src/models/ctx/update_notifications.rs:
 * eligible items come from `should_pull_notifications`; catalogues that declare the
 * `lastVideosIds` extra are queried at most every 6 hours (or at once when series are added);
 * a video is new when it was released after the item was last watched and isn't in the future.
 */
object NewEpisodes {
    const val EXTRA = "lastVideosIds"
    const val REQUEST_EVERY_MS = 6L * 3600 * 1000
    const val ITEMS_COUNT = 100

    fun eligible(items: Collection<LibraryItem>): List<LibraryItem> =
        items.filter { it.shouldPullNotifications }.sortedByDescending { it.mtimeMs }.take(ITEMS_COUNT)

    fun shouldRequest(lastUpdatedMs: Long?, nowMs: Long, newSeriesAdded: Boolean): Boolean =
        newSeriesAdded || lastUpdatedMs == null || lastUpdatedMs + REQUEST_EVERY_MS <= nowMs

    /** Catalogue requests to make: (descriptor, type, catalogId, comma-joined sorted IDs). */
    data class Request(val addon: Descriptor, val type: String, val catalogId: String, val ids: String)

    fun requests(addons: List<Descriptor>, items: List<LibraryItem>): List<Request> {
        val out = mutableListOf<Request>()
        for (addon in addons) {
            for (cat in addon.manifest.catalogs) {
                if (!cat.supportsExtra(EXTRA)) continue
                val prefixes = addon.manifest.idPrefixes
                val ids = items.filter { it.type == cat.type && (prefixes == null || prefixes.any { p -> it.id.startsWith(p) }) }
                    .map { it.id }
                if (ids.isEmpty()) continue
                val limit = cat.extra.firstOrNull { it.name == EXTRA }?.optionsLimit?.takeIf { it > 0 } ?: ITEMS_COUNT
                // stremio-core sorts IDs before joining to improve add-on caching.
                out += Request(addon, cat.type, cat.id, ids.take(minOf(limit, ITEMS_COUNT)).sorted().joinToString(","))
            }
        }
        return out
    }

    /** Returns the release time when the video should be kept as new, null otherwise. */
    fun retainedRelease(lastWatchedMs: Long?, releasedMs: Long?, nowMs: Long): Long? = when {
        releasedMs == null -> null
        lastWatchedMs == null -> releasedMs
        lastWatchedMs < releasedMs && releasedMs <= nowMs -> releasedMs
        else -> null
    }

    /** New video IDs for one library item given its (fresh) meta. */
    fun newVideoIds(item: LibraryItem, meta: MetaItem, nowMs: Long): Set<String> {
        if (!item.shouldPullNotifications) return emptySet()
        return meta.videos.filter { v ->
            val rel = v.released?.let { LibraryItem.parseTime(JsonPrimitive(it)) }
            // Only items with a lastWatched are compared against releases (the None case is
            // stremio-core's catch-all for stored notifications, not for meta videos).
            item.state.lastWatchedMs != null && retainedRelease(item.state.lastWatchedMs, rel, nowMs) != null
        }.map { it.id }.toSet()
    }
}
