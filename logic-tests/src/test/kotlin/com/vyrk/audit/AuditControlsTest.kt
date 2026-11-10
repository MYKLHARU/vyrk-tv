package com.vyrk.audit

import com.vyrk.core.account.StremioAccountApi
import com.vyrk.core.addon.*
import com.vyrk.core.net.*
import com.vyrk.core.stream.*
import com.vyrk.tv.data.*
import com.vyrk.tv.player.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Positive controls for the audit platform boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditControlsTest {
    @Test fun animePreferencesAreSavedPerProfileAndOldProfilesDefaultToRegular() = runBlocking {
        assertFalse(AppJson.decodeFromString(ProfileSettings.serializer(), "{}").animeMode)
        val firstDir = Files.createTempDirectory("vyrk-anime-").toFile()
        val secondDir = Files.createTempDirectory("vyrk-regular-").toFile()
        val first = session(firstDir)
        val second = session(secondDir)
        try {
            first.updateSettings { it.copy(animeMode = true, animeGenre = "Isekai") }
            AtomicFiles.flush()
            val saved = AppJson.decodeFromString(ProfileSettings.serializer(), File(firstDir, "settings.json").readText())
            assertTrue(saved.animeMode)
            assertEquals("Isekai", saved.animeGenre)
            assertFalse(second.settings.value.animeMode)
            first.updateSettings { it.copy(animeMode = false) }
            assertEquals("Isekai", first.settings.value.animeGenre)
        } finally { first.close(); second.close() }
    }
    private val http = object : HttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = HttpResult(200, "{}")
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(200, """{"result":{}}""")
    }
    private fun session(dir: File) = ProfileSession(Profile("control", "Control"), dir, SecretVault(), AddonClient(http), StremioAccountApi(http), { emptyList() })

    @Test fun shortStreamProtectionWorksInActualPlayerModel() = runTest {
        val s = session(Files.createTempDirectory("vyrk-control-").toFile())
        try {
            val episode = Video("show:1:1", season = 1, episode = 1, runtime = "45 min")
            val next = Video("show:1:2", season = 1, episode = 2)
            val meta = MetaItem("show", "series", videos = listOf(episode, next))
            val graph = AppGraph(http).also { it.metaCache.put(meta) }
            val source = StreamRanker.rank(listOf(Candidate(Stream(url = "https://media.test/sample.mp4"), "test", "Test", 0, 0)), DeviceCaps(), RankPrefs())
            val engine = PlayerEngine().also { it.player.duration = 5 * 60_000 }
            val model = PlayerModel(graph, s, backgroundScope, engine, PlaybackRequest(meta.toPreview(), episode.id, ranked = source)) { id, _ -> "$id" }
            model.start(); runCurrent(); model.onFirstFrame()
            engine.player.currentPosition = 5 * 60_000
            advanceTimeBy(1001); runCurrent()
            assertFalse(model.upNext)
            model.onPlaybackState(androidx.media3.common.Player.STATE_ENDED)
            assertEquals(Phase.INCOMPLETE, model.phase)
            assertEquals(episode.id, model.request.videoId)
            assertEquals(0, s.item("show")!!.state.flaggedWatched)
            assertEquals(5 * 60_000, s.item("show")!!.state.timeOffset)
        } finally { s.close() }
    }

    @Test fun serialSettingSavesPersistTheLastValue() = runBlocking {
        val dir = Files.createTempDirectory("vyrk-control-").toFile()
        val s = session(dir)
        try {
            for (value in 0..5) {
                s.updateSettings { it.copy(posterSize = value) }
                AtomicFiles.flush()
            }
            val disk = AppJson.decodeFromString(ProfileSettings.serializer(), File(dir, "settings.json").readText())
            assertEquals(5, disk.posterSize)
        } finally { s.close() }
    }

    @Test fun completeStreamRemainsEligibleForAutoplay() = runTest {
        val s = session(Files.createTempDirectory("vyrk-control-").toFile())
        try {
            val e1 = Video("show:1:1", season = 1, episode = 1, runtime = "45 min")
            val e2 = Video("show:1:2", season = 1, episode = 2)
            val meta = MetaItem("show", "series", videos = listOf(e1, e2))
            val graph = AppGraph(http).also { it.metaCache.put(meta) }
            val ranked = StreamRanker.rank(listOf(Candidate(Stream(url = "https://media.test/full.mp4"), "test", "Test", 0, 0)), DeviceCaps(), RankPrefs())
            val engine = PlayerEngine()
            val model = PlayerModel(graph, s, backgroundScope, engine, PlaybackRequest(meta.toPreview(), e1.id, ranked = ranked)) { id, _ -> "$id" }
            model.start(); runCurrent(); model.onFirstFrame()
            engine.player.currentPosition = engine.player.duration
            model.onPlaybackState(androidx.media3.common.Player.STATE_ENDED)
            assertEquals(e2.id, model.request.videoId)
        } finally { s.close() }
    }
}
