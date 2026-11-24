package com.vyrk.core.debrid

import com.vyrk.core.addon.Stream
import com.vyrk.core.addon.StreamBehaviorHints
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers by method and URL prefix, and records every call. */
private class FakeHttp(private val routes: List<Pair<String, (String) -> HttpResult>>) : HttpTransport {
    val calls = mutableListOf<String>()
    private fun answer(key: String): HttpResult {
        calls += key
        return routes.firstOrNull { key.startsWith(it.first) }?.second?.invoke(key) ?: HttpResult(404, "{}")
    }
    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = answer("GET $url")
    override suspend fun postJson(url: String, body: String, timeoutMs: Long) = answer("POST $url")
    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeoutMs: Long) =
        answer("POST $url ${form.entries.joinToString("&") { "${it.key}=${it.value}" }}")
    override suspend fun delete(url: String, headers: Map<String, String>, timeoutMs: Long) = answer("DELETE $url")
}

private fun ok(body: String) = { _: String -> HttpResult(200, body) }

class DebridTest {
    private val pack = listOf(
        DebridFile(1, "/Show.S01/Show.S01E01.1080p.mkv", 2_000_000_000),
        DebridFile(2, "/Show.S01/Show.S01E02.1080p.mkv", 2_100_000_000),
        DebridFile(3, "/Show.S01/Sample/Show.S01E02.sample.mkv", 30_000_000),
        DebridFile(4, "/Show.S01/Show.S01.nfo", 2_000),
    )
    private val rdIndex: (DebridFile) -> Int? = { it.id - 1 }

    @Test
    fun picksTheFileTheAddonNamedThenItsIndexThenTheEpisode() {
        assertEquals(2, DebridFiles.pick(pack, FileHint(filename = "Show.S01E02.1080p.mkv"), rdIndex)?.id)
        assertEquals(1, DebridFiles.pick(pack, FileHint(fileIdx = 0), rdIndex)?.id)
        assertEquals(2, DebridFiles.pick(pack, FileHint(season = 1, episode = 2), rdIndex)?.id)
        // An index that points at another episode loses to the episode in the name.
        assertEquals(2, DebridFiles.pick(pack, FileHint(fileIdx = 0, season = 1, episode = 2), rdIndex)?.id)
        // No such episode in a pack: nothing, rather than the wrong one.
        assertNull(DebridFiles.pick(pack, FileHint(season = 1, episode = 9), rdIndex))
        // A film: the largest video that isn't a sample.
        val film = listOf(DebridFile(1, "/Film/Film.2160p.mkv", 50_000_000_000), DebridFile(2, "/Film/Sample/sample.mkv", 90_000_000), DebridFile(3, "/Film/Film.srt", 90_000))
        assertEquals(1, DebridFiles.pick(film, FileHint(), rdIndex)?.id)
        assertNull(DebridFiles.pick(listOf(DebridFile(1, "/x/readme.txt", 1)), FileHint(), rdIndex))
    }

    @Test
    fun episodeNumbersInNames() {
        assertEquals(1 to 2, DebridFiles.episodeOf("Show.S01E02.mkv"))
        assertEquals(10 to 103, DebridFiles.episodeOf("show s10 e103 1080p.mkv"))
        assertEquals(3 to 7, DebridFiles.episodeOf("Show 3x07 WEB.mkv"))
        assertNull(DebridFiles.episodeOf("Film.2019.1080p.x264.mkv"))
    }

    @Test
    fun hintsFromTheStreamAndVideo() {
        val s = Stream(infoHash = "abc", fileIdx = 3, behaviorHints = StreamBehaviorHints(filename = "a.mkv"))
        assertEquals(FileHint("a.mkv", 3, 2, 5), FileHint.of(s, "tt0903747:2:5"))
        assertEquals(FileHint("a.mkv", 3, null, null), FileHint.of(s, "tt0111161"))
        assertEquals(FileHint("a.mkv", 3, null, null), FileHint.of(s, "kitsu:1:4"))
    }

    @Test
    fun magnetLinks() {
        assertEquals("magnet:?xt=urn:btih:abcdef&dn=My+Film&tr=udp%3A%2F%2Ftracker.example%3A80",
            Magnet.of("ABCDEF", listOf("tracker:udp://tracker.example:80", "dht:abcdef"), "My Film"))
    }

