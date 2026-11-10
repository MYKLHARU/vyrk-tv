package com.vyrk.tv.browse

import com.vyrk.core.addon.*
import com.vyrk.core.net.*
import com.vyrk.tv.data.Tab
import com.vyrk.tv.ui.browse.*
import kotlinx.coroutines.*
import kotlin.test.*

class AnimeBrowseModelTest {
    private fun addon() = Descriptor(transportUrl = "https://addon.test/manifest.json", manifest = Manifest("mixed", name = "Mixed",
        catalogs = listOf(
            ManifestCatalog("movie", "anime/films", "Anime films", extraSupported = listOf("skip")),
            ManifestCatalog("series", "anime/shows", "Anime shows", extraSupported = listOf("skip")),
            ManifestCatalog("series", "regular", "Regular shows"))))

    @Test fun modePartitionsTabsAndCachesWithoutContaminatingRegularView() = runBlocking {
        val client = AddonClient(http { """{"metas":[]}""" })
        val store = RowStore()
        fun model(tab: Tab, anime: Boolean) = BrowseModel(client, tab, this, store, anime)
            .also { it.rebuild(listOf(addon()), emptySet()) }
        val regular = model(Tab.HOME, false)
        val home = model(Tab.HOME, true)
        assertEquals(3, regular.rows.size)
        assertEquals(2, home.rows.size)
        assertEquals(listOf("movie"), model(Tab.MOVIES, true).rows.map { it.ref!!.catalog.type })
        assertEquals(listOf("series"), model(Tab.SERIES, true).rows.map { it.ref!!.catalog.type })
        assertNotSame(regular.rows.first(), home.rows.first())
        assertEquals(regular.rows, model(Tab.HOME, false).rows)
    }

    @Test fun paginationAdvancesByRawCountWhenGenreRemovesEveryItem() = runBlocking {
        val urls = mutableListOf<String>()
        val client = AddonClient(http { url ->
            urls += url
            if ("skip=2" in url) """{"metas":[{"id":"mal:3","type":"series","genres":["Action"]}]}"""
            else """{"metas":[{"id":"mal:1","type":"series","genres":["Drama"]},{"id":"mal:2","type":"series","genres":["Drama"]}]}"""
        })
        val model = BrowseModel(client, Tab.SERIES, this, animeOnly = true, genre = "Action")
        model.rebuild(listOf(addon()), emptySet())
        val row = model.rows.single()
        model.ensureLoaded(row); row.job!!.join()
        assertTrue(row.items.isEmpty())
        assertTrue(row.hasMore)
        model.loadMore(row); row.job!!.join()
        assertEquals("mal:3", row.items.single().id)
        assertTrue(urls.last().contains("skip=2"))
    }

    @Test fun requiredGenreAndGenericAnimeCataloguesUseProviderSpellingAndActualItemType() = runBlocking {
        val urls = mutableListOf<String>()
        val client = AddonClient(http { url ->
            urls += url
            """{"metas":[{"id":"mal:1","type":"movie"},{"id":"mal:2","type":"series"}]}"""
        })
        val d = addon().copy(manifest = addon().manifest.copy(catalogs = listOf(
            ManifestCatalog("anime", "all", "Anime", extra = listOf(
                ExtraProp("genre", isRequired = true, options = listOf("Science Fiction")))))))
        val all = BrowseModel(client, Tab.MOVIES, this, animeOnly = true)
        all.rebuild(listOf(d), emptySet())
        assertTrue(all.rows.isEmpty())
        val filtered = BrowseModel(client, Tab.MOVIES, this, animeOnly = true, genre = "Sci-Fi")
        filtered.rebuild(listOf(d), emptySet())
        val row = filtered.rows.single()
        filtered.ensureLoaded(row); row.job!!.join()
        assertTrue(urls.single().contains("genre=Science%20Fiction"))
        assertEquals("movie", row.items.single().type)
    }

    @Test fun providerIgnoringSkipCannotLoopForeverOnFilteredPages() = runBlocking {
        val client = AddonClient(http { """{"metas":[{"id":"mal:1","type":"series","genres":["Drama"]}]}""" })
        val model = BrowseModel(client, Tab.SERIES, this, animeOnly = true, genre = "Action")
        model.rebuild(listOf(addon()), emptySet())
        val row = model.rows.single()
        model.ensureLoaded(row); row.job!!.join()
        model.loadMore(row); row.job!!.join()
        assertTrue(row.items.isEmpty())
        assertFalse(row.hasMore)
    }

    private fun http(reply: (String) -> String) = object : HttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = HttpResult(200, reply(url))
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(200, "{}")
    }
}
