package com.vyrk.core.catalog

import com.vyrk.core.addon.*
import kotlin.test.*

class AnimeBrowseTest {
    private fun ref(id: String = "anime/popular", name: String = "Popular", options: List<String> = emptyList()) = CatalogRef(
        Descriptor(transportUrl = "https://addon.test/manifest.json", manifest = Manifest("mixed", name = "Mixed provider",
            catalogs = emptyList())), ManifestCatalog("series", id, name, extra = listOf(ExtraProp("genre", options = options))), 0)

    @Test fun mixedProviderRecognizesOnlyAnimeCatalogues() {
        assertTrue(CatalogRules.isAnimeCatalog(ref()))
        assertTrue(CatalogRules.isAnimeCatalog(ref("mal.upcoming")))
        assertFalse(CatalogRules.isAnimeCatalog(ref("popular")))
        assertFalse(CatalogRules.isAnimeCatalog(ref("animal-documentaries")))
    }

    @Test fun supportsAllRequestedGenresAndProviderSpellings() {
        assertEquals(16, AnimeBrowse.genres.size)
        assertEquals("Science Fiction", AnimeBrowse.providerGenre(ref(options = listOf("Science Fiction")), "Sci-Fi"))
        assertEquals("Shounen", AnimeBrowse.providerGenre(ref(options = listOf("Shounen")), "Shonen"))
        assertEquals("Shoujo", AnimeBrowse.providerGenre(ref(options = listOf("Shoujo")), "Shojo"))
        assertNull(AnimeBrowse.providerGenre(ref(options = listOf("Action")), "Isekai"))
    }

    @Test fun movieAndSeriesTypesStaySeparate() {
        val film = MetaPreview("mal:1", "movie", genres = listOf("Action"))
        assertTrue(AnimeBrowse.matches(film, "movie", "Action"))
        assertFalse(AnimeBrowse.matches(film, "series", "Action"))
        assertFalse(AnimeBrowse.matches(film, "movie", "Sports"))
        assertTrue(AnimeBrowse.matches(MetaPreview("kitsu:2", "anime"), "series"))
    }

    @Test fun animeOnlyContinueWatchingRejectsUnidentifiedRegularTitles() {
        assertFalse(AnimeBrowse.matches(MetaPreview("tt123", "series")))
        assertTrue(AnimeBrowse.matches(MetaPreview("tt123", "series", genres = listOf("Anime", "Shounen")), genre = "Shonen"))
        assertTrue(AnimeBrowse.matches(MetaPreview("tt123", "series"), trustedCatalog = true))
    }

    @Test fun genreFilterRequiresEvidenceUnlessProviderAlreadyFiltered() {
        val item = MetaPreview("mal:1", "series")
        assertFalse(AnimeBrowse.matches(item, genre = "Comedy"))
        assertTrue(AnimeBrowse.matches(item, genre = "Comedy", serverFiltered = true))
        assertTrue(AnimeBrowse.matchesGenre(listOf("Slice-of-Life"), "Slice of Life"))
        assertFalse(AnimeBrowse.matchesGenre(listOf("Supernatural"), "Thriller"))
        assertTrue(AnimeBrowse.matches(MetaPreview("mal:2", "series", genre = listOf("Action")), genre = "Action"))
        assertTrue(AnimeBrowse.matches(MetaPreview("mal:3", "series", links = listOf(Link("Shounen", "Genres"))), genre = "Shonen"))
    }
}
