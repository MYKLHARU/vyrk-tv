package com.vyrk.tv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.tmdb.TmdbClient
import com.vyrk.tv.R
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.str
import java.time.LocalDate
import java.time.MonthDay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Home's Christmas Movies row, from 1 November to 6 January: well-known films TMDB tags
 * "christmas", most popular first (no anime), opened through your add-ons by IMDb id.
 */
object Seasonal {
    const val CHRISTMAS = "season:christmas"
    private const val FRESH_MS = 12L * 3600 * 1000
    @Volatile private var cached: Pair<Long, List<MetaPreview>>? = null

    fun isChristmas(today: LocalDate = LocalDate.now()): Boolean {
        val d = MonthDay.from(today)
        return d >= MonthDay.of(11, 1) || d <= MonthDay.of(1, 6)
    }

    suspend fun christmas(graph: AppGraph): List<MetaPreview> {
        cached?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.let { return it.second }
        val tmdb = graph.tmdb
        if (!tmdb.enabled) return emptyList()
        val films = tmdb.moviesWithKeyword("christmas").filterNot { it.looksAnime }.take(30)
        val limit = Semaphore(4)
        val items = coroutineScope {
            films.map { t ->
                async {
                    limit.withPermit { tmdb.imdbId(t.tmdbId, t.type) }?.let {
                        MetaPreview(id = it, type = "movie", name = t.title, poster = TmdbClient.image(t.posterPath, "w342"),
                            background = TmdbClient.image(t.backdropPath, "w1280"), releaseInfo = t.year, description = t.overview)
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }.take(24)
        }
        if (items.isNotEmpty()) cached = System.currentTimeMillis() to items
        return items
    }
}

@Composable
fun rememberSeasonalRows(session: ProfileSession, enabled: Boolean): List<RowState> {
    val graph = LocalGraph.current
    val addons by session.addons.collectAsState()
    val settings by session.settings.collectAsState()
    val row = remember { RowState(Seasonal.CHRISTMAS, RowKind.CATALOG, null, "") }
    row.title = str(R.string.christmas_movies)
    val list = remember(addons) { session.effectiveAddons() }
    val on = enabled && Seasonal.isChristmas() && Top10.canOpen(list, "movie")
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        row.loading = true
        row.items = runCatching { Seasonal.christmas(graph) }.getOrDefault(emptyList())
        row.loading = false
        row.loadedOnce = true
    }
    return if (on && row.items.isNotEmpty() && row.key !in settings.hiddenRows) listOf(row) else emptyList()
}
