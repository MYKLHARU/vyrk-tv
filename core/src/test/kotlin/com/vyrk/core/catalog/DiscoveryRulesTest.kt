package com.vyrk.core.catalog

import com.vyrk.core.addon.*
import kotlin.test.*

class DiscoveryRulesTest {
    private val catalogs = listOf(
        ManifestCatalog("movie", "popular", "Popular"),
        ManifestCatalog("series", "shows", "Shows"),
        ManifestCatalog("anime", "kitsu/all", "Anime"),
        ManifestCatalog("movie", "genre", "By genre", extra = listOf(ExtraProp("genre", true, listOf("Action", "Drama")))),
        ManifestCatalog("movie", "search", extraRequired = listOf("search")),
        ManifestCatalog("movie", "invalid", extraRequired = listOf("genre")),
        ManifestCatalog("tv", "channels"))
    private val addon = Descriptor(transportUrl = "https://addon.test/manifest.json", manifest = Manifest("mixed", name = "Mixed", catalogs = catalogs))

    @Test fun typeAndRequiredInputsSelectOnlyUsableCatalogs() {
        assertEquals(listOf("popular", "kitsu/all", "genre"), DiscoveryRules.catalogs(listOf(addon), "movie", false).map { it.catalog.id })
        assertEquals(listOf("shows", "kitsu/all"), DiscoveryRules.catalogs(listOf(addon), "series", false).map { it.catalog.id })
    }
    @Test fun animeModeRejectsRegularCatalogsInMixedProvider() {
        assertEquals(listOf("kitsu/all"), DiscoveryRules.catalogs(listOf(addon), "movie", true).map { it.catalog.id })
    }
    @Test fun hiddenCatalogsAndProviderOrderAreRespected() {
        val second = addon.copy(manifest = addon.manifest.copy(id = "second"))
        val result = DiscoveryRules.catalogs(listOf(addon, second), "series", false, setOf("mixed/series/shows"))
        assertEquals(listOf("mixed", "second", "second"), result.map { it.addon.manifest.id })
    }
    @Test fun requiredGenreHasValidDefaultAndStaleValuesReset() {
        val ref = CatalogRef(addon, catalogs[3], 0)
        assertEquals("Action", DiscoveryRules.genre(ref, false, null))
        assertEquals("Drama", DiscoveryRules.genre(ref, false, "Drama"))
        assertEquals("Action", DiscoveryRules.genre(ref, true, "Isekai"))
    }
    @Test fun optionalGenreAllowsAllAndAnimeUsesAllSixteenFilters() {
        val ref = CatalogRef(addon, catalogs[2], 0)
        assertNull(DiscoveryRules.genre(ref, true, null))
        assertEquals(16, DiscoveryRules.genres(ref, true).size)
        assertEquals("Isekai", DiscoveryRules.genre(ref, true, "Isekai"))
        assertNull(DiscoveryRules.genre(ref, false, "Isekai"))
    }
}
