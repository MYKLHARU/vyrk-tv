package com.vyrk.core.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkTest {
    @Test
    fun metahubFallbacksOnlyForImdbIds() {
        assertEquals("https://images.metahub.space/poster/medium/tt0944947/img", Artwork.metahubPoster("tt0944947"))
        assertEquals("https://images.metahub.space/logo/medium/tt0944947/img", Artwork.metahubLogo("tt0944947:1:2"))
        assertEquals("https://images.metahub.space/background/medium/tt0944947/img", Artwork.metahubBackground("tt0944947"))
        assertNull(Artwork.metahubPoster("kitsu:12"))
        assertNull(Artwork.metahubPoster("tmdb:1399"))
        assertNull(Artwork.metahubPoster("ttabc"))
    }

    @Test
    fun largerCopiesOfKnownCdnImages() {
        assertEquals("https://image.tmdb.org/t/p/original/logo.png", Artwork.forLogo("https://image.tmdb.org/t/p/w300/logo.png"))
        assertEquals("https://image.tmdb.org/t/p/w1280/bd.jpg", Artwork.forBackdrop("https://image.tmdb.org/t/p/w780/bd.jpg", "w1280"))
        assertEquals("https://image.tmdb.org/t/p/original/bd.jpg", Artwork.forBackdrop("https://image.tmdb.org/t/p/w1280/bd.jpg", "original"))
        assertEquals("https://images.metahub.space/background/medium/tt1/img", Artwork.forBackdrop("https://images.metahub.space/background/small/tt1/img", "w1280"))
        // Already large, or unknown hosts (including add-on proxies): unchanged.
        assertEquals("https://images.metahub.space/logo/large/tt1/img", Artwork.forLogo("https://images.metahub.space/logo/large/tt1/img"))
        assertEquals("https://example.org/art/w300/x.png", Artwork.forLogo("https://example.org/art/w300/x.png"))
        assertNull(Artwork.forLogo(" "))
        assertNull(Artwork.forBackdrop(null, "w1280"))
    }

    // A catalogue-only Kitsu shelf with AIO Metadata as the only metadata provider: the answer to
    // `kitsu:…` comes back as `tt…`. It was discarded, leaving the title with no logo or episodes.
    @Test fun anAnswerUnderTheProvidersCanonicalIdIsStillThisTitlesMetadata() {
        val preview = com.vyrk.core.addon.MetaPreview(id = "kitsu:11469", type = "series", name = "My Hero Academia")
        val answer = com.vyrk.core.addon.MetaItem(id = "tt5626028", type = "series", name = "My Hero Academia", logo = "https://example.test/logo.png",
            videos = listOf(com.vyrk.core.addon.Video(id = "tt5626028:1:1", title = "Izuku Midoriya: Origin", season = 1, episode = 1)))
        assertNull(Artwork.matchingMeta(preview, answer), "The strict check still rejects another identity")
        val full = Artwork.answerFor(preview, answer)!!
        assertEquals("kitsu:11469", full.id, "Library and watched state stay under the catalogue's ID")
        assertEquals("series", full.type)
        assertEquals("tt5626028", full.imdbId, "The canonical IMDb ID is kept for artwork")
        assertEquals(listOf("tt5626028:1:1"), full.videos.map { it.id }, "Episode IDs are the provider's")
        assertEquals(full, Artwork.matchingMeta(preview, full))
        assertEquals("tt5626028", Artwork.lookupId(preview, full))
        assertEquals("https://example.test/logo.png", Artwork.logos(preview, full, null).first())
    }

    @Test fun anExactAnswerIsReturnedUnchangedAndNoAnswerStaysNone() {
        val preview = com.vyrk.core.addon.MetaPreview(id = "kitsu:1", type = "series", name = "A")
        val exact = com.vyrk.core.addon.MetaItem(id = "kitsu:1", type = "series", name = "A")
        assertEquals(true, Artwork.answerFor(preview, exact) === exact)
        assertNull(Artwork.answerFor(preview, null))
    }
}
