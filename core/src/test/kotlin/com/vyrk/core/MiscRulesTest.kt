package com.vyrk.core

import com.vyrk.core.account.ApiError
import com.vyrk.core.account.StremioAccountApi
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.Manifest
import com.vyrk.core.addon.ManifestCatalog
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.CatalogRef
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.lang.Languages
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import com.vyrk.core.playback.AudioPrefs
import com.vyrk.core.playback.AudioTrack
import com.vyrk.core.playback.BandwidthMeter
import com.vyrk.core.playback.ErrorClass
import com.vyrk.core.playback.ErrorClassifier
import com.vyrk.core.playback.RecoveryAction
import com.vyrk.core.playback.RecoveryPolicy
import com.vyrk.core.playback.StallDetector
import com.vyrk.core.playback.SubtitleMode
import com.vyrk.core.playback.SubtitlePrefs
import com.vyrk.core.playback.TextTrack
import com.vyrk.core.playback.TrackSelector
import com.vyrk.core.profile.PinHasher
import com.vyrk.core.profile.PinLockout
import com.vyrk.core.search.SearchRanking
import com.vyrk.core.subtitles.SubtitleParser
import com.vyrk.core.update.GithubAsset
import com.vyrk.core.update.GithubRelease
import com.vyrk.core.update.Updates
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryTest {
    @Test
    fun brokenTopLinkIsRefreshedThenFallsOver() {
        val p = RecoveryPolicy(candidateCount = 3, userPicked = false)
        assertEquals(RecoveryAction.RefreshLink, p.onFailure(ErrorClassifier.classify(2004, 404), canRefresh = true))
        assertEquals(RecoveryAction.NextCandidate, p.onFailure(ErrorClass.HTTP_NOT_FOUND, canRefresh = true))
        assertEquals(1, p.candidateIndex)
    }

    @Test
    fun networkErrorsRetryTwiceThenAdvanceUpToThreeCandidates() {
        val p = RecoveryPolicy(candidateCount = 5, userPicked = false)
        repeat(2) { assertEquals(RecoveryAction.Retry, p.onFailure(ErrorClass.NETWORK, false)) }
        assertEquals(RecoveryAction.NextCandidate, p.onFailure(ErrorClass.NETWORK, false))
        repeat(2) { p.onFailure(ErrorClass.NETWORK, false) }
        assertEquals(RecoveryAction.NextCandidate, p.onFailure(ErrorClass.NETWORK, false))
        repeat(2) { p.onFailure(ErrorClass.NETWORK, false) }
        assertEquals(RecoveryAction.GiveUp, p.onFailure(ErrorClass.NETWORK, false))
    }

    @Test
    fun noFirstFrameMovesStraightToTheNextSource() {
        val p = RecoveryPolicy(candidateCount = 3, userPicked = false)
        assertEquals(RecoveryAction.NextCandidate, p.onFirstFrameTimeout())
        assertEquals(1, p.candidateIndex)
        assertEquals(RecoveryAction.NextCandidate, p.onFailure(ErrorClass.DECODER, false))
        assertEquals(RecoveryAction.GiveUp, p.onFirstFrameTimeout())
    }

    @Test
    fun handPickedSourceAsksInsteadOfSwitching() {
        val p = RecoveryPolicy(candidateCount = 3, userPicked = true)
        assertEquals(RecoveryAction.AskUser, p.onFailure(ErrorClass.DECODER, false))
        val q = RecoveryPolicy(candidateCount = 3, userPicked = true)
        assertEquals(RecoveryAction.LinkExpired, q.onFailure(ErrorClass.HTTP_EXPIRED, canRefresh = false))
    }

    @Test
    fun audioAndLiveFailures() {
        val p = RecoveryPolicy(3, false)
        assertEquals(RecoveryAction.EnableFfmpegAudio, p.onFailure(ErrorClassifier.classify(5001), false))
        assertEquals(RecoveryAction.TryOtherAudioTrack, p.onFailure(ErrorClass.AUDIO_TRACK, false))
        assertEquals(RecoveryAction.SeekToLiveEdge, p.onFailure(ErrorClassifier.classify(1002), false))
    }

    @Test
    fun classification() {
        assertEquals(ErrorClass.HTTP_EXPIRED, ErrorClassifier.classify(2004, 403))
        assertEquals(ErrorClass.HTTP_SERVER, ErrorClassifier.classify(2004, 503))
        assertEquals(ErrorClass.NETWORK, ErrorClassifier.classify(2001))
        assertEquals(ErrorClass.CONTAINER, ErrorClassifier.classify(3001))
        assertEquals(ErrorClass.DECODER, ErrorClassifier.classify(4003))
    }

    @Test
    fun stallPromptAfterThreeStallsInSixtySecondsIgnoringStartupAndSeeks() {
        val s = StallDetector()
        assertFalse(s.onBuffering(0)) // start-up
        s.onFirstFrame(1_000)
        s.onSeek(10_000)
        assertFalse(s.onBuffering(11_000)) // right after a seek
        assertFalse(s.onBuffering(20_000))
        assertFalse(s.onBuffering(30_000))
        assertTrue(s.onBuffering(40_000))
        assertFalse(s.onBuffering(41_000)) // prompts once
        val t = StallDetector(); t.onFirstFrame(0)
        t.onBuffering(10_000); t.onBuffering(50_000)
        assertFalse(t.onBuffering(75_000)) // first stall left the window
    }

    @Test
    fun bandwidthIgnoresSmallSamples() {
        val b = BandwidthMeter()
        assertNull(b.onSample(100_000, 100))
        assertEquals(8_388_608.0, b.onSample(1_048_576, 1_000))
        val next = b.onSample(1_048_576, 500)!!
        assertTrue(next > 8_388_608.0 && next < 16_777_216.0)
    }
}