    private val rdInfoWaiting = """{"id":"T1","status":"waiting_files_selection","files":[
        {"id":1,"path":"/Show.S01/Show.S01E01.mkv","bytes":100,"selected":0},
        {"id":2,"path":"/Show.S01/Show.S01E02.mkv","bytes":200,"selected":0},
        {"id":3,"path":"/Show.S01/info.nfo","bytes":1,"selected":0}],"links":[]}"""
    private val rdInfoReady = """{"id":"T1","status":"downloaded","files":[
        {"id":1,"path":"/Show.S01/Show.S01E01.mkv","bytes":100,"selected":1},
        {"id":2,"path":"/Show.S01/Show.S01E02.mkv","bytes":200,"selected":1},
        {"id":3,"path":"/Show.S01/info.nfo","bytes":1,"selected":0}],"links":["https://rd/l1","https://rd/l2"]}"""

    @Test
    fun realDebridAddsSelectsVideosAndUnrestrictsTheEpisode() = runBlocking {
        var infos = 0
        val http = FakeHttp(listOf(
            "GET ${RealDebridClient.API}/torrents?" to ok("[]"),
            "POST ${RealDebridClient.API}/torrents/addMagnet" to ok("""{"id":"T1"}"""),
            "GET ${RealDebridClient.API}/torrents/info/T1" to { _ -> HttpResult(200, if (infos++ == 0) rdInfoWaiting else rdInfoReady) },
            "POST ${RealDebridClient.API}/torrents/selectFiles/T1" to { _ -> HttpResult(204, "") },
            "POST ${RealDebridClient.API}/unrestrict/link link=https://rd/l2" to ok("""{"download":"https://dl.rd/E02.mkv","filename":"Show.S01E02.mkv","filesize":200}"""),
        ))
        val r = RealDebridClient(http) {}.resolve("tok", "ABC", Magnet.of("abc"), FileHint(season = 1, episode = 2))
        assertEquals(DebridResult.Ok("https://dl.rd/E02.mkv", "Show.S01E02.mkv", 200), r)
        assertTrue(http.calls.any { it == "POST ${RealDebridClient.API}/torrents/selectFiles/T1 files=1,2" })
        assertTrue(http.calls.none { it.startsWith("DELETE") })
    }

    @Test
    fun realDebridDeletesWhatItAddedWhenItIsNotReady() = runBlocking {
        val downloading = rdInfoReady.replace("downloaded", "downloading")
        var infos = 0
        val http = FakeHttp(listOf(
            "GET ${RealDebridClient.API}/torrents?" to ok("[]"),
            "POST ${RealDebridClient.API}/torrents/addMagnet" to ok("""{"id":"T1"}"""),
            "GET ${RealDebridClient.API}/torrents/info/T1" to { _ -> HttpResult(200, if (infos++ == 0) rdInfoWaiting else downloading) },
            "POST ${RealDebridClient.API}/torrents/selectFiles/T1" to { _ -> HttpResult(204, "") },
            "DELETE ${RealDebridClient.API}/torrents/delete/T1" to { _ -> HttpResult(204, "") },
        ))
        assertEquals(DebridResult.NotCached, RealDebridClient(http) {}.resolve("tok", "abc", Magnet.of("abc"), FileHint(season = 1, episode = 2)))
        assertTrue(http.calls.contains("DELETE ${RealDebridClient.API}/torrents/delete/T1"))
    }

    @Test
    fun realDebridReusesAReadyTorrentAndNeverDeletesIt() = runBlocking {
        val http = FakeHttp(listOf(
            "GET ${RealDebridClient.API}/torrents?" to ok("""[{"id":"T9","hash":"abc","status":"downloaded"}]"""),
            "GET ${RealDebridClient.API}/torrents/info/T9" to ok(rdInfoReady),
            "POST ${RealDebridClient.API}/unrestrict/link link=https://rd/l1" to ok("""{"download":"https://dl.rd/E01.mkv"}"""),
        ))
        val r = RealDebridClient(http) {}.resolve("tok", "ABC", Magnet.of("abc"), FileHint(fileIdx = 0))
        assertEquals("https://dl.rd/E01.mkv", (r as DebridResult.Ok).url)
        assertTrue(http.calls.none { it.contains("addMagnet") || it.startsWith("DELETE") })
    }

    @Test
    fun realDebridErrors() {
        assertEquals(DebridResult.SignedOut, RealDebridClient.errorOf(HttpResult(401, """{"error":"bad_token","error_code":8}""")))
        assertEquals(DebridResult.Removed, RealDebridClient.errorOf(HttpResult(451, """{"error":"infringing_file","error_code":35}""")))
        assertEquals(DebridResult.NotPremium, RealDebridClient.errorOf(HttpResult(403, """{"error_code":20}""")))
        assertNull(RealDebridClient.errorOf(HttpResult(201, "{}")))
    }

