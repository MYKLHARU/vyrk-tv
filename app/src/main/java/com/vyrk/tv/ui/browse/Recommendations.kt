package com.vyrk.tv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.library.LibraryItem
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
 * "Because you watched …" rows: TMDB's recommendations for the titles you watched most recently,
 * opened through your add-ons by their IMDb ids. Anime is left out (these rows are on Home).
 * Needs the build's TMDB key; without one there are no rows.
 */
object Recommendations {
    /** How many recent titles get a row. */
    const val SEEDS = 2
    private const val PER_ROW = 20

    private val bySeed = ConcurrentHashMap<String, List<MetaPreview>>()
    private val imdbIds = ConcurrentHashMap<String, String>()

    /** The most recently watched films and shows with IMDb ids, newest first. */
    fun seeds(library: Collection<LibraryItem>): List<LibraryItem> = library
        .filter { it.id.startsWith("tt") && (it.type == "movie" || it.type == "series") && (it.state.lastWatchedMs ?: 0L) > 0L && !it.removed }
        .sortedByDescending { it.state.lastWatchedMs ?: 0L }
        .distinctBy { it.id }
        .take(SEEDS)

    /** Recommendations for [seed], without titles in [exclude]. Cached for the session. */
    suspend fun forSeed(graph: AppGraph, seed: LibraryItem, exclude: Set<String>): List<MetaPreview> {
        val cached = bySeed[seed.id] ?: run {
            val tmdb = graph.tmdb
            if (!tmdb.enabled) return emptyList()
            val found = tmdb.findByImdb(seed.id.substringBefore(':'), preferTv = seed.type == "series") ?: return emptyList()
            val recs = tmdb.recommendations(found.first, found.second).filterNot { it.looksAnime }.take(PER_ROW)
            val limit = Semaphore(5)
            val items = coroutineScope {
                recs.map { r ->
                    async {
                        limit.withPermit {
                            val key = r.type + ":" + r.tmdbId
                            val imdb = imdbIds[key] ?: tmdb.imdbId(r.tmdbId, r.type)?.also { imdbIds[key] = it }
                            imdb?.let {
                                MetaPreview(id = it, type = r.type, name = r.title, poster = TmdbClient.image(r.posterPath, "w342"),
                                    background = TmdbClient.image(r.backdropPath, "w1280"), releaseInfo = r.year, description = r.overview)
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.id }
            }
            if (items.isNotEmpty()) bySeed[seed.id] = items
            items
        }
        return cached.filter { it.id !in exclude && it.id != seed.id }
    }
}

/**
 * The Home rows for [Recommendations], in order; a row shows once its titles have arrived.
 * [onLoaded] gets every recommended title, for the launcher's VYRK row.
 */
@Composable
fun rememberRecommendationRows(session: ProfileSession, enabled: Boolean, onLoaded: (List<MetaPreview>) -> Unit = {}): List<RowState> {
    val graph = LocalGraph.current
    val library by session.library.collectAsState()
    val seeds = remember(library) { Recommendations.seeds(library.values) }
    val seedIds = seeds.map { it.id }
    val rows = remember(seedIds) { seeds.map { RowState("rec:${it.id}", RowKind.CATALOG, null, "") } }
    rows.zip(seeds).forEach { (row, seed) -> row.title = str(R.string.because_you_watched, seed.name) }
    LaunchedEffect(seedIds, enabled) {
        if (!enabled) return@LaunchedEffect
        // Titles already watched, or already in your library, aren't worth suggesting.
        val exclude = library.values.filter { it.watched || it.inLibrary }.mapTo(HashSet()) { it.id }
        val all = ArrayList<MetaPreview>()
        rows.zip(seeds).forEach { (row, seed) ->
            row.loading = true
            val items = runCatching { Recommendations.forSeed(graph, seed, exclude) }.getOrDefault(emptyList())
            row.items = items.filter { m -> all.none { it.id == m.id } }
            row.loading = false
            row.loadedOnce = true
            all += row.items
        }
        if (all.isNotEmpty()) onLoaded(all)
    }
    return if (enabled) rows.filter { it.items.isNotEmpty() } else emptyList()
}