class TracksAndSubtitlesTest {
    @Test
    fun audioPrefersLanguageAndAvoidsCommentary() {
        val tracks = listOf(
            AudioTrack("1", "en", "English", 6, isDefault = true),
            AudioTrack("2", "it", "Italian - Director's Commentary", 2),
            AudioTrack("3", "ita", "Italiano", 6),
        )
        assertEquals("3", TrackSelector.selectAudio(tracks, AudioPrefs(languages = listOf("ita")))!!.id)
        assertEquals("1", TrackSelector.selectAudio(tracks, AudioPrefs(languages = listOf("fre")))!!.id)
        assertEquals("1", TrackSelector.selectAudio(tracks, AudioPrefs(languages = listOf("ita"), originalLanguage = true, originalLanguageCode = "en"))!!.id)
    }

    @Test
    fun subtitleModes() {
        val subs = listOf(TextTrack("e", "eng", "English"), TextTrack("f", "eng", "English forced", forced = true), TextTrack("s", "spa", "Spanish", source = "OpenSubtitles v3"))
        assertEquals("e", TrackSelector.selectSubtitle(subs, SubtitlePrefs(SubtitleMode.ALWAYS, listOf("eng")), "eng")!!.id)
        assertEquals("f", TrackSelector.selectSubtitle(subs, SubtitlePrefs(SubtitleMode.FOREIGN_ONLY, listOf("eng")), "eng")!!.id)
        assertEquals("e", TrackSelector.selectSubtitle(subs, SubtitlePrefs(SubtitleMode.FOREIGN_ONLY, listOf("eng")), "jpn")!!.id)
        assertNull(TrackSelector.selectSubtitle(subs, SubtitlePrefs(SubtitleMode.OFF, listOf("eng")), "jpn"))
        assertNull(TrackSelector.selectSubtitle(subs, SubtitlePrefs(SubtitleMode.ALWAYS, listOf("eng"), rememberedOff = true), "jpn"))
        assertEquals("s", TrackSelector.selectSubtitle(subs, SubtitlePrefs(SubtitleMode.ALWAYS, listOf("spa", "eng")), "eng")!!.id)
        assertEquals(listOf("s", "e", "f"), TrackSelector.sortForDisplay(subs, listOf("spa")).map { it.id })
    }

    @Test
    fun parsesSrtAndStripsMarkupIncludingBraces() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n{\\an8}<i>Hello</i> {there}\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nLine one\r\n<b>Line two</b>\r\n\r\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals("Hello", cues[0].text)
        assertEquals(1_000, cues[0].startMs); assertEquals(2_500, cues[0].endMs)
        assertEquals("Line one\nLine two", cues[1].text)
        // A stray closing brace must not crash.
        assertEquals("a } b", SubtitleParser.stripMarkup("a } b"))
    }

    @Test
    fun parsesVtt() {
        val vtt = "WEBVTT\n\nNOTE x\n\n00:01.000 --> 00:02.000 align:start\n<c.yellow>Hi</c> <00:01.500>there\n\n01:00:00.000 --> 01:00:01.000\nLate"
        val cues = SubtitleParser.parse(vtt)
        assertTrue(SubtitleParser.isVtt(vtt))
        assertEquals("Hi there", cues[0].text)
        assertEquals(3_600_000, cues[1].startMs)
    }

    @Test
    fun languagesShowNamesWithFlagsAsSupplement() {
        assertEquals("eng", Languages.normalise("en"))
        assertEquals("por", Languages.normalise("pt-BR"))
        assertEquals("English 🇬🇧", Languages.label("eng"))
        assertEquals("XYZ", Languages.label("xyz"))
        assertEquals(listOf("eng", "ita"), Languages.detect("Multi ENG ITA 1080p"))
        assertEquals(emptyList(), Languages.detect("Fin del mundo"))
    }
}

