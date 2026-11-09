package com.vyrk.audit

import com.vyrk.core.account.StremioAccountApi
import com.vyrk.core.addon.*
import com.vyrk.core.library.*
import com.vyrk.core.net.*
import com.vyrk.core.playback.ErrorClass
import com.vyrk.core.stream.*
import com.vyrk.core.subtitles.SubtitleParser
import com.vyrk.tv.data.*
import com.vyrk.tv.player.*
import com.vyrk.tv.ui.browse.MetaCache
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.After
import kotlin.test.*

/** Regression cases discovered in the v0.1.16 audit. Executes the current production sources. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditReproductionsTest {
    private val sessions = mutableListOf<ProfileSession>()
    private val minute = 60_000L
    private val e1 = Video("show:1:1", season = 1, episode = 1, runtime = "45 min")
    private val e2 = Video("show:1:2", season = 1, episode = 2, runtime = "45 min")
    private val show = MetaItem("show", "series", runtime = "45 min", videos = listOf(e1, e2))

    private fun descriptor(id: String) = Descriptor(Manifest(id, name = id, types = listOf("series"),
        rawResources = listOf(JsonPrimitive("meta"), JsonPrimitive("stream"))), "https://$id.test/manifest.json")

    private class FakeHttp : HttpTransport {
        var remote = listOf<Descriptor>()
        var rejectAddonWrites = false
        var getter: suspend (String) -> HttpResult = { HttpResult(200, "{}") }
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = getter(url)
        override suspend fun postJson(url: String, body: String, timeoutMs: Long): HttpResult {
            val result = when (url.substringAfterLast('/')) {
                "getUser" -> """{"_id":"synthetic-user","email":"audit@example.invalid"}"""
                "addonCollectionGet" -> """{"addons":${ProtocolJson.encodeToString(ListSerializer(Descriptor.serializer()), remote)}}"""
                "addonCollectionSet" -> {
                    if (rejectAddonWrites) throw IOException("Synthetic offline add-on save")
                    remote = ProtocolJson.decodeFromJsonElement(ListSerializer(Descriptor.serializer()), ProtocolJson.parseToJsonElement(body).jsonObject.getValue("addons"))
                    "{}"
                }
                "datastoreMeta", "datastoreGet" -> "[]"
                else -> "{}"
            }
            return HttpResult(200, """{"result":$result}""")
        }
    }

    private fun session(http: FakeHttp, profile: Profile = Profile("audit", "Audit"), dir: File = Files.createTempDirectory("vyrk-audit-").toFile()): ProfileSession =
        ProfileSession(profile, dir, SecretVault(), AddonClient(http), StremioAccountApi(http), { emptyList() }).also { sessions += it }

    private data class Fixture(val model: PlayerModel, val engine: PlayerEngine, val session: ProfileSession, val graph: AppGraph)
    private fun fixture(scope: CoroutineScope, meta: MetaItem = show, http: FakeHttp = FakeHttp(), subtitles: Boolean = false): Fixture {
        val session = session(http)
        val graph = AppGraph(http)
        graph.metaCache.put(meta)
        val engine = PlayerEngine()
        val candidates = listOf(1080, 720, 480).mapIndexed { i, resolution ->
            Candidate(Stream(url = "https://media.test/source-$i.mp4", title = "${resolution}p", subtitles = if (subtitles)
                listOf(StreamSubtitle(url = "https://subs.test/source-$i.srt", lang = "eng")) else emptyList()), "audit", "Audit", 0, i)
        }
        val ranked = StreamRanker.rank(candidates, DeviceCaps(), RankPrefs(preset = Preset.ADDON_ORDER))
        val request = PlaybackRequest(meta.toPreview(), meta.videos.first().id, ranked = ranked)
        return Fixture(PlayerModel(graph, session, scope, engine, request) { id, _ -> "resource-$id" }, engine, session, graph)
    }

    @After fun closeSessions() { sessions.forEach { it.close() } }

    @Test fun seekingForwardShouldNotMarkUnplayedContentWatched() {
        val item = LibraryItem("movie", "Movie", "movie", state = LibraryItemState(
            videoId = "movie", duration = 60 * minute, timeOffset = minute, timeWatched = minute))
        val (after, crossed) = LibraryRules.onTimeChanged(item, "movie", 50 * minute, 60 * minute, 1000)
        println("Jumped 1m -> 50m; recorded watched=${after.state.timeWatched / minute}m; crossed=$crossed")
        assertFalse(crossed, "A seek must not mark 49 minutes of unplayed video watched")
    }

    @Test fun rewindingShouldSaveTheNewResumePosition() {
        val item = LibraryItem("movie", "Movie", "movie", state = LibraryItemState(
            videoId = "movie", duration = 60 * minute, timeOffset = 10 * minute, timeWatched = 10 * minute))
        val after = LibraryRules.onTimeChanged(item, "movie", 2 * minute, 60 * minute, 1000).first
        assertEquals(2 * minute, LibraryRules.resumeMs(after, "movie"), "Resume should follow the viewer's rewind")
    }

    @Test fun episodeAdvanceShouldNotDoubleTotalWatchedTime() {
        val item = LibraryItem("show", "Show", "series", state = LibraryItemState(
            videoId = e1.id, timeOffset = 10_000, timeWatched = 10_000, overallTimeWatched = 10_000))
        assertEquals(10_000, LibraryRules.advanceToVideo(item, e2.id).state.overallTimeWatched)
    }

    @Test fun cancellingUpNextShouldKeepItCancelledForThisEpisode() = runTest {
        val f = fixture(backgroundScope)
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        f.engine.player.currentPosition = 44 * minute
        advanceTimeBy(1001); runCurrent()
        assertTrue(f.model.upNext)
        // Exact dismiss action used by PlayerActivity's Cancel and Back handlers.
        f.model.dismissUpNext()
        advanceTimeBy(501); runCurrent()
        println("After Cancel: upNext=${f.model.upNext}, countdown=${f.model.upNextCountdown}")
        assertFalse(f.model.upNext, "Tick must not immediately offer the dismissed countdown again")
    }

    @Test fun lowerQualityFailureShouldNotReplayTheSameFailedSource() = runTest {
        val f = fixture(backgroundScope)
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        f.model.lowerQuality(); f.model.onFirstFrame()
        val failedSource = f.model.current!!.candidate.stream.url
        f.model.onError(androidx.media3.common.PlaybackException(ErrorClass.TIMEOUT))
        println("Failed source=$failedSource; recovery source=${f.model.current!!.candidate.stream.url}")
        assertNotEquals(failedSource, f.model.current!!.candidate.stream.url)
    }

    @Test fun runtimeUsedForRankingShouldUnderstandHours() = runTest {
        val f = fixture(backgroundScope, show.copy(runtime = "1h 35min"))
        assertEquals(95, f.model.prefs("show").runtimeMinutes)
    }

    @Test fun switchingStreamShouldRefreshStreamProvidedSubtitles() = runTest {
        val f = fixture(backgroundScope, subtitles = true)
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        assertEquals("https://subs.test/source-0.srt", f.model.externalSubs.single().url)
        f.model.pickSource(f.model.candidates[1]); runCurrent(); f.model.onFirstFrame()
        assertEquals("https://subs.test/source-1.srt", f.model.externalSubs.single().url)
    }

    @Test fun oldEpisodeTimingResponseMustNotReplaceTheNewEpisodesTimings() = runTest {
        val old = CompletableDeferred<HttpResult>()
        val http = FakeHttp()
        http.getter = { url -> when {
            "introdb" in url && "episode=1" in url -> old.await()
            "introdb" in url && "episode=2" in url -> HttpResult(200, """{"intro":{"start_ms":20000,"end_ms":40000}}""")
            "/stream/" in url -> HttpResult(200, """{"streams":[{"url":"https://media.test/full.mp4","title":"720p"}]}""")
            else -> HttpResult(200, "{}")
        } }
        val meta = show.copy(id = "tt123", videos = listOf(e1.copy(id = "tt123:1:1"), e2.copy(id = "tt123:1:2")))
        val f = fixture(backgroundScope, meta, http)
        f.session.install(descriptor("timings"))
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        f.model.playNext(auto = false); runCurrent()
        assertEquals("tt123:1:2", f.model.video?.id)
        assertEquals(20_000, f.model.segments.single().startMs)
        old.complete(HttpResult(200, """{"intro":{"start_ms":1000,"end_ms":90000}}"""))
        runCurrent()
        println("Current episode=${f.model.video?.id}; intro start=${f.model.segments.single().startMs}")
        assertEquals(20_000, f.model.segments.single().startMs, "Late episode-one response must be ignored")
    }

    @Test fun offlineAddonChangesShouldSurviveTheNextSync() = runBlocking {
        val http = FakeHttp().apply { remote = listOf(descriptor("old")) }
        val s = session(http)
        s.connectAccount("synthetic-key") {}
        http.rejectAddonWrites = true
        s.install(descriptor("new"))
        assertTrue(s.addons.value.any { it.manifest.id == "new" })
        s.syncNow()
        assertTrue(s.addons.value.any { it.manifest.id == "new" }, "Failed cloud save must not discard the local installation")
    }

    @Test fun removingAllAddonsOnAnotherDeviceShouldSyncAnEmptyCollection() = runBlocking {
        val http = FakeHttp().apply { remote = listOf(descriptor("old")) }
        val s = session(http)
        s.connectAccount("synthetic-key") {}
        http.remote = emptyList()
        s.syncNow()
        assertTrue(s.addons.value.isEmpty(), "A valid empty remote collection must clear local add-ons")
    }

    @Test fun signingOutShouldRemainSignedOutAfterRestart() = runBlocking {
        val dir = Files.createTempDirectory("vyrk-logout-").toFile()
        val profile = Profile("audit", "Audit", accountEmail = "audit@example.invalid")
        val http = FakeHttp()
        val s = session(http, profile, dir)
        s.connectAccount("synthetic-key") {}
        s.logout(); s.close()
        val reopened = session(http, profile, dir)
        reopened.load()
        assertEquals(AccountState.None, reopened.account.value)
    }

    @Test fun metadataProviderChangeShouldNotReuseTheOldProvidersCachedAnswer() = runBlocking {
        val http = FakeHttp()
        http.getter = { url -> HttpResult(200, """{"meta":{"id":"show","type":"series","name":"${if ("first.test" in url) "First provider" else "Second provider"}"}}""") }
        val cache = MetaCache(AddonClient(http))
        assertEquals("First provider", cache.get(listOf(descriptor("first")), "series", "show")?.name)
        assertEquals("Second provider", cache.get(listOf(descriptor("second")), "series", "show")?.name)
    }

    @Test fun malformedSubtitleCueShouldBeIgnoredInsteadOfCrashing() {
        val text = "999999999999999999999:00:00,000 --> 999999999999999999999:00:01,000\nCorrupt cue"
        assertEquals(emptyList(), SubtitleParser.parse(text))
    }

    @Test fun concurrentSettingSavesShouldPersistTheLatestState() = runBlocking {
        val dir = Files.createTempDirectory("vyrk-settings-").toFile()
        val s = session(FakeHttp(), dir = dir)
        repeat(20) { round ->
            repeat(80) { value -> s.updateSettings { it.copy(posterSize = value) } }
            AtomicFiles.flush()
            val saved = AppJson.decodeFromString(ProfileSettings.serializer(), File(dir, "settings.json").readText())
            println("Settings round=$round expected=79 disk=${saved.posterSize}")
            assertEquals(79, saved.posterSize, "Older snapshot must not overwrite the newest settings")
        }
    }

    @Test fun liveChannelTypeShouldNotAccumulateVodResumeProgress() {
        val channel = MetaPreview("channel-1", "channel", "Live channel")
        val item = LibraryItem.fromMeta(channel, 1000)
        val saved = LibraryRules.onTimeChanged(item, channel.id, minute, 2 * 60 * minute, 2000).first
        assertTrue(saved.isLive, "The Live tab accepts channel as well as tv")
        assertEquals(0, saved.state.timeOffset)
    }

    @Test fun disablingBothAudioEffectsShouldPreservePcmSamples() {
        val processor = DialogueProcessor()
        assertFalse(processor.clearDialogue)
        assertFalse(processor.tvSpeaker)
        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(48_000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        assertSame(format, processor.onConfigure(format)) // Processor remains active even with both settings off.
        val input = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.nativeOrder())
        input.putShort(32_000).putShort(-32_000).flip()
        processor.queueInput(input)
        val output = processor.auditOutput.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        assertEquals(32_000.toShort(), output.get(0), "Disabled processing must not compress loud samples")
        assertEquals((-32_000).toShort(), output.get(1))
    }

    @Test fun dismissedUpNextAlsoPreventsEndOfFileAutoplay() = runTest {
        val f = fixture(backgroundScope)
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        f.model.dismissUpNext()
        f.engine.player.currentPosition = f.engine.player.duration
        f.model.onPlaybackState(androidx.media3.common.Player.STATE_ENDED)
        assertEquals(e1.id, f.model.request.videoId)
        assertTrue(f.model.finishRequested)
    }

    @Test fun pendingAddonChangesSurviveRestartAndRetrySuccessfully() = runBlocking {
        val dir = Files.createTempDirectory("vyrk-pending-").toFile()
        val http = FakeHttp().apply { remote = listOf(descriptor("old")) }
        val s = session(http, dir = dir)
        s.connectAccount("synthetic-key") {}
        http.rejectAddonWrites = true
        s.install(descriptor("new"))
        s.close(); AtomicFiles.flush()
        val reopened = session(http, dir = dir)
        reopened.load()
        reopened.scope.coroutineContext[Job]!!.children.filter { it.isActive }.take(1).forEach { it.join() }
        assertTrue(reopened.addons.value.any { it.manifest.id == "new" })
        http.rejectAddonWrites = false
        reopened.syncNow()
        assertTrue(http.remote.any { it.manifest.id == "new" })
        assertEquals(reopened.state.value.addonsRevision, reopened.state.value.addonsSyncedRevision)
    }

    @Test fun closingImmediatelyDoesNotCancelLatestSettingsWrite() = runBlocking {
        val dir = Files.createTempDirectory("vyrk-close-").toFile()
        val s = session(FakeHttp(), dir = dir)
        repeat(100) { value -> s.updateSettings { it.copy(posterSize = value) } }
        s.close()
        // A late callback from the disposed screen must not replace the saved choice.
        s.updateSettings { it.copy(posterSize = -1) }
        AtomicFiles.flush()
        val reopened = session(FakeHttp(), dir = dir)
        reopened.load()
        assertEquals(99, reopened.settings.value.posterSize)
    }

    @Test fun metadataPeekInvalidatesOnProviderChangeAndExpiry() = runBlocking {
        val http = FakeHttp().apply { getter = { HttpResult(200, """{"meta":{"id":"show","type":"series","name":"Cached"}}""") } }
        var providers = listOf(descriptor("first")); var clock = 0L
        val cache = MetaCache(AddonClient(http), { providers }, { clock }, 1000)
        cache.get(providers, "series", "show")
        assertNotNull(cache.peek("series", "show"))
        clock = 1001; assertNull(cache.peek("series", "show"))
        clock = 0; providers = listOf(descriptor("second"))
        assertNull(cache.peek("series", "show"))
    }

    @Test fun originalLanguageSettingSelectsMetadataLanguageInsteadOfEnglish() = runTest {
        val f = fixture(backgroundScope, show.copy(originalLanguage = "fr"))
        f.session.updateSettings { it.copy(originalLanguage = true) }
        f.engine.audio = f.engine.audio + com.vyrk.core.playback.AudioTrack("french", "fra", "French")
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        assertEquals("french", f.model.selectedAudio)
    }

    @Test fun hidingTheSelectedTabFallsBackToHome() {
        assertEquals(Tab.HOME, visibleTab(Tab.SERIES, listOf(Tab.HOME, Tab.MOVIES)))
        assertEquals(Tab.MOVIES, visibleTab(Tab.MOVIES, listOf(Tab.HOME, Tab.MOVIES)))
    }

    @Test fun playerSeekDoesNotCountSkippedTimeOrLoseRewoundResumePoint() = runTest {
        val f = fixture(backgroundScope)
        f.model.start(); runCurrent(); f.model.onFirstFrame()
        f.model.seekBy(40 * minute)
        val saved = f.session.item("show")!!
        assertEquals(0, saved.state.timeWatched)
        assertEquals(0, saved.state.flaggedWatched)
        f.model.seekBy(-30 * minute)
        assertEquals(10 * minute, f.session.item("show")!!.state.timeOffset)
    }
}
