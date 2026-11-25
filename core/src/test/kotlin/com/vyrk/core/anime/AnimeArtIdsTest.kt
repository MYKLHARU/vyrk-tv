package com.vyrk.core.anime

import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.art.Artwork
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeArtIdsTest {
    private val ids = AnimeArtIds.parse("""
        # header
        1 1 tt0213338
        - 36701 tt1092564
        50 69 tv:34676
        60 - movie:555
        70 71 nonsense
        x 80 tt1234567
        1 2 tt9999999
    """.trimIndent())

    @Test fun kitsuAndMalIdsFindTheirArtworkId() {
        assertEquals("tt0213338", ids.ref("kitsu:1"))
        assertEquals("tt0213338", ids.ref("mal:1"))
        assertEquals("tt0213338", ids.ref("kitsu:1:5"), "an episode ID")
        assertEquals("tt1092564", ids.ref("mal:36701"))
        assertEquals("tt1234567", ids.ref("mal:80"), "a bad Kitsu column leaves the MAL one")
        assertEquals("tt0213338", ids.ref("kitsu:1"), "the first line for an ID wins")
        assertNull(ids.ref("kitsu:70"), "malformed IDs are skipped")
        assertNull(ids.ref("kitsu:36701")); assertNull(ids.ref("tt0213338")); assertNull(ids.ref("anilist:1")); assertNull(ids.ref("kitsu:"))
    }

    @Test fun tmdbIdsOnlyForTheirOwnKind() {
        assertEquals("tt0213338", ids.lookupId("kitsu:1", "series"))
        assertEquals("tt0213338", ids.lookupId("kitsu:1", "movie"), "IMDb IDs fit either")
        assertEquals("tmdb:34676", ids.lookupId("kitsu:50", "series"))
        assertNull(ids.lookupId("kitsu:50", "movie"), "a show's TMDB ID is not a film's")
        assertEquals("tmdb:555", ids.lookupId("kitsu:60", "movie"))
        assertNull(ids.lookupId("kitsu:60", "series"))
        assertNull(AnimeArtIds.EMPTY.lookupId("kitsu:1", "series"))
    }

    @Test fun theLookupIdGivesCinemetasLogoWithoutChangingTheTitlesId() {
        val meta = MetaPreview(id = "kitsu:1", type = "series", name = "Cowboy Bebop")
        assertNull(Artwork.lookupId(meta))
        val lookup = Artwork.lookupId(meta) ?: ids.lookupId(meta.id, meta.type)
        assertEquals(listOf("https://images.metahub.space/logo/medium/tt0213338/img"), Artwork.logos(meta, null, null, lookup))
        assertEquals(listOf("https://images.metahub.space/background/medium/tt0213338/img"), Artwork.backdrops(meta, null, null, "w1280", lookup))
        assertEquals(emptyList(), Artwork.logos(meta, null, null), "without it, nothing")
        assertEquals(emptyList(), Artwork.logos(meta, null, null, "tmdb:34676"), "Cinemeta's images need an IMDb ID; TMDB and fanart.tv take the TMDB one")
    }

    @Test fun bundledListCoversWellKnownAnime() {
        val file = listOf(File("../app/src/main/assets/anime-art-ids.txt"), File("app/src/main/assets/anime-art-ids.txt")).first { it.exists() }
        val bundled = AnimeArtIds.parse(file.readText())
        assertTrue(bundled.size > 10_000, "${bundled.size} IDs")
        assertEquals("tt0213338", bundled.lookupId("kitsu:1", "series"), "Cowboy Bebop")
        assertEquals("tt0388629", bundled.lookupId("mal:21", "series"), "ONE PIECE")
        assertEquals("tt30217403", bundled.lookupId("kitsu:49425", "series"), "DAN DA DAN Season 2: the show's ID")
        assertTrue(file.readText().contains("Open Database License"), "the ODbL notice stays with the data")
    }
}
