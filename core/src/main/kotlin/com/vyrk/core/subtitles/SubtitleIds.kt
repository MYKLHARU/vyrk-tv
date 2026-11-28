package com.vyrk.core.subtitles

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.Video

/**
 * Which IMDb ID to ask subtitle add-ons for when an episode has an ID of its own. Most subtitle
 * add-ons, OpenSubtitles among them, know only IMDb IDs ("tt…"), while anime from Anime Kitsu
 * and other anime add-ons uses theirs ("kitsu:…", "mal:…"), so they were never asked.
 *
 * The equivalent comes from the title's meta: Anime Kitsu gives the show's imdb_id and, for each
 * episode, imdbSeason and imdbEpisode (a Kitsu entry is often one season of a longer IMDb series,
 * numbered from 1). Nothing is guessed: an episode without that mapping has no equivalent.
 */
object SubtitleIds {
    /** An ID and the type to ask with ("series" for an episode "tt…:season:episode", "movie" for a film). */
    data class Target(val type: String, val id: String)

    private val IMDB = Regex("tt\\d+")

    /**
     * The IMDb equivalent of [videoId], or null when it already is an IMDb ID or nothing maps it.
     * [fallbackImdb] is an IMDb ID for the title from elsewhere (VYRK's bundled anime IDs); it is
     * used only for films, which need no episode mapping.
     */
    fun imdbEquivalent(videoId: String, type: String, meta: MetaItem?, video: Video?, fallbackImdb: String? = null): Target? {
        if (videoId.startsWith("tt")) return null
        fun clean(id: String?) = id?.trim()?.substringBefore(':')?.takeIf { IMDB.matches(it) }
        val imdb = clean(video?.imdbId) ?: clean(meta?.imdbId)
        val season = video?.imdbSeason
        val episode = video?.imdbEpisode
        if (imdb != null && season != null && episode != null && season >= 0 && episode > 0) return Target("series", "$imdb:$season:$episode")
        if (type == "movie") return (imdb ?: clean(fallbackImdb))?.let { Target("movie", it) }
        return null
    }
}
