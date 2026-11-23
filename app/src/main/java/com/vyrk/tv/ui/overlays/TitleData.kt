package com.vyrk.tv.ui.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryItemState
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.stream.Ranked
import com.vyrk.core.stream.StreamRanker
import com.vyrk.core.tmdb.Credits
import com.vyrk.tv.R
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.str

// What the TV and phone title pages and cards load and show the same way.

/** A film's sources on its title page. */
sealed interface SourcesState {
    data object Idle : SourcesState
    data object Loading : SourcesState
    data class Ready(val ranked: List<Ranked>, val failedAddons: Int, val addonCount: Int) : SourcesState
}

/** What a source list says in place of sources: not loaded, loading, or why there are none. Null when there are sources. */
@Composable
fun sourcesMessage(state: SourcesState): String? {
    val ready = state as? SourcesState.Ready
    return when {
        state is SourcesState.Idle -> str(R.string.press_sources)
        state is SourcesState.Loading -> str(R.string.finding_sources)
        ready != null && ready.addonCount == 0 -> str(R.string.no_stream_addons)
        ready != null && ready.ranked.isEmpty() && ready.failedAddons > 0 -> str(R.string.sources_addons_failed)
        ready != null && ready.ranked.isEmpty() -> str(R.string.no_sources_found)
        else -> null
    }
}

/** Asks every stream add-on for the title's default video and ranks what comes back. */
suspend fun loadTitleSources(graph: AppGraph, session: ProfileSession, meta: MetaPreview, runtimeMinutes: Int?): SourcesState.Ready {
    val videoId = meta.behaviorHints.defaultVideoId ?: meta.id
    val results = graph.addonClient.allStreams(session.effectiveAddons(), meta.type, videoId)
    val ranked = StreamRanker.rank(StreamRanker.candidates(results), graph.playbackCaps(), session.rankPrefs(meta.id, runtimeMinutes))
    return SourcesState.Ready(ranked, failedAddons = results.count { it.second is AddonResult.Failed }, addonCount = results.size)
}

/** Cast and crew with photos from TMDB, when the build has a TMDB key and the title has an IMDb ID. */
@Composable
fun rememberTmdbCredits(graph: AppGraph, meta: MetaPreview): Credits? {
    val credits by produceState<Credits?>(null, meta.id) {
        if (!graph.tmdb.enabled || !meta.id.startsWith("tt")) return@produceState
        val (tmdbId, mediaType) = graph.tmdb.findByImdb(meta.id.substringBefore(':'), preferTv = meta.type == "series") ?: return@produceState
        value = graph.tmdb.credits(tmdbId, mediaType)
    }
    return credits
}

/** "42m left" or "1h 5m left" for a started title; null when nothing is left to show. */
@Composable
fun timeLeftLabel(state: LibraryItemState?): String? {
    val m = LibraryRules.minutesLeft(state) ?: return null
    return str(R.string.time_left, if (m < 60) str(R.string.time_m_short, m) else str(R.string.time_hm_short, m / 60, m % 60))
}

/** "S5 E4 · 28m left", "28m left" or "S1 E3"; null when the library knows neither. */
@Composable
fun continueLabel(item: LibraryItem?): String? {
    val st = item?.state ?: return null
    return listOfNotNull(LibraryRules.episodeLabel(item.id, st.videoId), timeLeftLabel(st)).joinToString(" · ").ifEmpty { null }
}