class CatalogAndSearchTest {
    private fun ref(name: String, type: String = "movie", id: String = name.lowercase()) =
        CatalogRef(Descriptor(Manifest(id = "a", name = "A"), "https://a/manifest.json"), ManifestCatalog(type, id, name), 0)

    @Test
    fun topTenServicesFromCatalogueNames() {
        assertEquals("Netflix", CatalogRules.streamingService("Netflix"))
        assertEquals("Max", CatalogRules.streamingService("HBO Max"))
        assertEquals("Disney+", CatalogRules.streamingService("Disney+"))
        assertEquals("Prime Video", CatalogRules.streamingService("Prime Video"))
        assertEquals("Apple TV+", CatalogRules.streamingService("Apple TV+"))
        assertNull(CatalogRules.streamingService("Popular"))
        assertNull(CatalogRules.streamingService("Maximum Overdrive Picks"))
        assertEquals("Top 10 on Netflix · Series", CatalogRules.top10Title(ref("Netflix", "series")))
        assertEquals("Popular - Movies", CatalogRules.rowTitle(ref("Popular")))
    }

    @Test
    fun homeRowsPutServiceRowsFirstAndSkipRequiredExtras() {
        val m = Manifest(id = "x", name = "X", catalogs = listOf(
            ManifestCatalog("movie", "top", "Popular"),
            ManifestCatalog("movie", "nfx", "Netflix"),
            ManifestCatalog("series", "last", "Last", extraRequired = listOf("lastVideosIds")),
            ManifestCatalog("movie", "search", "Search", extra = listOf(com.vyrk.core.addon.ExtraProp("search", isRequired = true))),
        ))
        val (top, rest) = CatalogRules.homeRows(listOf(Descriptor(m, "https://x/manifest.json")))
        assertEquals(listOf("nfx"), top.map { it.catalog.id })
        assertEquals(listOf("top"), rest.map { it.catalog.id })
        assertEquals(listOf("search"), CatalogRules.searchCatalogs(listOf(Descriptor(m, "https://x/manifest.json"))).map { it.catalog.id })
    }

    @Test
    fun animeOnlyFromReliableSignals() {
        assertTrue(CatalogRules.isAnime(MetaPreview("kitsu:1", "series")))
        assertTrue(CatalogRules.isAnime(MetaPreview("tt1", "anime")))
        assertFalse(CatalogRules.isAnime(MetaPreview("tt1", "series", genres = listOf("Animation"))))
        assertTrue(CatalogRules.isAnimeCatalog(ref("Top Anime", "series")))
    }

    @Test
    fun searchMergesAndRanksPerType() {
        val q = "matrix"
        val a = SearchRanking.Source(0, listOf(MetaPreview("tt2", "movie", "The Matrix Reloaded"), MetaPreview("tt1", "movie", "The Matrix"), MetaPreview("tt9", "series", "Matrix")))
        val b = SearchRanking.Source(1, listOf(MetaPreview("tt1", "movie", "The Matrix"), MetaPreview("tt3", "movie", "Matrix")))
        val r = SearchRanking.merge(q, listOf(a, b))
        assertEquals(listOf("tt3", "tt1", "tt2"), r["movie"]!!.map { it.id })
        assertEquals(listOf("tt9"), r["series"]!!.map { it.id })
        assertEquals(1000, SearchRanking.score("Amélie", "amelie"))
    }
}

class ProfileAndUpdateTest {
    @Test
    fun pinHashing() {
        val h = PinHasher.hash("1234", iterations = 1000)
        assertTrue(PinHasher.verify("1234", h))
        assertFalse(PinHasher.verify("1235", h))
        assertFalse(h.contains("1234"))
        assertTrue(PinHasher.isValidPin("0000")); assertFalse(PinHasher.isValidPin("12a4"))
    }

