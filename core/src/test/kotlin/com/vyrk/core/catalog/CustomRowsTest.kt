package com.vyrk.core.catalog

import com.vyrk.core.addon.*
import kotlin.test.*

class CustomRowsTest {
    private val kitsu = Descriptor(transportUrl = "https://kitsu.test/manifest.json", manifest = Manifest("kitsu", name = "Kitsu", idPrefixes = listOf("kitsu:"), catalogs = listOf(
        ManifestCatalog("anime", "kitsu-anime-popular", "Kitsu Most Popular", extra = listOf(ExtraProp("genre", false, listOf("Romance", "Cooking", "action", "science-fiction")), ExtraProp("skip"))),
        ManifestCatalog("anime", "kitsu-anime-list", extra = listOf(ExtraProp("search", true))),
    )))
    private val cinemeta = Descriptor(transportUrl = "https://cinemeta.test/manifest.json", manifest = Manifest("cinemeta", name = "Cinemeta", catalogs = listOf(
        ManifestCatalog("movie", "top", "Popular", extra = listOf(ExtraProp("genre", false, listOf("Drama", "Comedy")))),
        ManifestCatalog("series", "byGenre", "By genre", extra = listOf(ExtraProp("genre", true, listOf("Crime")))),
        ManifestCatalog("tv", "channels"),
    )))
    private val addons = listOf(kitsu, cinemeta)

    @Test fun onlyBrowsableCataloguesAreOffered() {
        // byGenre requires a genre and offers none of the allowed ones.
        assertEquals(listOf("kitsu-anime-popular", "top"), CustomRows.catalogs(addons).map { it.catalog.id })
    }

    @Test fun genresTabsAndAll() {
        val (pop, top) = CustomRows.catalogs(addons)
        val byGenre = CatalogRef(cinemeta, cinemeta.manifest.catalogs[1], 1)
        // Only the allowed genres, in their order, in the add-on's spelling.
        assertEquals(listOf("action", "Romance", "science-fiction"), CustomRows.genres(pop))
        assertEquals(listOf("Comedy", "Drama"), CustomRows.genres(top))
        assertEquals("Science Fiction", CustomRows.label("science-fiction"))
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
        // Tick boxes: ticking adds at the end, ticking again removes.
        val ticked = CustomRows.toggle(listOf(a), pop, "action", "anime", "c")
        assertEquals(listOf("a", "c"), ticked.map { it.id })
        assertTrue(CustomRows.has(ticked, pop, "action", "anime"))
        assertEquals(listOf("c"), CustomRows.toggle(ticked, pop, "Romance", "anime", "x").map { it.id })
    }
}
