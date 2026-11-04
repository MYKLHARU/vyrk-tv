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
}