    @Test
    fun escalatingLockout() {
        var l = PinLockout()
        l = l.onFailure(0); l = l.onFailure(0)
        assertFalse(l.isLocked(0))
        l = l.onFailure(0)
        assertEquals(30_000, l.remainingMs(0))
        l = l.onFailure(0)
        assertEquals(60_000, l.remainingMs(0))
        repeat(10) { l = l.onFailure(0) }
        assertEquals(PinLockout.MAX_MS, l.remainingMs(0))
        assertFalse(l.onSuccess().isLocked(0))
    }

    @Test
    fun updateRules() {
        assertTrue(Updates.isNewer("v1.2.0", "1.1.9"))
        assertFalse(Updates.isNewer("v1.2.0", "1.2.0"))
        assertFalse(Updates.isNewer("v1.2.0", "1.10.0"))
        val rel = GithubRelease("v2.0.0", assets = listOf(GithubAsset("vyrk-2.0.0-arm64-v8a.apk", "u1"), GithubAsset("vyrk-2.0.0-universal.apk", "u2")))
        assertEquals("u1", Updates.pickAsset(rel, "1.0.0", listOf("arm64-v8a"))!!.browser_download_url)
        assertEquals("u2", Updates.pickAsset(rel, "1.0.0", listOf("x86"))!!.browser_download_url)
        assertNull(Updates.pickAsset(rel.copy(prerelease = true), "1.0.0", listOf("arm64-v8a")))
        assertNull(Updates.latestUrl("not a repo"))
        assertEquals("https://api.github.com/repos/MYKLHARU/vyrk-tv/releases/latest", Updates.latestUrl("MYKLHARU/vyrk-tv"))
    }
}

class AccountApiTest {
    private class Fake(val get: Map<String, String> = emptyMap(), val post: Map<String, String> = emptyMap()) : HttpTransport {
        val bodies = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) =
            HttpResult(200, get.entries.first { url.contains(it.key) }.value)
        override suspend fun postJson(url: String, body: String, timeoutMs: Long): HttpResult {
            bodies += body
            return HttpResult(200, post.entries.first { url.endsWith(it.key) }.value)
        }
    }

    @Test
    fun linkPairingPendingThenAuthKey() = runTest {
        val api = StremioAccountApi(Fake(get = mapOf(
            "create" to """{"result":{"success":true,"code":"5OCH","link":"https://link.stremio.com/5OCH","qrcode":"https://link.stremio.com/qr?data=x"}}""",
            "code=PEND" to """{"error":{"code":101,"message":"Invalid or expired token"}}""",
            "code=DONE" to """{"result":{"authKey":"secret-key"}}""",
        )))
        assertEquals("5OCH", api.createLink().code)
        assertNull(api.readLink("PEND"))
        assertEquals("secret-key", api.readLink("DONE"))
    }

    @Test
    fun expiredSessionIsCodeOne() = runTest {
        val api = StremioAccountApi(Fake(post = mapOf("getUser" to """{"error":{"code":1,"message":"Session does not exist"}}""")))
        val e = assertFailsWith<ApiError> { api.getUser("k") }
        assertTrue(e.isSessionExpired)
    }

    @Test
    fun requestBodiesMatchStremioCore() = runTest {
        val fake = Fake(post = mapOf(
            "addonCollectionGet" to """{"result":{"addons":[{"transportUrl":"https://v3-cinemeta.strem.io/manifest.json","manifest":{"id":"c","name":"C","version":"1.0.0","resources":["catalog"],"types":["movie"]},"flags":{"official":true,"protected":true}},{"broken":true}],"lastModified":"2024-01-01T00:00:00Z"}}""",
            "datastoreMeta" to """{"result":[["tt1",1700000000000],["tt2",1700000000001]]}""",
            "datastorePut" to """{"result":{"success":true}}""",
        ))
        val api = StremioAccountApi(fake)
        val addons = api.addonCollectionGet("k")
        assertEquals(1, addons.size)
        assertTrue(addons[0].flags.official)
        assertEquals(2, api.datastoreMeta("k").size)
        api.datastorePut("k", emptyList())
        assertTrue(fake.bodies[0].contains("\"type\":\"AddonCollectionGet\"") && fake.bodies[0].contains("\"update\":true"))
        assertTrue(fake.bodies[1].contains("\"collection\":\"libraryItem\""))
        assertTrue(fake.bodies[2].contains("\"changes\":[]"))
    }
}
