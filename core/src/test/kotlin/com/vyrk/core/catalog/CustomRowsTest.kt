package com.vyrk.core.catalog

import com.vyrk.core.addon.*
import kotlin.test.*

class CustomRowsTest {
    private val kitsu = Descriptor(transportUrl = "https://kitsu.test/manifest.json", manifest = Manifest("kitsu", name = "Kitsu", idPrefixes = listOf("kitsu:"), catalogs = listOf(
        ManifestCatalog("anime", "kitsu-anime-popular", "Kitsu Most Popular", extra = listOf(ExtraProp("genre", false, listOf("Action", "Romance")), ExtraProp("skip"))),
        ManifestCatalog("anime", "kitsu-anime-list", extra = listOf(ExtraProp("search", true))),
    )))
    private val cinemeta = Descriptor(transportUrl = "https://cinemeta.test/manifest.json", manifest = Manifest("cinemeta", name = "Cinemeta", catalogs = listOf(
        ManifestCatalog("movie", "top", "Popular", extra = listOf(ExtraProp("genre", false, listOf("Drama", "Comedy")))),
        ManifestCatalog("series", "byGenre", "By genre", extra = listOf(ExtraProp("genre", true, listOf("Crime")))),
        ManifestCatalog("tv", "channels"),
    )))
    private val addons = listOf(kitsu, cinemeta)

    @Test fun onlyBrowsableCataloguesAreOffered() {
        assertEquals(listOf("kitsu-anime-popular", "top", "byGenre"), CustomRows.catalogs(addons).map { it.catalog.id })
    }

    @Test fun genresTabsAndAll() {
        val (pop, top, byGenre) = CustomRows.catalogs(addons)
        assertEquals(listOf("Action", "Romance"), CustomRows.genres(pop))
        assertEquals(listOf("anime"), CustomRows.tabs(pop))
        assertEquals(listOf("home", "movies"), CustomRows.tabs(top))
        assertFalse(CustomRows.allowsAll(byGenre))
        assertTrue(CustomRows.allowsAll(top))
    }

    @Test fun rowsResolveByAddonIdAndKeepTheirOrder() {
        val pop = CustomRows.catalogs(addons)[0]
        val a = CustomRows.of("a", pop, "Romance", "anime")
        val b = CustomRows.of("b", pop, "Isekai", "anime")
        assertNull(b.genre, "a genre the add-on doesn't list is dropped")
        assertEquals("Romance · Kitsu Most Popular", CustomRows.title(a, pop))
        val reinstalled = kitsu.copy(transportUrl = "https://kitsu.test/new-config/manifest.json")
        assertEquals(listOf("a", "b"), CustomRows.forTab(listOf(a, b), "anime", listOf(reinstalled)).map { it.first.id })
        assertTrue(CustomRows.forTab(listOf(a), "anime", listOf(cinemeta)).isEmpty())
        assertEquals(listOf("b", "a"), CustomRows.move(listOf(a, b), "b", -1).map { it.id })
        assertEquals(listOf("a", "b"), CustomRows.move(listOf(a, b), "a", -1).map { it.id })
    }
}
