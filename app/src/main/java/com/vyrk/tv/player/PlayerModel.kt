package com.vyrk.tv.player

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.Video
import com.vyrk.core.lang.Languages
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.library.nextVideo
import com.vyrk.core.playback.AudioPrefs
import com.vyrk.core.playback.Chapter
import com.vyrk.core.playback.IntroDb
import com.vyrk.core.playback.Markers
import com.vyrk.core.playback.MkvChapters
import com.vyrk.core.playback.Scrubber
import com.vyrk.core.playback.Segment
import com.vyrk.core.playback.SegmentKind
import com.vyrk.core.playback.SkipOffer
import com.vyrk.tv.data.SkipIntroMode
import com.vyrk.core.subtitles.Cue
import com.vyrk.core.playback.RecoveryAction
import com.vyrk.core.playback.RecoveryPolicy
import com.vyrk.core.playback.StallDetector
import com.vyrk.core.playback.SubtitlePrefs
import com.vyrk.core.playback.TextTrack
import com.vyrk.core.playback.TrackSelector
import com.vyrk.core.stream.Candidate
import com.vyrk.core.stream.Delivery
import com.vyrk.core.stream.RankPrefs
import com.vyrk.core.stream.Ranked
import com.vyrk.core.stream.StreamRanker
import com.vyrk.core.subtitles.SubtitleParser
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.ProfileSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase { CONNECTING, PLAYING, ASK, ERROR, NO_SOURCES }
enum class Panel { NONE, AUDIO_SUBS, SOURCES, SLEEP }
enum class SleepMode { OFF, END_OF_EPISODE, MINUTES_60 }

data class ExternalSub(val id: String, val lang: String, val label: String, val source: String, val url: String)

