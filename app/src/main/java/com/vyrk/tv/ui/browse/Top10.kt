package com.vyrk.tv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.tmdb.TmdbClient
import com.vyrk.tv.R
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.str
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Home's two Top 10 rows: this week's most popular films and shows on TMDB (no anime), opened
 * through your add-ons by IMDb id. A row shows only when an installed add-on can open IMDb titles
 * of its type, and only with the build's TMDB key. Kept for six hours.
 */
object Top10 {
    const val MOVIES = "top10:movie"
    const val SHOWS = "top10:series"
    private const val FRESH_MS = 6L * 3600 * 1000
    private val cache = ConcurrentHashMap<String, Pair<Long, List<MetaPreview>>>()
    private val imdbIds = ConcurrentHashMap<String, String>()

    /** Whether an add-on can show IMDb titles of [type] ("movie" or "series"). */
    fun canOpen(addons: List<Descriptor>, type: String) = addons.any { it.manifest.supports("meta", type, "tt0000001") }

    suspend fun titles(graph: AppGraph, type: String): List<MetaPreview> {
        cache[type]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.let { return it.second }
        val tmdb = graph.tmdb
        if (!tmdb.enabled) return emptyList()
        val trending = tmdb.trending(if (type == "series") "tv" else "movie").filterNot { it.looksAnime }.take(15)
        val limit = Semaphore(4)
        val items = coroutineScope {
            trending.map { t ->
                async {
                    val key = t.type + ":" + t.tmdbId
                    val imdb = imdbIds[key] ?: limit.withPermit { tmdb.imdbId(t.tmdbId, t.type) }?.also { imdbIds[key] = it }
                    imdb?.let {
                        MetaPreview(id = it, type = t.type, name = t.title, poster = TmdbClient.image(t.posterPath, "w342"),
                            background = TmdbClient.image(t.backdropPath, "w1280"), releaseInfo = t.year, description = t.overview)
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }.take(10)
        }
        if (items.isNotEmpty()) cache[type] = System.currentTimeMillis() to items
        return items
    }
}

@Composable
fun rememberTop10Rows(session: ProfileSession, enabled: Boolean): List<RowState> {
    val graph = LocalGraph.current
    val addons by session.addons.collectAsState()
    val settings by session.settings.collectAsState()
    val movies = remember { RowState(Top10.MOVIES, RowKind.TOP10, null, "") }
    val shows = remember { RowState(Top10.SHOWS, RowKind.TOP10, null, "") }
    movies.title = str(R.string.top10_movies)
    shows.title = str(R.string.top10_shows)
    val list = remember(addons) { session.effectiveAddons() }
    val openMovies = Top10.canOpen(list, "movie")
    val openShows = Top10.canOpen(list, "series")
    LaunchedEffect(enabled, openMovies, openShows) {
        if (!enabled) return@LaunchedEffect
        for ((row, type, on) in listOf(Triple(movies, "movie", openMovies), Triple(shows, "series", openShows))) {
            if (!on) continue
            row.loading = true
            row.items = runCatching { Top10.titles(graph, type) }.getOrDefault(emptyList())
            row.loading = false
            row.loadedOnce = true
        }
    }
    // Hide row on one of them hides it like any other row (Settings can bring it back).
    return if (enabled) listOfNotNull(movies.takeIf { openMovies }, shows.takeIf { openShows })
        .filter { it.items.isNotEmpty() && it.key !in settings.hiddenRows } else emptyList()
}
