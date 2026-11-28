package com.vyrk.core.subtitles

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaResponse
import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.addon.Video
import com.vyrk.core.playback.SubtitleMode
import com.vyrk.core.playback.SubtitlePrefs
import com.vyrk.core.playback.TextTrack
import com.vyrk.core.playback.TrackSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleIdsTest {
    // Shaped like Anime Kitsu's answer: the show's imdb_id, each episode's imdbSeason and imdbEpisode.
    private val kitsuMeta: MetaItem = ProtocolJson.decodeFromString(MetaResponse.serializer(), """{"meta":{
        "id":"kitsu:12","type":"series","name":"One Piece","imdb_id":"tt0388629",
        "videos":[
          {"id":"kitsu:12:5","title":"Episode 5","season":1,"episode":5,"imdb_id":"tt0388629","imdbSeason":1,"imdbEpisode":5},
          {"id":"kitsu:12:62","title":"Episode 62","season":1,"episode":62,"imdbSeason":"2","imdbEpisode":"1"},
          {"id":"kitsu:12:900","title":"Episode 900","season":1,"episode":900}
        ]}}""").meta!!

    private fun video(id: String) = kitsuMeta.videos.first { it.id == id }

    @Test fun animeEpisodesMapToTheirImdbSeasonAndEpisode() {
        assertEquals(SubtitleIds.Target("series", "tt0388629:1:5"), SubtitleIds.imdbEquivalent("kitsu:12:5", "series", kitsuMeta, video("kitsu:12:5")))
        // A later Kitsu episode can be another IMDb season; quoted numbers are read too.
        assertEquals(SubtitleIds.Target("series", "tt0388629:2:1"), SubtitleIds.imdbEquivalent("kitsu:12:62", "anime", kitsuMeta, video("kitsu:12:62")))
    }

    @Test fun nothingIsGuessedForAnEpisodeWithoutAMapping() {
        assertNull(SubtitleIds.imdbEquivalent("kitsu:12:900", "series", kitsuMeta, video("kitsu:12:900")))
        assertNull(SubtitleIds.imdbEquivalent("kitsu:12:900", "series", kitsuMeta, null, fallbackImdb = "tt0388629"))
    }

    @Test fun imdbIdsNeedNoEquivalent() {
        assertNull(SubtitleIds.imdbEquivalent("tt0388629:1:5", "series", kitsuMeta, video("kitsu:12:5")))
        assertNull(SubtitleIds.imdbEquivalent("tt0245429", "movie", null, null, fallbackImdb = "tt0245429"))
    }

    @Test fun filmsUseTheMetaOrTheBundledId() {
        val film = MetaItem(id = "kitsu:176", type = "movie", name = "Spirited Away", imdbId = "tt0245429")
        assertEquals(SubtitleIds.Target("movie", "tt0245429"), SubtitleIds.imdbEquivalent("kitsu:176", "movie", film, null))
        assertEquals(SubtitleIds.Target("movie", "tt0245429"), SubtitleIds.imdbEquivalent("kitsu:176", "movie", null, null, fallbackImdb = "tt0245429"))
        // Not IMDb IDs: ignored.
        assertNull(SubtitleIds.imdbEquivalent("kitsu:176", "movie", film.copy(imdbId = "tmdb:129"), null, fallbackImdb = "tv:1"))
    }

    @Test fun anEpisodesOwnImdbIdWinsOverTheShows() {
        val v = Video(id = "mal:1:3", imdbId = "tt9999999", imdbSeason = 3, imdbEpisode = 4)
        assertEquals(SubtitleIds.Target("series", "tt9999999:3:4"), SubtitleIds.imdbEquivalent("mal:1:3", "series", kitsuMeta, v))
    }

    // Embedded subtitles with no language (common in anime releases).
    private val untagged = TextTrack("emb:2:0", null, null, source = "Embedded", isDefault = true)
    private val untaggedUnd = TextTrack("emb:3:0", "und", "Full Subs", source = "Embedded")
    private val signs = TextTrack("emb:4:0", null, "Signs & Songs", forced = true, source = "Embedded")
    private val english = TextTrack("os:1", "eng", "English", source = "OpenSubtitles v3")

    @Test fun untaggedEmbeddedSubtitlesShowUnderForeignAudio() {
        val prefs = SubtitlePrefs(SubtitleMode.FOREIGN_ONLY, listOf("eng"))
        assertEquals("emb:2:0", TrackSelector.selectSubtitle(listOf(signs, untaggedUnd, untagged), prefs, "jpn")?.id)
        assertEquals("emb:3:0", TrackSelector.selectSubtitle(listOf(signs, untaggedUnd), prefs, "jpn")?.id)
        assertEquals("emb:3:0", TrackSelector.selectSubtitle(listOf(untaggedUnd), SubtitlePrefs(SubtitleMode.ALWAYS, listOf("eng")), "jpn")?.id)
    }

    @Test fun aSubtitleInYourLanguageStillWins() {
        val prefs = SubtitlePrefs(SubtitleMode.FOREIGN_ONLY, listOf("eng"))
        assertEquals("os:1", TrackSelector.selectSubtitle(listOf(untagged, english), prefs, "jpn")?.id)
    }

    @Test fun untaggedSubtitlesStayOffWhenTheAudioIsYoursOrUnknown() {
        val prefs = SubtitlePrefs(SubtitleMode.FOREIGN_ONLY, listOf("eng"))
        assertNull(TrackSelector.selectSubtitle(listOf(untagged), prefs, "eng"))
        assertNull(TrackSelector.selectSubtitle(listOf(untagged), prefs, null))
        assertNull(TrackSelector.selectSubtitle(listOf(untagged), prefs, "und"))
        assertNull(TrackSelector.selectSubtitle(listOf(untagged), SubtitlePrefs(SubtitleMode.OFF, listOf("eng")), "jpn"))
        // Add-on subtitles with no language aren't taken for the release's own.
        assertNull(TrackSelector.selectSubtitle(listOf(untagged.copy(id = "x", source = "Some add-on")), prefs, "jpn"))
    }
}