/** Player state and logic, kept apart from the activity so it is easy to follow. */
class PlayerModel(
    private val graph: AppGraph,
    private val session: ProfileSession,
    private val scope: CoroutineScope,
    val engine: PlayerEngine,
    var request: PlaybackRequest,
    private val strings: (Int, Array<out Any>) -> String,
) {
    var phase by mutableStateOf(Phase.CONNECTING)
    /** 0..1 while connecting: drives the logo loading bar. Only ever moves forward for one title. */
    var loadProgress by mutableStateOf(0f)
    private fun progressTo(p: Float) { if (p > loadProgress) loadProgress = p.coerceAtMost(1f) }
    var connecting by mutableStateOf("")
    var message by mutableStateOf("")
    var meta by mutableStateOf<MetaItem?>(null)
    var video by mutableStateOf<Video?>(null)
    var nextVideo by mutableStateOf<Video?>(null)
    val candidates = mutableStateListOf<Ranked>()
    val excluded = mutableStateListOf<Ranked>()
    var current by mutableStateOf<Ranked?>(null)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var buffered by mutableStateOf(0L)
    var playing by mutableStateOf(false)
    var controlsVisible by mutableStateOf(false)
    var panel by mutableStateOf(Panel.NONE)
    var lastInteraction = SystemClock.elapsedRealtime()
    var stallPrompt by mutableStateOf(false)
    var upNext by mutableStateOf(false)
    var upNextCountdown by mutableStateOf(10)
    var stillWatching by mutableStateOf(false)
    var sleep by mutableStateOf(SleepMode.OFF)
    private var sleepDeadline = 0L

    val externalSubs = mutableStateListOf<ExternalSub>()
    var selectedSub by mutableStateOf<String?>(null)
    var subDelayMs by mutableStateOf(0L)
    var cues by mutableStateOf<List<Cue>>(emptyList())
    var selectedAudio by mutableStateOf<String?>(null)

    // Timeline: container chapters merged with IntroDB segments.
    var markers by mutableStateOf<List<Chapter>>(emptyList())
    var segments by mutableStateOf<List<Segment>>(emptyList())
    var skipSegment by mutableStateOf<Segment?>(null)
    private var skipOffer = SkipOffer(emptyList())
    private var skipShownAt = 0L
    private var fileChapters: List<Chapter> = emptyList()
    private var chaptersFor: String? = null
    val scrubber = Scrubber({ markers }, { duration })

    /** Short message over the video ("Audio switched to …", "Skipped intro"). */
    var notice by mutableStateOf<String?>(null)
    private var noticeAt = 0L
    private val failedAudio = mutableSetOf<String>()

    private val settings: ProfileSettings get() = session.settings.value
    private var policy = RecoveryPolicy(1, false)
    private var index = 0
    private var item: LibraryItem? = null
    private var firstFrame = false
    private var firstFrameJob: Job? = null
    private var lastPushMs = SystemClock.elapsedRealtime()
    private val stall = StallDetector()
    private var autoplayedInRow = 0
    private var userPicked = false
    val isLive get() = request.meta.type == "tv"

    private val now get() = System.currentTimeMillis()
    private fun s(id: Int, vararg a: Any) = strings(id, a)

    fun start() {
        scope.launch { load() }
        scope.launch { tick() }
    }

    private suspend fun load() {
        val req = request
        phase = Phase.CONNECTING
        loadProgress = 0f
        progressTo(0.06f)
        connecting = s(com.vyrk.tv.R.string.connecting)
        val addons = session.effectiveAddons()
        val m = graph.metaCache.get(addons, req.meta.type, req.meta.id)
        meta = m
        progressTo(0.22f)
        val vid = req.videoId ?: m?.let { mm ->
            mm.videos.filter { (it.season ?: 1) > 0 }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).firstOrNull()?.id
        } ?: req.meta.id
        video = m?.videos?.firstOrNull { it.id == vid }
        nextVideo = m?.nextVideo(vid, now)
        resetTimeline()
        scope.launch { loadSegments(req.meta.type, req.meta.id, vid) }
        item = session.itemFor(req.meta)

        val streamType = req.meta.type
        val ranked: List<Ranked> = req.ranked ?: run {
            val results = graph.addonClient.allStreams(addons, streamType, vid)
            val cands = results.flatMapIndexed { order, (addon, r) ->
                (r as? AddonResult.Ok)?.value?.streams.orEmpty().mapIndexed { i, st -> Candidate(st, addon.manifest.id, addon.manifest.name, order, i) }
            }
            if (results.isEmpty()) { fail(Phase.NO_SOURCES, s(com.vyrk.tv.R.string.no_stream_addons)); return }
            StreamRanker.rank(cands, caps(), prefs(req.meta.id))
        }
        progressTo(0.42f)
        userPicked = req.picked != null
        val playable = buildList {
            req.picked?.let { add(it) }
            addAll(ranked.filter { it.playable && it.candidate.key != req.picked?.candidate?.key })
        }
        candidates.clear(); candidates.addAll(playable)
        excluded.clear(); excluded.addAll(ranked.filter { !it.playable })
        if (playable.isEmpty()) {
            val msg = when {
                ranked.isEmpty() -> s(com.vyrk.tv.R.string.no_sources_found)
                ranked.all { it.info.delivery == Delivery.TORRENT } -> s(com.vyrk.tv.R.string.no_sources_torrent)
                else -> s(com.vyrk.tv.R.string.no_sources_playable)
            }
            fail(Phase.NO_SOURCES, msg); return
        }
        policy = RecoveryPolicy(playable.size, userPicked)
        index = 0
        val resume = item?.takeIf { it.state.videoId == vid && it.state.timeOffset > 1 }?.state?.timeOffset ?: 0L
        playCandidate(resume)
        loadSubtitles(vid)
    }

    fun caps() = graph.device.caps(graph.deviceSettings.value.measuredBps)

    fun prefs(titleId: String): RankPrefs {
        val mem = session.titleMemory(titleId)
        val st = settings
        return RankPrefs(
            preset = st.preset, maxResolution = st.maxResolution, maxSizeBytes = st.maxSizeGb?.let { it * 1_000_000_000L },
            format = st.format, hdr = st.hdr, audioLanguages = st.audioLanguages,
            rememberedKey = mem.pickedKey, lastBingeGroup = mem.bingeGroup, lastReleaseGroup = mem.releaseGroup,
            runtimeMinutes = graph.metaCache.peek(request.meta.type, request.meta.id)?.runtime?.let { r -> Regex("""\d+""").find(r)?.value?.toIntOrNull() },
        )
    }

    private fun quality(r: Ranked) = listOf(r.info.resolutionLabel, r.info.releaseType.label).filter { it.isNotEmpty() }.joinToString(" ")

    private fun playCandidate(startMs: Long) {
        val r = candidates.getOrNull(index) ?: return
        current = r
        phase = Phase.CONNECTING
        connecting = s(com.vyrk.tv.R.string.connecting_to, r.candidate.addonName, quality(r).ifEmpty { "—" })
        progressTo(0.5f)
        firstFrame = false
        Diagnostics.log("player", "candidate ${index + 1}/${candidates.size} from ${r.candidate.addonId}")
        engine.prepare(r.candidate.stream, startMs)
        firstFrameJob?.cancel()
        firstFrameJob = scope.launch {
            delay(policy.firstFrameTimeoutMs)
            if (!firstFrame) handle(policy.onFirstFrameTimeout())
        }
    }

    fun onFirstFrame() {
        if (firstFrame) return
        firstFrame = true
        firstFrameJob?.cancel()
        progressTo(1f)
        phase = Phase.PLAYING
        stall.reset(); stall.onFirstFrame(SystemClock.elapsedRealtime())
        val r = current ?: return
        val titleId = request.meta.id
        session.updateTitleMemory(titleId) { mem ->
            mem.copy(
                pickedKey = if (userPicked) r.candidate.key else mem.pickedKey,
                bingeGroup = r.info.bingeGroup ?: mem.bingeGroup,
                releaseGroup = r.info.releaseGroup ?: mem.releaseGroup,
            )
        }
        applyTrackPrefs()
        showControls()
        r.candidate.stream.url?.let { url -> if (url != chaptersFor) { chaptersFor = url; scope.launch { loadChapters(r) } } }
    }

    private fun resetTimeline() {
        markers = emptyList(); segments = emptyList(); fileChapters = emptyList(); chaptersFor = null
        skipOffer = SkipOffer(emptyList()); skipSegment = null; scrubber.cancel(); failedAudio.clear()
    }

    /** IntroDB community timings for this episode or film (IMDb IDs only). */
    private suspend fun loadSegments(type: String, metaId: String, videoId: String) {
        if (settings.skipIntro == SkipIntroMode.OFF && !settings.chapterMarkers) return
        val url = IntroDb.url(type, metaId, videoId) ?: return
        val res = runCatching { graph.transport.get(url, timeoutMs = 8_000) }.getOrNull() ?: return
        if (!res.isSuccess) return
        val found = IntroDb.parse(res.body)
        segments = found
        skipOffer = SkipOffer(found)
        rebuildMarkers()
        Diagnostics.log("player", "introdb: ${found.size} segment(s)")
        android.util.Log.i("VyrkEvent", "introdb_segments ${found.size}")
    }

    /** Matroska chapters from the playing file, read with HTTP range requests. */
    private suspend fun loadChapters(r: Ranked) {
        if (!settings.chapterMarkers) return
        val url = r.candidate.stream.url ?: return
        val headers = r.candidate.stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val chapters = runCatching {
            MkvChapters.read { start, len -> graph.transport.getBytes(url, headers + ("Range" to "bytes=$start-${start + len - 1}"), 10_000) }
        }.getOrDefault(emptyList())
        if (url != chaptersFor) return
        fileChapters = chapters
        rebuildMarkers()
        if (chapters.isNotEmpty()) Diagnostics.log("player", "file chapters: ${chapters.size}")
    }

    private fun rebuildMarkers() {
        markers = if (settings.chapterMarkers) Markers.merge(fileChapters, segments, duration) else emptyList()
    }

    fun chapterTitleAt(ms: Long): Chapter? = Markers.at(markers, ms)

    fun skipNow() {
        val s = skipSegment ?: return
        skipOffer.dismiss(s)
        skipSegment = null
        stall.onSeek(SystemClock.elapsedRealtime())
        engine.player.seekTo(skipOffer.target(s))
    }

    fun showNotice(text: String) { notice = text; noticeAt = SystemClock.elapsedRealtime() }

    // Scrubbing state mirrored into Compose state so the timeline redraws.
    var scrubTarget by mutableStateOf<Long?>(null)
    var chapterMode by mutableStateOf(false)
    private fun syncScrub() { scrubTarget = scrubber.targetMs; chapterMode = scrubber.chapterMode }
    fun scrubStep(forward: Boolean) { scrubber.step(forward, position, SystemClock.elapsedRealtime()); syncScrub(); touch() }
    fun scrubEnterChapters(): Boolean { val ok = scrubber.enterChapterMode(position); syncScrub(); touch(); return ok }
    fun scrubExitChapters() { scrubber.exitChapterMode(); syncScrub(); touch() }
    fun scrubCancel() { scrubber.cancel(); syncScrub() }

    /** Seeks to the scrub target (OK on the timeline, or after a pause in pressing). */
    fun commitScrub() {
        val t = scrubber.commit()
        syncScrub()
        if (t == null) return
        stall.onSeek(SystemClock.elapsedRealtime())
        engine.player.seekTo(t)
        position = t
        touch()
    }

    fun onError(e: PlaybackException) {
        val cls = engine.classify(e)
        Diagnostics.log("player", "error ${e.errorCodeName} class=$cls")
        firstFrameJob?.cancel()
        // Once the film is playing, a broken audio track shouldn't stop it: change track, keep the second.
        if (cls == com.vyrk.core.playback.ErrorClass.AUDIO_TRACK && firstFrame && switchAudioMidFilm()) return
        if (cls == com.vyrk.core.playback.ErrorClass.DECODER) current?.let { decoderFailedOn(it) }
        handle(policy.onFailure(cls, canRefresh = true))
    }

    /**
     * The decoder can't play this kind of file (on the emulator: every 4K Dolby Vision HEVC file).
     * Remember it for the session so the ranker skips such files, and try the other kinds of file
     * among the remaining sources first: three more of the same kind would fail the same way.
     */
    private fun decoderFailedOn(r: Ranked) {
        val group = StreamRanker.formatGroup(r.info)
        graph.device.recordDecoderFailure(group)
        if (index + 1 > candidates.lastIndex) return
        val (same, other) = candidates.subList(index + 1, candidates.size).partition { StreamRanker.formatGroup(it.info) == group }
        if (same.isEmpty() || other.isEmpty()) return
        val reordered = other + same
        for (i in candidates.lastIndex downTo index + 1) candidates.removeAt(i)
        candidates.addAll(reordered)
        Diagnostics.log("player", "decoder failed on $group; trying ${other.size} other source(s) of another kind first")
    }

    /**
     * Mid-film audio failure: switch to the next best audio track at the same second (same
     * language on a simpler codec first). Returns false when there is nothing left to try.
     */
    private fun switchAudioMidFilm(): Boolean {
        val tracks = engine.audioTracks()
        val current = selectedAudio ?: tracks.firstOrNull { it.isDefault }?.id
        current?.let { failedAudio += it }
        val st = settings
        val next = TrackSelector.fallbackAudio(tracks, current, failedAudio, AudioPrefs(st.audioLanguages, st.originalLanguage, null, st.avoidCommentary)) ?: return false
        val pos = engine.player.currentPosition
        selectedAudio = next.id
        engine.select(C.TRACK_TYPE_AUDIO, next.id)
        Diagnostics.log("player", "audio track failed; switched to ${next.language ?: "?"} ${next.codec ?: ""}")
        android.util.Log.i("VyrkEvent", "audio_switched")
        engine.player.seekTo(pos)
        engine.player.prepare()
        engine.player.playWhenReady = true
        showNotice(s(com.vyrk.tv.R.string.audio_switched, listOfNotNull(next.language?.let { Languages.name(it) }, next.label).distinct().joinToString(" · ").ifEmpty { "—" }))
        return true
    }

    private fun handle(action: RecoveryAction) {
        val pos = engine.player.currentPosition.takeIf { firstFrame } ?: startPositionFallback()
        when (action) {
            RecoveryAction.Retry, RecoveryAction.EnableFfmpegAudio -> playCandidate(pos)
            RecoveryAction.TryOtherAudioTrack -> {
                val tracks = engine.audioTracks()
                val next = tracks.firstOrNull { it.id != selectedAudio && !it.isDefault } ?: tracks.firstOrNull()
                next?.let { selectedAudio = it.id; engine.select(C.TRACK_TYPE_AUDIO, it.id) }
                playCandidate(pos)
            }
            RecoveryAction.RefreshLink -> scope.launch { refreshLink(pos) }
            RecoveryAction.NextCandidate -> { index = policy.candidateIndex; playCandidate(pos) }
            RecoveryAction.SeekToLiveEdge -> { engine.player.seekToDefaultPosition(); engine.player.prepare() }
            RecoveryAction.AskUser -> { phase = Phase.ASK; message = s(com.vyrk.tv.R.string.source_failed_body) }
            RecoveryAction.LinkExpired -> fail(Phase.ERROR, s(com.vyrk.tv.R.string.link_expired))
            RecoveryAction.GiveUp -> fail(Phase.ERROR, s(com.vyrk.tv.R.string.nothing_played))
        }
    }

    private fun startPositionFallback(): Long =
        item?.takeIf { it.state.videoId == video?.id }?.state?.timeOffset?.takeIf { it > 1 } ?: 0L

    /** Asks the same add-on for a fresh link to the same file, then resumes. */
    private suspend fun refreshLink(pos: Long) {
        val r = current ?: return
        val addon = session.effectiveAddons().firstOrNull { it.manifest.id == r.candidate.addonId }
        val vid = video?.id ?: request.videoId ?: request.meta.id
        val fresh = addon?.let { graph.addonClient.streams(it, request.meta.type, vid) }
        val match = (fresh as? AddonResult.Ok)?.value?.streams?.firstOrNull { st ->
            val c = r.candidate.copy(stream = st)
            c.key == r.candidate.key && st.url != r.candidate.stream.url
        }
        if (match != null) {
            val updated = StreamRanker.score(r.candidate.copy(stream = match), caps(), prefs(request.meta.id))
            candidates[index] = updated
            Diagnostics.log("player", "refreshed link from ${r.candidate.addonId}")
            playCandidate(pos)
        } else {
            handle(policy.onFailure(com.vyrk.core.playback.ErrorClass.HTTP_NOT_FOUND, canRefresh = false))
        }
    }

    private fun fail(p: Phase, msg: String) { phase = p; message = msg; controlsVisible = false }

    fun retryPicked() { policy = RecoveryPolicy(candidates.size, userPicked); playCandidate(startPositionFallback()) }

    /** User chose a source in the Other sources panel. */
    fun pickSource(r: Ranked) {
        val pos = if (firstFrame) engine.player.currentPosition else startPositionFallback()
        userPicked = true
        val rest = candidates.filter { it.candidate.key != r.candidate.key }
        candidates.clear(); candidates.add(r); candidates.addAll(rest)
        index = 0
        policy = RecoveryPolicy(candidates.size, true)
        panel = Panel.NONE
        playCandidate(pos)
    }

    /** After the stall prompt: the next candidate with a lower resolution. */
    fun lowerQuality() {
        stallPrompt = false
        val cur = current ?: return
        val curRes = cur.info.resolution ?: return
        val idx = candidates.indexOfFirst { (it.info.resolution ?: 0) in 1 until curRes }
        if (idx < 0) return
        val pos = engine.player.currentPosition
        index = idx
        playCandidate(pos)
    }

    private suspend fun loadSubtitles(vid: String) {
        externalSubs.clear()
        current?.candidate?.stream?.subtitles?.forEachIndexed { i, sub ->
            externalSubs += ExternalSub("st:$i", Languages.normalise(sub.lang), Languages.label(sub.lang), current?.candidate?.addonName ?: "", sub.url)
        }
        val results = graph.addonClient.allSubtitles(session.effectiveAddons(), request.meta.type, vid)
        results.forEach { (addon, r) ->
            (r as? AddonResult.Ok)?.value?.subtitles?.forEachIndexed { i, sub ->
                externalSubs += ExternalSub("${addon.manifest.id}:$i", Languages.normalise(sub.lang), Languages.label(sub.lang), addon.manifest.name, sub.url)
            }
        }
        if (firstFrame) applyTrackPrefs()
    }

    fun allTextTracks(): List<TextTrack> =
        engine.embeddedTextTracks() + externalSubs.map { TextTrack(it.id, it.lang, it.label, false, it.source) }

    private var prefsApplied = false
    private fun applyTrackPrefs() {
        val st = settings
        val mem = session.titleMemory(request.meta.id)
        val audio = TrackSelector.selectAudio(engine.audioTracks(), AudioPrefs(st.audioLanguages, st.originalLanguage, meta?.country?.let { null }, st.avoidCommentary, mem.audio))
        if (audio != null && !audio.isDefault) { engine.select(C.TRACK_TYPE_AUDIO, audio.id) }
        selectedAudio = audio?.id
        if (prefsApplied && selectedSub != null) return
        val audioLang = audio?.language
        val sub = TrackSelector.selectSubtitle(allTextTracks(), SubtitlePrefs(st.subtitleMode, st.subtitleLanguages, false, mem.subtitle, mem.subtitleOff), audioLang)
        selectSubtitle(sub?.id, remember = false)
        prefsApplied = true
    }

    fun selectSubtitle(id: String?, remember: Boolean = true) {
        selectedSub = id
        cues = emptyList()
        when {
            id == null -> engine.select(C.TRACK_TYPE_TEXT, null)
            id.startsWith("emb:") -> engine.select(C.TRACK_TYPE_TEXT, id)
            else -> {
                engine.select(C.TRACK_TYPE_TEXT, null)
                val sub = externalSubs.firstOrNull { it.id == id } ?: return
                scope.launch {
                    val res = runCatching { graph.transport.get(sub.url, timeoutMs = 15_000) }.getOrNull()
                    if (res != null && res.isSuccess && selectedSub == id) cues = SubtitleParser.parse(res.body)
                }
            }
        }
        if (remember) session.updateTitleMemory(request.meta.id) { it.copy(subtitle = id, subtitleOff = id == null) }
    }

    fun selectAudio(id: String) {
        selectedAudio = id
        engine.select(C.TRACK_TYPE_AUDIO, id)
        session.updateTitleMemory(request.meta.id) { it.copy(audio = engine.audioTracks().firstOrNull { t -> t.id == id }?.language ?: id) }
    }

    fun currentCue(): String? {
        if (cues.isEmpty()) return null
        val t = position - subDelayMs
        return cues.firstOrNull { t >= it.startMs && t <= it.endMs }?.text
    }

    fun showControls() { controlsVisible = true; lastInteraction = SystemClock.elapsedRealtime() }
    fun touch() { lastInteraction = SystemClock.elapsedRealtime() }

    fun seekBy(ms: Long) {
        val p = engine.player
        val target = (p.currentPosition + ms).coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE)
        stall.onSeek(SystemClock.elapsedRealtime())
        p.seekTo(target)
        position = target
    }

    fun togglePlay() {
        val p = engine.player
        if (p.isPlaying) { p.pause(); session.pushNow() } else p.play()
    }

    fun onPlaybackState(state: Int) {
        if (state == Player.STATE_BUFFERING && firstFrame) {
            if (stall.onBuffering(SystemClock.elapsedRealtime())) stallPrompt = true
        }
        if (state == Player.STATE_ENDED) onEnded()
    }

    private fun onEnded() {
        saveProgress(final = true)
        if (sleep == SleepMode.END_OF_EPISODE) { finishRequested = true; return }
        if (nextVideo != null && settings.autoplayNext) playNext(auto = true) else finishRequested = true
    }

    var finishRequested by mutableStateOf(false)

    fun playNext(auto: Boolean) {
        val nv = nextVideo ?: return
        upNext = false
        saveProgress(final = true)
        if (auto) autoplayedInRow++ else autoplayedInRow = 0
        if (auto && settings.askStillWatching && autoplayedInRow >= 3) { stillWatching = true; engine.player.pause(); return }
        request = request.copy(videoId = nv.id, picked = null, ranked = null)
        prefsApplied = false
        selectedSub = null
        scope.launch { load() }
    }

    fun confirmStillWatching() { stillWatching = false; autoplayedInRow = 0; nextVideo?.let { playNext(auto = false) } ?: engine.player.play() }

    fun chooseSleep(mode: SleepMode) {
        sleep = mode
        sleepDeadline = if (mode == SleepMode.MINUTES_60) SystemClock.elapsedRealtime() + 60 * 60_000L else 0L
        panel = Panel.NONE
    }

    /** Saves progress locally on every update and pushes every 3 minutes. */
    fun saveProgress(final: Boolean = false) {
        if (isLive || !firstFrame) return
        val p = engine.player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val vid = video?.id ?: request.videoId ?: request.meta.id
        val base = session.item(request.meta.id) ?: item ?: return
        val wb = meta?.takeIf { it.videos.isNotEmpty() }?.let { base.watchedBitField(it.videos) }
        var (next, _) = LibraryRules.onTimeChanged(base, vid, p.currentPosition, dur, now, wb)
        if (final) next = LibraryRules.onPlaybackEnded(next, nextVideo)
        item = next
        session.putItem(next, pushSoon = false)
        val t = SystemClock.elapsedRealtime()
        if (final || t - lastPushMs >= LibraryRules.PUSH_TO_LIBRARY_EVERY_MS) { lastPushMs = t; session.pushNow() }
    }

    private suspend fun tick() {
        var n = 0
        while (scope.isActive) {
            delay(500)
            val p = engine.player
            position = p.currentPosition.coerceAtLeast(0)
            duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
            buffered = p.bufferedPosition
            playing = p.isPlaying
            if (controlsVisible && panel == Panel.NONE && !scrubber.active && SystemClock.elapsedRealtime() - lastInteraction > 5_000 && playing) controlsVisible = false
            val clock = SystemClock.elapsedRealtime()
            // Loading bar: buffer ahead of the start point (Media3 starts at about 2.5 s), plus a slow creep.
            if (phase == Phase.CONNECTING && loadProgress >= 0.5f) {
                val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
                progressTo(maxOf(0.5f + 0.45f * (ahead / 2_500f).coerceAtMost(1f), (loadProgress + 0.006f).coerceAtMost(0.9f)))
            } else if (phase == Phase.CONNECTING) progressTo((loadProgress + 0.004f).coerceAtMost(0.45f))
            if (scrubber.shouldAutoCommit(clock)) commitScrub()
            if (notice != null && clock - noticeAt > 3_500) notice = null
            if (markers.isNotEmpty() && duration > 0 && n % 20 == 0) rebuildMarkers()
            // Skip intro / recap from IntroDB timings.
            if (firstFrame && !isLive && settings.skipIntro != SkipIntroMode.OFF) {
                val seg = skipOffer.at(position)
                when {
                    seg == null -> skipSegment = null
                    settings.skipIntro == SkipIntroMode.AUTOMATIC -> {
                        skipSegment = seg; skipNow()
                        showNotice(s(if (seg.kind == SegmentKind.RECAP) com.vyrk.tv.R.string.skipped_recap else com.vyrk.tv.R.string.skipped_intro))
                    }
                    seg != skipSegment -> { skipSegment = seg; skipShownAt = clock; android.util.Log.i("VyrkEvent", "skip_offered ${seg.kind}") }
                    clock - skipShownAt > 10_000 -> { skipOffer.dismiss(seg); skipSegment = null } // shown 10 s and left alone
                }
            }
            if (++n % 2 == 0 && playing) saveProgress()
            if (sleep == SleepMode.MINUTES_60 && sleepDeadline > 0 && SystemClock.elapsedRealtime() > sleepDeadline) { p.pause(); finishRequested = true }
            // Up Next in the last 30 seconds (or past the credits threshold).
            val nv = nextVideo
            if (nv != null && duration > 0 && !isLive && settings.autoplayNext && sleep != SleepMode.END_OF_EPISODE) {
                // Credits start from IntroDB when known, otherwise the last 30 s or past 90%.
                val credits = skipOffer.creditsStartMs
                val near = if (credits != null) position >= credits else duration - position < 30_000 || position > duration * LibraryRules.CREDITS_THRESHOLD_COEF
                if (near && !upNext && playing) { upNext = true; upNextCountdown = 10 }
                if (upNext && n % 2 == 0) { upNextCountdown--; if (upNextCountdown <= 0) playNext(auto = true) }
            }
        }
    }

    fun close() {
        saveProgress(final = engine.player.duration > 0 && engine.player.currentPosition > engine.player.duration * LibraryRules.CREDITS_THRESHOLD_COEF)
        session.pushNow()
    }
}
