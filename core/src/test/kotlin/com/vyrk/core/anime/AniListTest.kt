package com.vyrk.core.anime

import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answer shapes follow AniList's GraphQL API (https://docs.anilist.co); the titles are real, trimmed. */
class AniListTest {
    private class Fake(var reply: (String) -> HttpResult) : HttpTransport {
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = error("AniList is POST only")
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = error("headers are required")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
            assertEquals(AniList.ENDPOINT, url)
            bodies += body; this.headers += headers
            return reply(body)
        }
    }

    private val answer = """{"data":{
        "trending":{"media":[
          {"id":185660,"idMal":60543,"format":"TV","seasonYear":2025,"genres":["Action","Comedy"],
           "title":{"romaji":"Dandadan 2nd Season","english":"DAN DA DAN Season 2"},
           "coverImage":{"extraLarge":"https://s4.anilist.co/x/large.jpg","large":"https://s4.anilist.co/x/medium.jpg"},
           "description":"Momo and Okarun <i>return</i>.<br><br>\n(Source: Crunchyroll &amp; others)"},
          {"id":999999999,"idMal":null,"format":null,"seasonYear":null,"genres":[],
           "title":{"romaji":"Announced Title","english":null},"coverImage":{"extraLarge":null,"large":"https://s4.anilist.co/y.jpg"},"description":null}
        ]},
        "season":{"media":[]},
        "popular":{"media":[
          {"id":21,"idMal":21,"format":"TV","seasonYear":1999,"genres":["Action","Adventure"],
           "title":{"romaji":"ONE PIECE","english":"ONE PIECE"},"coverImage":{"extraLarge":"https://s4.anilist.co/op.jpg"},"description":"Gold Roger…"},
          {"id":21,"idMal":21,"format":"TV","seasonYear":1999,"genres":[],"title":{"romaji":"dupe"},"coverImage":{},"description":null}
        ]},
        "top":{"media":[
          {"id":199,"idMal":199,"format":"MOVIE","seasonYear":2001,"genres":["Adventure","Fantasy"],
           "title":{"romaji":"Sen to Chihiro no Kamikakushi","english":"Spirited Away"},"coverImage":{"extraLarge":"https://s4.anilist.co/sa.jpg"},"description":"A girl…"}
        ]}
    }}"""

    @Test fun seasonsFollowTheAnimeCalendar() {
        assertEquals("WINTER" to 2027, AniList.season(LocalDate.of(2027, 1, 4)))
        assertEquals("SPRING" to 2026, AniList.season(LocalDate.of(2026, 6, 30)))
        assertEquals("SUMMER" to 2026, AniList.season(LocalDate.of(2026, 7, 1)))
        assertEquals("FALL" to 2026, AniList.season(LocalDate.of(2026, 11, 25)))
        assertEquals("FALL" to 2026, AniList.season(LocalDate.of(2026, 12, 31)))
    }

    @Test fun oneRequestAsksForFourSafeRows() {
        val q = AniList.QUERY
        AniList.Section.entries.forEach { assertTrue("${it.key}: Page(perPage: \$n)" in q, it.key) }
        assertEquals(4, Regex("isAdult: false").findAll(q).count(), "adult titles are never asked for")
        assertTrue("season: \$season, seasonYear: \$year, sort: POPULARITY_DESC" in q)
        assertTrue("sort: TRENDING_DESC" in q && "sort: SCORE_DESC" in q)
        assertEquals(q.count { it == '{' }, q.count { it == '}' }, "balanced braces")
    }

    @Test fun variablesCarryOnlyTheChosenFilter() {
        val day = LocalDate.of(2026, 11, 25)
        val all = AniList.requestBody(null, day)
        assertTrue("\"variables\":{\"n\":30,\"season\":\"FALL\",\"year\":2026}" in all, all)
        assertTrue("\"genre\"" !in all && "\"tag\"" !in all, "no filter variables at all, not nulls")
        assertTrue("\"genre\":[\"Sci-Fi\"]" in AniList.requestBody(AniList.Filter("Sci-Fi"), day))
        val tag = AniList.requestBody(AniList.Filter("Isekai", tag = true), day)
        assertTrue("\"tag\":[\"Isekai\"]" in tag && "\"genre\"" !in tag)
    }

    @Test fun filtersAreAniListGenresWithoutAdultOnesThenTags() {
        val names = AniList.filters.map { it.name }
        assertFalse("Hentai" in names || "Ecchi" in names)
        assertEquals(17, AniList.filters.count { !it.tag })
        assertEquals(listOf("Isekai", "Shounen", "Seinen", "Shoujo"), AniList.filters.filter { it.tag }.map { it.name })
        assertEquals(names.distinct(), names)
    }

    @Test fun parsesRowsTitlesAndPlainDescriptions() {
        val rows = AniList.parse(answer)!!
        val trending = rows.getValue(AniList.Section.TRENDING)
        assertEquals(listOf(185660, 999999999), trending.map { it.id })
        val dan = trending[0]
        assertEquals("DAN DA DAN Season 2", dan.title, "English title first")
        assertEquals(60543, dan.malId)
        assertEquals("https://s4.anilist.co/x/large.jpg", dan.poster)
        assertEquals("Momo and Okarun return.\n\n(Source: Crunchyroll & others)", dan.description)
        val announced = trending[1]
        assertEquals("Announced Title", announced.title, "romaji when there is no English title")
        assertNull(announced.malId); assertNull(announced.format); assertNull(announced.year); assertNull(announced.description)
        assertEquals("https://s4.anilist.co/y.jpg", announced.poster)
        assertEquals(emptyList(), rows.getValue(AniList.Section.SEASON))
        assertEquals(1, rows.getValue(AniList.Section.POPULAR).size, "duplicates dropped")
        assertEquals("MOVIE", rows.getValue(AniList.Section.TOP).single().format)
    }

    @Test fun errorsAreNotRows() {
        val bad = """{"errors":[{"message":"Validation error of type FieldUndefined","status":400}],"data":null}"""
        assertNull(AniList.parse(bad))
        assertEquals("Validation error of type FieldUndefined", AniList.errorMessage(bad))
        assertNull(AniList.parse("<html>Bad gateway</html>"))
        assertNull(AniList.errorMessage("<html>Bad gateway</html>"))
    }

    @Test fun previewsOpenByKitsuIdAndOnlyWhatAnAddonOpens() {
        val ids = AniListKitsu.parse("# header\n21 12\n185660 49425\n199 176\n")
        val rows = AniList.parse(answer)!!
        val all = AniList.Section.entries.flatMap { rows.getValue(it) }
        val shown = AniList.previews(all, ids) { _, _ -> true }
        assertEquals(listOf("kitsu:49425", "kitsu:12", "kitsu:176"), shown.map { it.id }, "the unmapped title is left out")
        val dan = shown[0]
        assertEquals("series", dan.type); assertEquals("DAN DA DAN Season 2", dan.name); assertEquals("2025", dan.releaseInfo)
        assertEquals(listOf("Action", "Comedy"), dan.genres)
        assertEquals("movie", shown[2].type, "AniList films open as films")
        val seriesOnly = AniList.previews(all, ids) { type, _ -> type == "series" }
        assertEquals(listOf("kitsu:49425", "kitsu:12"), seriesOnly.map { it.id })
    }

    @Test fun newTitlesAreLookedUpByAniListThenMalId() {
        val ids = AniListKitsu.parse("21 12\n")
        val rows = AniList.parse(answer)!!
        val missing = AniList.unmapped(rows, ids, limit = 10)
        assertEquals(listOf(185660, 999999999, 199), missing.map { it.id })
        assertEquals(listOf("anilist:185660", "mal:60543"), AniList.lookupIds(missing[0]))
        assertEquals(listOf("anilist:999999999"), AniList.lookupIds(missing[1]))
        ids.learn(185660, 49425); ids.learn(999999999, null)
        assertEquals(49425, ids.kitsu(185660))
        assertTrue(ids.isKnownMiss(999999999))
        assertEquals(listOf(199), AniList.unmapped(rows, ids, limit = 10).map { it.id }, "found and missed titles aren't asked again")
        assertEquals(1, AniList.unmapped(rows, AniListKitsu.EMPTY, limit = 1).size)
    }

    @Test fun idMapParsesSkipsJunkAndKeepsOneIdPerTitle() {
        val ids = AniListKitsu.parse("# c\n\n5 2\n1 1\nx y\n7\n-3 4\n5 9\n")
        assertEquals(2, ids.size)
        assertEquals(1, ids.kitsu(1)); assertEquals(2, ids.kitsu(5)); assertNull(ids.kitsu(7))
        ids.learn(5, 99)
        assertEquals(2, ids.kitsu(5), "the snapshot wins over a run-time answer")
        assertEquals(1376, AniListKitsu.fromId("kitsu:1376"))
        assertEquals(1376, AniListKitsu.fromId("kitsu:1376:4"))
        assertNull(AniListKitsu.fromId("tt0944947")); assertNull(AniListKitsu.fromId("kitsu:x")); assertNull(AniListKitsu.fromId(null))
    }

    @Test fun bundledSnapshotMapsWellKnownTitles() {
        val file = listOf(File("../app/src/main/assets/anilist-kitsu.txt"), File("app/src/main/assets/anilist-kitsu.txt")).first { it.exists() }
        val ids = AniListKitsu.parse(file.readText())
        assertTrue(ids.size > 15_000, "${ids.size} titles")
        assertEquals(1, ids.kitsu(1), "Cowboy Bebop")
        assertEquals(12, ids.kitsu(21), "ONE PIECE")
        assertEquals(49425, ids.kitsu(185660), "DAN DA DAN Season 2")
        assertTrue(file.readLines().first().startsWith("# AniList ID -> Kitsu ID"))
        assertTrue(file.readText().contains("Open Database License"), "the ODbL notice stays with the data")
    }

    @Test fun clientCachesPerFilterAndReportsFailures() = runTest {
        var t = 0L
        val http = Fake { HttpResult(200, answer) }
        val client = AniListClient(http) { t }
        val day = LocalDate.of(2026, 11, 25)
        assertIs<AniListClient.Result.Ok>(client.rows(null, day))
        assertIs<AniListClient.Result.Ok>(client.rows(null, day))
        assertEquals(1, http.bodies.size, "cached")
        assertEquals("application/json", http.headers[0]["Accept"])
        client.rows(AniList.Filter("Romance"), day)
        assertEquals(2, http.bodies.size, "each filter has its own rows")
        t += AniListClient.FRESH_MS + 1
        http.reply = { HttpResult(429, """{"errors":[{"message":"Too Many Requests.","status":429}],"data":null}""") }
        val limited = client.rows(null, day)
        assertIs<AniListClient.Result.Failed>(limited)
        assertEquals(429, limited.code); assertEquals("Too Many Requests.", limited.reason)
        http.reply = { throw java.io.IOException("Unable to resolve host") }
        val offline = client.rows(null, day)
        assertIs<AniListClient.Result.Failed>(offline)
        assertEquals(0, offline.code)
        http.reply = { HttpResult(200, """{"errors":[{"message":"Syntax Error"}]}""") }
        assertIs<AniListClient.Result.Failed>(client.rows(null, day), "200 without data is a failure")
    }
}