    @Test
    fun realDebridDeviceSignIn() = runBlocking {
        val code = RealDebridClient.parseDeviceCode("""{"device_code":"DC","user_code":"ABCD1234","interval":5,"expires_in":1800,
            "verification_url":"https://real-debrid.com/device","direct_verification_url":"https://real-debrid.com/authorize?x=1"}""")!!
        assertEquals("https://real-debrid.com/authorize?x=1", code.qrUrl)
        val pending = FakeHttp(listOf("GET ${RealDebridClient.OAUTH}/device/credentials" to { _ -> HttpResult(403, """{"error":"authorization_pending"}""") }))
        assertEquals(DevicePoll.Pending, RealDebridClient(pending) {}.poll(code, 0))
        val approved = FakeHttp(listOf(
            "GET ${RealDebridClient.OAUTH}/device/credentials" to ok("""{"client_id":"CID","client_secret":"SEC"}"""),
            "POST ${RealDebridClient.OAUTH}/token" to ok("""{"access_token":"AT","refresh_token":"RT","expires_in":3600}"""),
        ))
        val poll = RealDebridClient(approved) {}.poll(code, 1_000)
        assertEquals(DebridLogin(DebridService.REAL_DEBRID, "AT", "RT", "CID", "SEC", 1_000 + 3_600_000), (poll as DevicePoll.Approved).login)
        assertTrue(approved.calls.any { it.contains("code=DC") && it.contains("grant_type=http://oauth.net/grant_type/device/1.0") })
    }

    @Test
    fun torBoxSkipsWhatIsNotCached() = runBlocking {
        val http = FakeHttp(listOf("GET ${TorBoxClient.API}/torrents/checkcached" to ok("""{"success":true,"data":[]}""")))
        assertEquals(DebridResult.NotCached, TorBoxClient(http) {}.resolve("key", "abc", Magnet.of("abc"), FileHint()))
        assertTrue(http.calls.none { it.contains("createtorrent") })
    }

    @Test
    fun torBoxAddsACachedTorrentAndLinksTheFile() = runBlocking {
        val http = FakeHttp(listOf(
            "GET ${TorBoxClient.API}/torrents/checkcached" to ok("""{"success":true,"data":[{"hash":"abc","name":"Film"}]}"""),
            "POST ${TorBoxClient.API}/torrents/createtorrent" to ok("""{"success":true,"data":{"torrent_id":77,"hash":"abc"}}"""),
            "GET ${TorBoxClient.API}/torrents/mylist?id=77" to ok("""{"success":true,"data":{"id":77,"download_finished":true,"download_present":true,
                "files":[{"id":0,"name":"Film/Film.2160p.mkv","size":5000},{"id":1,"name":"Film/sample.mkv","size":10}]}}"""),
            "GET ${TorBoxClient.API}/torrents/requestdl" to ok("""{"success":true,"data":"https://tb.cdn/film.mkv"}"""),
        ))
        val r = TorBoxClient(http) {}.resolve("key", "ABC", Magnet.of("abc"), FileHint())
        assertEquals(DebridResult.Ok("https://tb.cdn/film.mkv", "Film.2160p.mkv", 5000), r)
        assertTrue(http.calls.any { it.contains("torrent_id=77&file_id=0") })
    }

    @Test
    fun torBoxCacheCheckAndErrors() = runBlocking {
        val http = FakeHttp(listOf("GET ${TorBoxClient.API}/torrents/checkcached" to ok("""{"success":true,"data":[{"hash":"AAA"},{"hash":"ccc"}]}""")))
        assertEquals(setOf("aaa", "ccc"), TorBoxClient(http) {}.cached("key", listOf("aaa", "bbb", "ccc")))
        assertNull(TorBoxClient(FakeHttp(emptyList())) {}.cached("key", listOf("aaa")))
        assertEquals(DebridResult.SignedOut, TorBoxClient.errorOf(HttpResult(403, """{"success":false,"error":"BAD_TOKEN"}""")))
        assertEquals(DebridResult.Failed("ACTIVE_LIMIT"), TorBoxClient.errorOf(HttpResult(200, """{"success":false,"error":"ACTIVE_LIMIT"}""")))
    }
}
