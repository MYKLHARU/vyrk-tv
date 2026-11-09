package com.vyrk.core.art

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import kotlin.test.*

class TitleArtworkTest {
    private val preview = MetaPreview("shared-id", "movie", "Selected title", background = "https://synthetic.invalid/selected.jpg", logo = "https://synthetic.invalid/selected-logo.png")

    @Test fun richerMetadataAddsFallbacksWithoutReplacingTheImageTheViewerSelected() {
        val full = MetaItem(preview.id, preview.type, background = "https://synthetic.invalid/alternate.jpg", logo = "https://synthetic.invalid/alternate-logo.png")
        val before = Artwork.backdrops(preview, null, null, "w1280")
        val after = Artwork.backdrops(preview, full, "https://synthetic.invalid/tmdb.jpg", "w1280")
        assertEquals(before.first(), after.first())
        assertEquals(listOf(preview.background, full.background, "https://synthetic.invalid/tmdb.jpg"), after)
        assertEquals(preview.logo, Artwork.logos(preview, full, null).first())
    }

    @Test fun sameIdInDifferentCatalogTypesCannotReuseMetadataOrLogoKeys() {
        val series = MetaItem(preview.id, "series", background = "https://synthetic.invalid/wrong-series.jpg", logo = "https://synthetic.invalid/wrong-logo.png")
        assertNull(Artwork.matchingMeta(preview, series))
        assertEquals(listOf(preview.background), Artwork.backdrops(preview, series, null, "w1280"))
        assertEquals(listOf(preview.logo), Artwork.logos(preview, series, null))
        assertNotEquals(Artwork.titleKey("movie", preview.id), Artwork.titleKey("series", preview.id))
    }

    @Test fun previousTitlesMetadataIsRejectedAndMissingCatalogueArtStillGetsFallbacks() {
        val old = MetaItem("other-title", preview.type, background = "https://synthetic.invalid/old.jpg")
        assertNull(Artwork.matchingMeta(preview, old))
        val bare = preview.copy(background = null, logo = null)
        val full = MetaItem(preview.id, preview.type, background = "https://synthetic.invalid/full.jpg", logo = "https://synthetic.invalid/full-logo.png")
        assertEquals(listOf(full.background), Artwork.backdrops(bare, full, null, "w1280"))
        assertEquals(listOf(full.logo), Artwork.logos(bare, full, null))
    }

    @Test fun animeImdbMappingsSurviveParsingAndEnableFallbacksWithoutRewritingStreamIds() {
        val json = com.vyrk.core.addon.ProtocolJson
        val anime = json.decodeFromString(com.vyrk.core.addon.MetaPreview.serializer(),
            """{"id":"kitsu:7442","type":"series","name":"Anime title","imdb_id":"tt2560140"}""")
        assertEquals("tt2560140", Artwork.lookupId(anime))
        assertEquals(listOf("https://images.metahub.space/logo/medium/tt2560140/img"), Artwork.logos(anime, null, null))
        assertEquals("kitsu:7442", anime.id)
        val full = json.decodeFromString(com.vyrk.core.addon.MetaItem.serializer(),
            """{"id":"kitsu:7442","type":"series","imdbId":"tt2560140"}""")
        assertEquals("tt2560140", full.toPreview().imdbId)
        assertEquals("tt2560140", Artwork.lookupId(anime.copy(imdbId = null), full))
        assertTrue(Artwork.backdrops(anime, null, null, "w1280").last().contains("tt2560140"))
    }

    @Test fun artworkMappingsAreValidatedAndCannotLeakFromAnotherAnime() {
        val anime = preview.copy(id = "mal:123", imdbId = "not-an-imdb-id")
        assertNull(Artwork.lookupId(anime))
        assertNull(Artwork.lookupId(anime, MetaItem("mal:456", "movie", imdbId = "tt1234567")))
        assertEquals("tmdb:123", Artwork.lookupId(preview.copy(id = "tmdb:123")))
        assertEquals("tt1234567", Artwork.lookupId(preview.copy(id = "tt1234567", imdbId = "tt7654321")))
    }
}
