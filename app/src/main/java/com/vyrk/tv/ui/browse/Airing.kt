package com.vyrk.tv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.anime.AiringStatus
import com.vyrk.core.anime.Airing
import com.vyrk.core.anime.AniListAiring
import com.vyrk.tv.R
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.str
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** AniList schedules for followed anime, kept for half an hour. */
object AiringSchedule {
    private const val FRESH_MS = 30L * 60 * 1000
    private val byMal = ConcurrentHashMap<String, Pair<Long, AiringStatus?>>()

    /** Status per library id for [followed] titles, fetching what's missing or stale. */
    suspend fun statuses(graph: AppGraph, followed: List<com.vyrk.core.library.LibraryItem>, nowMs: Long): Map<String, AiringStatus> {
        val limit = Semaphore(4)
        val malById = coroutineScope {
            followed.map { item ->
                async {
                    val mal = Airing.malIdOf(item.id) ?: Airing.kitsuIdOf(item.id)?.let { k -> limit.withPermit { graph.animeIds.mal(k) } }
                    mal?.let { item.id to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        val stale = malById.values.distinct().filter { (byMal[it]?.first ?: 0) < nowMs - FRESH_MS }
        if (stale.isNotEmpty()) {
            val found = AniListAiring(graph.transport).fetch(stale, nowMs / 1000).associateBy { it.malId }
            // A failed lookup comes back empty: keep what we had rather than forgetting every show.
            if (found.isNotEmpty() || byMal.isEmpty()) stale.forEach { byMal[it] = nowMs to found[it] }
        }
        return malById.mapNotNull { (id, mal) -> byMal[mal]?.second?.let { id to it } }.toMap()
    }
}

/**
 * The Anime tab's "New episodes" (aired this week, not yet reached) and "Airing this week" rows,
 * from AniList's schedule for the anime you follow. New episodes also badge their posters and
 * episodes everywhere, like new episodes from add-ons.
 */
@Composable
fun rememberAiringRows(session: ProfileSession, enabled: Boolean): List<RowState> {
    val graph = LocalGraph.current
    val library by session.library.collectAsState()
    val newRow = remember { RowState("airing:new", RowKind.CATALOG, null, "") }
    val soonRow = remember { RowState("airing:soon", RowKind.CATALOG, null, "") }
    newRow.title = str(R.string.new_episodes)
    soonRow.title = str(R.string.airing_this_week)
    val today = str(R.string.today)
    val tomorrow = str(R.string.tomorrow)
    val res = androidx.compose.ui.platform.LocalContext.current
    fun ep(n: Int) = res.getString(R.string.episode_short, n)
    // What matters: which shows, and how far you've got in each.
    val followedKey = remember(library) {
        Airing.followed(library.values, System.currentTimeMillis()).map { it.id + "@" + it.state.videoId }
    }
    LaunchedEffect(enabled, followedKey) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val followed = Airing.followed(session.library.value.values, now)
            val statuses = runCatching { AiringSchedule.statuses(graph, followed, now) }.getOrDefault(emptyMap())
            val fresh = followed.filter { item -> statuses[item.id]?.let { Airing.isNew(item, it, now) } == true }
                .sortedByDescending { statuses[it.id]?.latest?.atSec ?: 0 }
            val soon = followed.filter { item -> statuses[item.id]?.let { Airing.upcoming(it, now) } == true }
                .sortedBy { statuses[it.id]?.next?.atSec ?: 0 }
            fun preview(i: com.vyrk.core.library.LibraryItem) = MetaPreview(id = i.id, type = i.type, name = i.name, poster = i.poster)
            newRow.items = fresh.map(::preview)
            newRow.labels = fresh.associate { it.id to ep(statuses.getValue(it.id).latest!!.number) }
            soonRow.items = soon.map(::preview)
            soonRow.labels = soon.associate { item ->
                val n = statuses.getValue(item.id).next!!
                item.id to "${whenLabel(n.atSec, today, tomorrow)} · ${ep(n.number)}"
            }
            newRow.loadedOnce = true; soonRow.loadedOnce = true
            // Badge the new episodes like add-on notifications do (titles page, posters elsewhere).
            val followedIds = followed.mapTo(HashSet()) { it.id }
            val newMap = fresh.associate { it.id to setOf(Airing.episodeId(it.id, statuses.getValue(it.id).latest!!.number)) }
            session.updateState { s -> s.copy(newEpisodes = s.newEpisodes.filterKeys { it !in followedIds } + newMap) }
            delay(30L * 60 * 1000)
        }
    }
    return if (enabled) listOf(newRow, soonRow).filter { it.items.isNotEmpty() } else emptyList()
}

/** "Today 18:00", "Tomorrow 09:30", "Sat 15:00", in the device's zone and locale. */
fun whenLabel(atSec: Long, today: String, tomorrow: String): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochSecond(atSec).atZone(zone)
    val now = LocalDate.now(zone)
    val day = when (t.toLocalDate()) {
        now -> today
        now.plusDays(1) -> tomorrow
        else -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
    return day + " " + t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}
