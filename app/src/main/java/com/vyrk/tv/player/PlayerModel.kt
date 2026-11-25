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
import com.vyrk.core.playback.EpisodePlaybackGuard
import com.vyrk.core.playback.IncompleteReason
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

enum class Phase { CONNECTING, PLAYING, ASK, INCOMPLETE, ERROR, NO_SOURCES }
enum class Panel { NONE, AUDIO_SUBS, SOURCES, SLEEP, EPISODES }

/** How the picture fills the screen: whole picture, cropped to fill, or stretched to fill. */
enum class PictureSize { FIT, ZOOM, STRETCH }
enum class SleepMode { OFF, END_OF_EPISODE, MINUTES_60 }

data class ExternalSub(val id: String, val lang: String, val label: String, val source: String, val url: String)

/** How long after the first frame a hard freeze still counts against the source. */
private const val FREEZE_GRACE_MS = 15_000L

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
    private var panelState by mutableStateOf(Panel.NONE)
    var panel: Panel
        get() = panelState
        set(value) {
            panelState = value
            raiseDueStallPrompt()
        }
    /** The stall prompt came due while it had to wait (see [raiseStallPrompt]). */
    private var stallPromptDue = false

    /**
     * The prompt takes focus with "Lower quality" selected, so it waits while a key meant for
     * something else could land on it: an open panel, or a seek being chosen on the timeline.
     */
    private fun raiseStallPrompt() {
        if (panel == Panel.NONE && !scrubber.active) { stallPrompt = true; vyrkEvent("stall_prompt") } else stallPromptDue = true
    }

    private fun raiseDueStallPrompt() {
        if (!stallPromptDue || panel != Panel.NONE || scrubber.active) return
        stallPromptDue = false
        if (phase == Phase.PLAYING && lowerQualityIndex() >= 0) { stallPrompt = true; vyrkEvent("stall_prompt") }
    }
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
    private var recoveryAudio: String? = null

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
    /** When the player opened, for "picture after … ms" in the diagnostics. */
    private val openedAt = android.os.SystemClock.elapsedRealtime()
    private val backupMode get() = graph.deviceSettings.value.backupPlayer
    private fun newPolicy(count: Int, picked: Boolean, start: Int = 0) =
        RecoveryPolicy(count, picked, startIndex = start, backupEngine = engine.backupAvailable && backupMode == com.vyrk.tv.data.BackupPlayer.WHEN_NEEDED)
    /** Direct links the debrid service gave for torrent sources, by [Candidate.key]; reused until one fails. */
    private val debridLinks = mutableMapOf<String, com.vyrk.core.debrid.DebridResult.Ok>()
    /** The stream the engine is playing (a torrent's debrid link in place of the torrent). */
    private var playingStream: com.vyrk.core.addon.Stream? = null
    private var index = 0
    private var item: LibraryItem? = null
    /** The video whose file the engine has, so progress is saved against it (not the one loading next). */
    private var playingVideoId: String? = null
    /** The title on screen for Trakt: the film, or the playing episode. */
    val traktItem get() = com.vyrk.core.trakt.TraktItem.of(request.meta.type, request.meta.id, playingVideoId ?: request.videoId)
    private var firstFrame = false
    private var firstFrameJob: Job? = null

    /** Told just before each source starts (the freeze guard notes it down). */
    var onSourceStarting: ((Ranked) -> Unit)? = null
    /** Told when the picture appears or the source fails cleanly. */
    var onSourceSettled: (() -> Unit)? = null
    /**
     * Offered the best source before VYRK plays it (with the start position). Returns true when it
     * played it somewhere else (another app), and VYRK's player steps aside.
     */
    var handOff: ((Ranked, Long) -> Boolean)? = null
    private var lastPushMs = SystemClock.elapsedRealtime()
    private val stall = StallDetector()
    private var autoplayedInRow = 0
    private var userPicked = false
    private var episodeGuard = EpisodePlaybackGuard(null)
    private var incompleteReason: IncompleteReason? = null
    private var endedHandled = false
    private var completionSaved = false
    private var upNextDismissed = false
    private var loadGeneration = 0
    private var streamGeneration = 0
    private var subtitleJob: Job? = null
    private var originalLanguageCode: String? = null
    private var progressPosition = 0L
    private var progressClock = 0L
    private var progressWasPlaying = false
    private var watchedAddedDuringPlayback = false
    private var previouslyWatched = false
    private val isEpisode get() = request.meta.type == "series"
    private fun sourceId(r: Ranked) = "${r.candidate.addonId}:${r.candidate.key}"
    fun suspectSource(r: Ranked): Boolean = episodeGuard.isSuspect(sourceId(r))
    val sourceChoices: List<Ranked> get() = candidates.sortedBy { suspectSource(it) }
    val isLive get() = com.vyrk.core.addon.isLiveType(request.meta.type)

    private val now get() = System.currentTimeMillis()
    private fun s(id: Int, vararg a: Any) = strings(id, a)

    fun start() {
        scope.launch { load() }
        scope.launch { tick() }
    }

    private suspend fun load() {
        val req = request
        val generation = ++loadGeneration
        upNextDismissed = false
        phase = Phase.CONNECTING
        loadProgress = 0f
        progressTo(0.06f)
        connecting = s(com.vyrk.tv.R.string.connecting)
        val addons = session.effectiveAddons()
        val m = graph.metaCache.get(addons, req.meta.type, req.meta.id)
        if (generation != loadGeneration) return
        meta = m
        originalLanguageCode = m?.originalLanguage ?: req.meta.originalLanguage
        if (settings.originalLanguage && originalLanguageCode == null) scope.launch {
            val language = graph.tmdb.originalLanguage(req.meta.id, req.meta.type)
            if (generation == loadGeneration) {
                originalLanguageCode = language
                if (firstFrame) applyTrackPrefs()
            }
        }
        progressTo(0.22f)
        val vid = req.videoId ?: m?.let { mm ->
            mm.videos.filter { (it.season ?: 1) > 0 }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).firstOrNull()?.id
        } ?: req.meta.id
        video = m?.videos?.firstOrNull { it.id == vid }
        nextVideo = m?.nextVideo(vid, now)
        episodeGuard = EpisodePlaybackGuard(EpisodePlaybackGuard.runtime(m, video))
        incompleteReason = null
        upNext = false
        upNextCountdown = 10
        stallPrompt = false; stallPromptDue = false
        panel = Panel.NONE
        resetTimeline()
        scope.launch { loadSegments(req.meta.type, req.meta.id, vid, generation, m?.malId) }
        item = session.itemFor(req.meta)
        watchedAddedDuringPlayback = false
        previouslyWatched = m?.videos?.let { item?.watchedBitField(it)?.getVideo(vid) } == true

        val streamType = req.meta.type
        val ranked: List<Ranked> = req.ranked ?: run {
            val results = graph.addonClient.allStreams(addons, streamType, vid)
            if (generation != loadGeneration) return
            if (results.isEmpty()) { fail(Phase.NO_SOURCES, s(com.vyrk.tv.R.string.no_stream_addons)); return }
            graph.rankStreams(results, prefs(req.meta.id))
        }
        if (generation != loadGeneration) return
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
        policy = newPolicy(playable.size, userPicked)
        index = 0
        val startAt = LibraryRules.resumeMs(item, vid)
        if (handOff?.invoke(playable.first(), startAt) == true) return
        playCandidate(startAt)
    }

    fun caps() = graph.playbackCaps()

    fun prefs(titleId: String): RankPrefs = session.rankPrefs(
        titleId, runtimeMinutes = graph.metaCache.peek(request.meta.type, request.meta.id)?.runtime?.let { com.vyrk.core.addon.RuntimeText.minutes(it) },
    )

    private fun quality(r: Ranked) = listOf(r.info.resolutionLabel, r.info.releaseType.label).filter { it.isNotEmpty() }.joinToString(" ")

    private fun playCandidate(startMs: Long, audioOverride: String? = null, backup: Boolean = false) {
        val r = candidates.getOrNull(index) ?: return
        val generation = ++streamGeneration
        completionSaved = false
        subtitleJob?.cancel()
        prefsApplied = false
        selectedSub = null; selectedAudio = null; cues = emptyList(); externalSubs.clear()
        recoveryAudio = audioOverride
        failedAudio.clear()
        incompleteReason = null
        endedHandled = false
        upNext = false
        upNextCountdown = 10
        stallPrompt = false; stallPromptDue = false
        position = 0
        duration = 0
        current = r
        episodeGuard.beginSource(sourceId(r))
        phase = Phase.CONNECTING
        connecting = s(com.vyrk.tv.R.string.connecting_to, r.candidate.addonName, quality(r).ifEmpty { "—" })
        progressTo(0.5f)
        firstFrame = false
        firstFrameJob?.cancel()
        playingVideoId = video?.id ?: request.videoId ?: request.meta.id
        Diagnostics.log("player", "candidate ${index + 1}/${candidates.size} from ${r.candidate.addonId}")
        vyrkEvent("play_start $playingVideoId $startMs")
        onSourceStarting?.invoke(r)
        val stream = r.candidate.stream
        if (stream.url == null && r.info.delivery == Delivery.TORRENT) {
            debridLinks[r.candidate.key]?.let { ok -> return startEngine(r, debridStream(stream, ok), startMs, audioOverride, backup, generation) }
            val service = graph.debrid.service ?: return handleDebrid(com.vyrk.core.debrid.DebridResult.SignedOut)
            connecting = s(com.vyrk.tv.R.string.debrid_checking, service.label)
            val vid = playingVideoId
            scope.launch {
                val result = graph.debrid.resolve(stream, vid)
                if (generation != streamGeneration) return@launch
                if (result is com.vyrk.core.debrid.DebridResult.Ok) {
                    debridLinks[r.candidate.key] = result
                    vyrkEvent("debrid_ok")
                    startEngine(r, debridStream(stream, result), startMs, audioOverride, backup, generation)
                } else handleDebrid(result)
            }
            return
        }
        startEngine(r, stream, startMs, audioOverride, backup, generation)
    }

    /** The torrent stream as a web link: the debrid service's URL, with its file name and size for the parser and chapters. */
    private fun debridStream(s: com.vyrk.core.addon.Stream, ok: com.vyrk.core.debrid.DebridResult.Ok) =
        s.copy(url = ok.url, behaviorHints = s.behaviorHints.copy(filename = s.behaviorHints.filename ?: ok.filename, videoSize = s.behaviorHints.videoSize ?: ok.bytes))

    private fun startEngine(r: Ranked, stream: com.vyrk.core.addon.Stream, startMs: Long, audioOverride: String?, backup: Boolean, generation: Int) {
        playingStream = stream
        val useBackup = backup || backupMode == com.vyrk.tv.data.BackupPlayer.ALWAYS
        if (useBackup && engine.backupAvailable) vyrkEvent("backup_player")
        engine.prepare(stream, startMs, preserveAudio = audioOverride != null, backup = useBackup)
        resetProgressClock()
        scope.launch { loadSubtitles(playingVideoId!!, r, generation) }
        firstFrameJob?.cancel()
        firstFrameJob = scope.launch {
            delay(maxOf(policy.firstFrameTimeoutMs, com.vyrk.core.playback.PlaybackBuffering.startupTimeoutMs(isLive, r.info)))
            if (!firstFrame) noPicture(r)
        }
    }

    /**
     * The debrid service couldn't give a link. Not ready, gone or no such file: on to the next
     * source (these don't count against the three tries). Signed out or no premium: say so.
     */
    private fun handleDebrid(result: com.vyrk.core.debrid.DebridResult) {
        val label = graph.debrid.service?.label ?: "Debrid"
        onSourceSettled?.invoke()
        vyrkEvent("debrid_unavailable ${result.javaClass.simpleName}")
        val msg = when (result) {
            com.vyrk.core.debrid.DebridResult.SignedOut -> return fail(Phase.ERROR, s(com.vyrk.tv.R.string.debrid_signed_out, label))
            com.vyrk.core.debrid.DebridResult.NotPremium -> return fail(Phase.ERROR, s(com.vyrk.tv.R.string.debrid_not_premium, label))
            com.vyrk.core.debrid.DebridResult.NotCached -> s(com.vyrk.tv.R.string.debrid_not_cached, label)
            com.vyrk.core.debrid.DebridResult.Removed -> s(com.vyrk.tv.R.string.debrid_removed, label)
            com.vyrk.core.debrid.DebridResult.NoFile -> s(com.vyrk.tv.R.string.debrid_no_file)
            else -> s(com.vyrk.tv.R.string.debrid_failed, label)
        }
        when (val action = policy.onUnavailable()) {
            RecoveryAction.AskUser -> { phase = Phase.ASK; message = msg }
            RecoveryAction.GiveUp -> fail(Phase.ERROR, msg)
            else -> { showNotice(msg); handle(action) }
        }
    }

    /**
     * The picture didn't come up in time. If the file had loaded (the player was ready, or sound
     * was playing) the decoder is what failed: count it against this kind of file, so the next
     * source tried is a different kind, and say what's happening. Then move on.
     */
    private fun noPicture(r: Ranked) {
        val p = engine.player
        val loaded = p.playbackState == Player.STATE_READY || p.isPlaying
        Diagnostics.log("player", "no picture after start (loaded=$loaded)")
        vyrkEvent("no_picture $loaded")
        onSourceSettled?.invoke()
        val action = policy.onNoPicture(loaded)
        if (loaded && action != RecoveryAction.TryBackupEngine) {
            decoderFailedOn(r)
            if (index + 1 <= candidates.lastIndex) showNotice(s(com.vyrk.tv.R.string.no_picture_trying_next))
        }
        handle(action)
    }

    /** Where you stopped in another app, saved like VYRK's own progress. */
    fun saveExternalProgress(positionMs: Long, durationMs: Long): Long? {
        if (isLive || positionMs <= 0) return null
        val vid = playingVideoId ?: video?.id ?: request.videoId ?: request.meta.id
        val base = session.item(request.meta.id) ?: item ?: session.itemFor(request.meta)
        // Not every player reports the length; the listed runtime ("118 min") stands in.
        val dur = durationMs.takeIf { it > 0 } ?: meta?.runtime?.let { Regex("""\d+""").find(it)?.value?.toLongOrNull()?.times(60_000) } ?: return null
        val wb = meta?.takeIf { it.videos.isNotEmpty() }?.let { base.watchedBitField(it.videos) }
        var (next, _) = LibraryRules.onTimeChanged(base, vid, positionMs, dur, now, wb, allowCompletion = true, playedMs = positionMs)
        if (positionMs >= dur * 0.9) next = LibraryRules.onPlaybackEnded(next, nextVideo, allowCompletion = true)
        item = next
        session.putItem(next, pushSoon = false)
        session.pushNow()
        return dur
    }

    fun onFirstFrame() {
        if (firstFrame) return
        firstFrame = true
        // Some TVs lock up a few seconds after the picture starts (a display-mode switch), so the
        // freeze guard stays armed briefly — unless another source has started since.
        val settling = current
        scope.launch { delay(FREEZE_GRACE_MS); if (current === settling && firstFrame) onSourceSettled?.invoke() }
        Diagnostics.log("player", "first frame ${engine.bufferDiagnostics()}")
        vyrkEvent("first_frame ${if (engine.onBackup) "mpv" else "exo"} cached=${engine.cachedBytes}")
        Diagnostics.log("player", "picture after ${android.os.SystemClock.elapsedRealtime() - openedAt} ms (${engine.cachedBytes / 1024} KB from instant start)")
        resetProgressClock()
        firstFrameJob?.cancel()
        progressTo(1f)
        phase = Phase.PLAYING
        stall.reset(); stallPromptDue = false; stall.onFirstFrame(SystemClock.elapsedRealtime())
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
        playingStream?.url?.let { url -> if (url != chaptersFor) { chaptersFor = url; scope.launch { loadChapters(playingStream ?: r.candidate.stream) } } }
    }

    private fun resetTimeline() {
        markers = emptyList(); segments = emptyList(); fileChapters = emptyList(); chaptersFor = null
        skipOffer = SkipOffer(emptyList()); skipSegment = null; scrubber.cancel(); failedAudio.clear()
    }

    /** IntroDB community timings for this episode or film (IMDb IDs); AniSkip openings and endings for anime (Kitsu and MAL IDs). */
    private suspend fun loadSegments(type: String, metaId: String, videoId: String, generation: Int, malHint: String? = null) {
        if (settings.skipIntro == SkipIntroMode.OFF && !settings.chapterMarkers) return
        val url = IntroDb.url(type, metaId, videoId)
        if (url == null) { loadAniSkip(metaId, videoId, generation, malHint); return }
        val res = runCatching { graph.transport.get(url, timeoutMs = 8_000) }.getOrNull() ?: return
        if (!res.isSuccess) return
        val found = IntroDb.parse(res.body)
        if (generation != loadGeneration) return
        segments = found
        skipOffer = SkipOffer(found)
        rebuildMarkers()
        Diagnostics.log("player", "introdb: ${found.size} segment(s)")
        vyrkEvent("introdb_segments ${found.size}")
    }

    private suspend fun loadAniSkip(metaId: String, videoId: String, generation: Int, malHint: String?) {
        val ep = com.vyrk.core.anime.AniSkip.episode(metaId, videoId, malHint) ?: return
        val mal = graph.animeIds.mal(ep) ?: return
        val res = runCatching { graph.transport.get(com.vyrk.core.anime.AniSkip.url(mal, ep.episode), timeoutMs = 8_000) }.getOrNull() ?: return
        if (!res.isSuccess) return
        // Timings are for a particular cut: wait for the file's length to check they fit.
        var waited = 0
        while (duration <= 0 && waited < 120 && generation == loadGeneration) { kotlinx.coroutines.delay(500); waited++ }
        val found = com.vyrk.core.anime.AniSkip.parse(res.body, duration)
        if (generation != loadGeneration) return
        segments = found
        skipOffer = SkipOffer(found)
        rebuildMarkers()
        Diagnostics.log("player", "aniskip: ${found.size} segment(s) for mal $mal ep ${ep.episode}")
        vyrkEvent("aniskip_segments ${found.size}")
    }

    /** The label for a skip button: opening and ending for anime, otherwise intro and recap. */
    fun skipLabel(seg: Segment): Int = when (seg.kind) {
        SegmentKind.RECAP -> com.vyrk.tv.R.string.skip_recap
        SegmentKind.OUTRO -> com.vyrk.tv.R.string.skip_ending
        else -> if (seg.anime) com.vyrk.tv.R.string.skip_opening else com.vyrk.tv.R.string.skip_intro
    }

    /** Matroska chapters from the playing file, read with HTTP range requests. */
    private suspend fun loadChapters(stream: com.vyrk.core.addon.Stream) {
        if (!settings.chapterMarkers) return
        val url = stream.url ?: return
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
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
        onPositionDiscontinuity()
    }

    fun showNotice(text: String) { notice = text; noticeAt = SystemClock.elapsedRealtime() }

    // Scrubbing state mirrored into Compose state so the timeline redraws.
    var scrubTarget by mutableStateOf<Long?>(null)
    var chapterMode by mutableStateOf(false)
    private fun syncScrub() { scrubTarget = scrubber.targetMs; chapterMode = scrubber.chapterMode; raiseDueStallPrompt() }
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
        onPositionDiscontinuity()
        position = t
        touch()
    }

    fun onError(e: PlaybackException) {
        onSourceSettled?.invoke()
        val cls = engine.classify(e)
        Diagnostics.log("player", "error ${e.errorCodeName} class=$cls")
        firstFrameJob?.cancel()
        // Once the film is playing, a broken audio track shouldn't stop it: change track, keep the second.
        if (cls == com.vyrk.core.playback.ErrorClass.AUDIO_TRACK && firstFrame && switchAudioMidFilm()) return
        val action = policy.onFailure(cls, canRefresh = true)
        if (cls == com.vyrk.core.playback.ErrorClass.DECODER && action != RecoveryAction.TryBackupEngine) current?.let { decoderFailedOn(it) }
        handle(action)
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
        val next = TrackSelector.fallbackAudio(tracks, current, failedAudio, AudioPrefs(st.audioLanguages, st.originalLanguage, originalLanguageCode, st.avoidCommentary)) ?: return false
        val pos = engine.player.currentPosition
        selectedAudio = next.id
        recoveryAudio = next.id
        engine.select(C.TRACK_TYPE_AUDIO, next.id)
        Diagnostics.log("player", "audio track failed; switched to ${next.language ?: "?"} ${next.codec ?: ""}")
        vyrkEvent("audio_switched")
        stall.onSeek(SystemClock.elapsedRealtime())
        engine.player.seekTo(pos)
        engine.player.prepare()
        engine.player.playWhenReady = true
        showNotice(s(com.vyrk.tv.R.string.audio_switched, listOfNotNull(next.language?.let { Languages.name(it) }, next.label).distinct().joinToString(" · ").ifEmpty { "—" }))
        return true
    }

    private fun handle(action: RecoveryAction) {
        // A retry is an explicit choice once a source is known to be incomplete.
        if (isEpisode && current?.let { suspectSource(it) } == true &&
            action in listOf(RecoveryAction.Retry, RecoveryAction.RefreshLink, RecoveryAction.EnableFfmpegAudio, RecoveryAction.TryOtherAudioTrack)) {
            showIncomplete(IncompleteReason.SHORT_STREAM)
            return
        }
        val pos = engine.player.currentPosition.takeIf { firstFrame } ?: startPositionFallback()
        when (action) {
            RecoveryAction.Retry, RecoveryAction.EnableFfmpegAudio -> playCandidate(pos, recoveryAudio)
            RecoveryAction.TryOtherAudioTrack -> {
                val tracks = engine.audioTracks()
                val next = tracks.firstOrNull { it.id != selectedAudio && !it.isDefault } ?: tracks.firstOrNull()
                next?.let { selectedAudio = it.id; engine.select(C.TRACK_TYPE_AUDIO, it.id) }
                playCandidate(pos, next?.id)
            }
            RecoveryAction.RefreshLink -> {
                val r = current
                // A debrid link that stopped working: ask the service for a fresh one.
                if (r != null && r.info.delivery == Delivery.TORRENT && debridLinks.remove(r.candidate.key) != null) {
                    graph.debrid.forget(r.candidate.stream, playingVideoId)
                    playCandidate(pos, recoveryAudio, engine.onBackup)
                }
                else scope.launch { refreshLink(pos) }
            }
            RecoveryAction.TryBackupEngine -> {
                Diagnostics.log("player", "trying the backup player")
                showNotice(s(com.vyrk.tv.R.string.trying_backup_player))
                playCandidate(pos, recoveryAudio, backup = true)
            }
            RecoveryAction.NextCandidate -> {
                val next = candidates.getOrNull(policy.candidateIndex)
                if (next != null && suspectSource(next)) handle(policy.onFirstFrameTimeout())
                else { index = policy.candidateIndex; playCandidate(pos) }
            }
            RecoveryAction.SeekToLiveEdge -> { engine.player.seekToDefaultPosition(); engine.player.prepare() }
            RecoveryAction.AskUser -> { phase = Phase.ASK; message = s(com.vyrk.tv.R.string.source_failed_body) }
            RecoveryAction.LinkExpired -> fail(Phase.ERROR, s(com.vyrk.tv.R.string.link_expired))
            RecoveryAction.GiveUp -> fail(Phase.ERROR, s(com.vyrk.tv.R.string.nothing_played))
        }
    }

    private fun startPositionFallback(): Long = video?.id?.let { LibraryRules.resumeMs(item, it) } ?: 0L
    /** Where this title would resume. */
    fun resumePosition(): Long = startPositionFallback()

    /** Asks the same add-on for a fresh link to the same file, then resumes. */
    private suspend fun refreshLink(pos: Long) {
        val r = current ?: return
        val generation = streamGeneration
        val addon = session.effectiveAddons().firstOrNull { it.manifest.id == r.candidate.addonId }
        val vid = video?.id ?: request.videoId ?: request.meta.id
        val fresh = addon?.let { graph.addonClient.streams(it, request.meta.type, vid) }
        if (generation != streamGeneration) return
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

    fun retryPicked() {
        val pos = if (incompleteReason != null) 0L else startPositionFallback()
        policy = newPolicy(candidates.size, userPicked, index)
        playCandidate(pos)
    }

    fun switchStream() { panel = Panel.SOURCES }

    /** User chose a source in the Other sources panel. */
    fun pickSource(r: Ranked) {
        saveProgress()
        val pos = if (incompleteReason != null) 0L else if (firstFrame) engine.player.currentPosition else startPositionFallback()
        userPicked = true
        val rest = candidates.filter { it.candidate.key != r.candidate.key }
        candidates.clear(); candidates.add(r); candidates.addAll(rest)
        index = 0
        policy = newPolicy(candidates.size, true)
        panel = Panel.NONE
        playCandidate(pos)
    }

    /** The first usable source with a lower resolution than the one playing, or -1. */
    private fun lowerQualityIndex(): Int {
        val curRes = current?.info?.resolution ?: return -1
        return candidates.indexOfFirst { !suspectSource(it) && (it.info.resolution ?: 0) in 1 until curRes }
    }

    /** After the stall prompt: the next candidate with a lower resolution. */
    fun lowerQuality() {
        stallPrompt = false; stallPromptDue = false
        val idx = lowerQualityIndex()
        if (idx < 0) return
        val pos = engine.player.currentPosition
        index = idx
        policy = newPolicy(candidates.size, userPicked, index)
        playCandidate(pos)
    }

    private suspend fun loadSubtitles(vid: String, source: Ranked, generation: Int) {
        val found = source.candidate.stream.subtitles.mapIndexed { i, sub ->
            ExternalSub("st:$generation:$i", Languages.normalise(sub.lang), Languages.label(sub.lang), source.candidate.addonName, sub.url)
        }.toMutableList()
        if (generation != streamGeneration) return
        externalSubs.addAll(found)
        val type = request.meta.type
        val results = graph.addonClient.allSubtitles(session.effectiveAddons(), type, vid)
        results.forEach { (addon, r) ->
            (r as? AddonResult.Ok)?.value?.subtitles?.forEachIndexed { i, sub ->
                found += ExternalSub("${addon.manifest.id}:$vid:$i", Languages.normalise(sub.lang), Languages.label(sub.lang), addon.manifest.name, sub.url)
            }
        }
        if (generation != streamGeneration) return
        externalSubs.clear(); externalSubs.addAll(found)
        if (firstFrame) applyTrackPrefs()
    }

    fun allTextTracks(): List<TextTrack> =
        engine.embeddedTextTracks() + externalSubs.map { TextTrack(it.id, it.lang, it.label, false, it.source) }

    private var prefsApplied = false
    private fun applyTrackPrefs() {
        val st = settings
        val mem = session.titleMemory(request.meta.id)
        val tracks = engine.audioTracks()
        val audio = tracks.firstOrNull { it.id == recoveryAudio }
            ?: TrackSelector.selectAudio(tracks, AudioPrefs(st.audioLanguages, st.originalLanguage, originalLanguageCode, st.avoidCommentary, mem.audio))
        if (audio != null && !audio.isDefault) { engine.select(C.TRACK_TYPE_AUDIO, audio.id) }
        selectedAudio = audio?.id
        if (prefsApplied && selectedSub != null) return
        val audioLang = audio?.language
        val sub = TrackSelector.selectSubtitle(allTextTracks(), SubtitlePrefs(st.subtitleMode, st.subtitleLanguages, false, mem.subtitle, mem.subtitleOff), audioLang)
        selectSubtitle(sub?.id, remember = false)
        prefsApplied = true
    }

    fun selectSubtitle(id: String?, remember: Boolean = true) {
        subtitleJob?.cancel()
        val generation = streamGeneration
        selectedSub = id
        cues = emptyList()
        when {
            id == null -> engine.select(C.TRACK_TYPE_TEXT, null)
            id.startsWith("emb:") -> engine.select(C.TRACK_TYPE_TEXT, id)
            else -> {
                engine.select(C.TRACK_TYPE_TEXT, null)
                val sub = externalSubs.firstOrNull { it.id == id } ?: return
                subtitleJob = scope.launch {
                    val res = runCatching { graph.transport.get(sub.url, timeoutMs = 15_000) }.getOrNull()
                    if (res != null && res.isSuccess && generation == streamGeneration && selectedSub == id)
                        cues = runCatching { SubtitleParser.parse(res.body) }.getOrDefault(emptyList())
                }
            }
        }
        if (remember) session.updateTitleMemory(request.meta.id) { it.copy(subtitle = id, subtitleOff = id == null) }
    }

    fun selectAudio(id: String) {
        recoveryAudio = null
        selectedAudio = id
        engine.select(C.TRACK_TYPE_AUDIO, id)
        session.updateTitleMemory(request.meta.id) { it.copy(audio = engine.audioTracks().firstOrNull { t -> t.id == id }?.language ?: id) }
    }

    fun currentCue(): String? {
        if (cues.isEmpty()) return null
        val t = position - subDelayMs
        return cues.firstOrNull { t >= it.startMs && t <= it.endMs }?.text
    }

    /** Picture size, remembered on this device for every title. */
    var pictureSize by mutableStateOf(runCatching { PictureSize.valueOf(graph.deviceSettings.value.pictureSize) }.getOrDefault(PictureSize.FIT))
        private set

    fun cyclePictureSize() {
        val next = PictureSize.entries[(pictureSize.ordinal + 1) % PictureSize.entries.size]
        pictureSize = next
        graph.updateDevice { it.copy(pictureSize = next.name) }
        showNotice(s(com.vyrk.tv.R.string.picture_now, s(when (next) {
            PictureSize.FIT -> com.vyrk.tv.R.string.picture_fit
            PictureSize.ZOOM -> com.vyrk.tv.R.string.picture_zoom
            PictureSize.STRETCH -> com.vyrk.tv.R.string.picture_stretch
        })))
    }

    /** The subtitles last turned off with [toggleSubtitles], to bring back the same ones. */
    private var lastSubtitle: String? = null

    /** One press: subtitles off, or back on (the ones last used, else the first in your languages). */
    fun toggleSubtitles() {
        val on = selectedSub
        if (on != null) {
            lastSubtitle = on
            selectSubtitle(null)
            showNotice(s(com.vyrk.tv.R.string.subtitles_off))
            return
        }
        val tracks = com.vyrk.core.playback.TrackSelector.sortForDisplay(allTextTracks(), session.settings.value.subtitleLanguages)
        val pick = tracks.firstOrNull { it.id == lastSubtitle } ?: tracks.firstOrNull { !it.forced } ?: tracks.firstOrNull()
        if (pick == null) { showNotice(s(com.vyrk.tv.R.string.no_subtitles)); return }
        selectSubtitle(pick.id)
        showNotice(s(com.vyrk.tv.R.string.subtitles_now, pick.language?.let { com.vyrk.core.lang.Languages.name(it) } ?: pick.label ?: pick.source))
    }

    /** The remote's audio-track button: the next audio track, named over the video. */
    fun cycleAudio() {
        val tracks = engine.audioTracks()
        if (tracks.size < 2) { tracks.firstOrNull()?.let { showNotice(s(com.vyrk.tv.R.string.audio_now, audioName(it, 0))) }; return }
        val at = tracks.indexOfFirst { it.id == selectedAudio }.takeIf { it >= 0 } ?: tracks.indexOfFirst { it.isDefault }.coerceAtLeast(0)
        val i = (at + 1) % tracks.size
        selectAudio(tracks[i].id)
        showNotice(s(com.vyrk.tv.R.string.audio_now, audioName(tracks[i], i)))
    }

    private fun audioName(t: com.vyrk.core.playback.AudioTrack, i: Int): String =
        t.language?.takeUnless { it.lowercase() in setOf("und", "mul", "zxx", "mis") }?.let { com.vyrk.core.lang.Languages.name(it) }
            ?: t.label?.takeUnless { it.isBlank() || it.equals(t.language, true) } ?: s(com.vyrk.tv.R.string.audio_track_n, i + 1)

    /** Plays another episode of this series, chosen from the episode list. */
    fun playEpisode(v: Video) {
        panel = Panel.NONE
        if (v.id == (playingVideoId ?: video?.id ?: request.videoId)) return
        upNext = false
        autoplayedInRow = 0
        saveProgress()
        engine.player.pause()
        endedHandled = true
        firstFrame = false
        firstFrameJob?.cancel()
        request = request.copy(videoId = v.id, picked = null, ranked = null)
        prefsApplied = false
        selectedSub = null
        scope.launch { load() }
    }

    fun showControls() { controlsVisible = true; lastInteraction = SystemClock.elapsedRealtime() }
    fun touch() { lastInteraction = SystemClock.elapsedRealtime() }

    fun seekBy(ms: Long) {
        val p = engine.player
        val target = (p.currentPosition + ms).coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE)
        stall.onSeek(SystemClock.elapsedRealtime())
        p.seekTo(target)
        onPositionDiscontinuity()
        position = target
    }

    /** Seeks to [ms] from the start (the phone timeline, dragged and let go). */
    fun seekTo(ms: Long) = seekBy(ms - engine.player.currentPosition)

    fun togglePlay() {
        val p = engine.player
        if (p.isPlaying) { p.pause(); session.pushNow() } else p.play()
    }

    fun onPlaybackState(state: Int) {
        if (state == Player.STATE_BUFFERING && firstFrame) {
            Diagnostics.log("player", "rebuffer ${engine.bufferDiagnostics()}")
            // "Try a lower quality?" is only asked when there is one: its button did nothing otherwise.
            if (stall.onBuffering(SystemClock.elapsedRealtime()) && lowerQualityIndex() >= 0) raiseStallPrompt()
        }
        if (state == Player.STATE_ENDED) onEnded()
    }

    private fun onEnded() {
        if (endedHandled) return
        endedHandled = true
        if (isEpisode) {
            val p = engine.player
            episodeGuard.observeDuration(p.duration)
            val reason = if (!firstFrame) IncompleteReason.UNKNOWN_END else episodeGuard.endReason(p.currentPosition, p.duration)
            if (reason != null) { showIncomplete(reason); return }
        }
        saveProgress(final = true)
        if (sleep == SleepMode.END_OF_EPISODE) { finishRequested = true; return }
        if (nextVideo != null && settings.autoplayNext && !upNextDismissed) playNext(auto = true) else finishRequested = true
    }

    var finishRequested by mutableStateOf(false)

    private fun showIncomplete(reason: IncompleteReason) {
        incompleteReason = reason
        current?.let { episodeGuard.rememberSuspect(sourceId(it)) }
        upNext = false
        upNextCountdown = 10
        stallPrompt = false; stallPromptDue = false
        firstFrameJob?.cancel()
        engine.player.pause()
        saveProgress()
        panel = Panel.NONE
        fail(Phase.INCOMPLETE, s(com.vyrk.tv.R.string.incomplete_stream_body))
        // Never log a source URL or key: either may contain add-on credentials.
        Diagnostics.log("player", "incomplete reason=$reason reportedMs=${engine.player.duration} positionMs=${engine.player.currentPosition} expectedMs=${episodeGuard.expected?.durationMs} delivery=${current?.info?.delivery}")
    }

    fun playNext(auto: Boolean) {
        val nv = nextVideo ?: return
        if (auto && (upNextDismissed || !firstFrame || phase != Phase.PLAYING || incompleteReason != null ||
                (isEpisode && episodeGuard.isShort(engine.player.duration)))) {
            upNext = false
            return
        }
        upNext = false
        saveProgress(final = true)
        if (auto) autoplayedInRow++ else autoplayedInRow = 0
        if (auto && settings.askStillWatching && autoplayedInRow >= 3) { stillWatching = true; engine.player.pause(); return }
        // Stop this episode (its credits kept playing while the next one's sources loaded, and its
        // position near the end was saved against the next episode, which then started there) and
        // save nothing until the next one shows its first frame.
        engine.player.pause()
        endedHandled = true
        firstFrame = false
        firstFrameJob?.cancel()
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

    fun dismissUpNext() { upNextDismissed = true; upNext = false; upNextCountdown = 10 }

    private fun resetProgressClock() {
        progressPosition = engine.player.currentPosition
        progressClock = SystemClock.elapsedRealtime()
        progressWasPlaying = engine.player.isPlaying
    }

    /** Media3 seeks/timeline jumps change resume position but never add skipped viewing time. */
    fun onPositionDiscontinuity() {
        resetProgressClock()
        saveProgress()
    }

    /** Saves progress locally on every update and pushes every 3 minutes. */
    fun saveProgress(final: Boolean = false) {
        if (isLive || !firstFrame || completionSaved) return
        val p = engine.player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: episodeGuard.progressDuration(0).takeIf { incompleteReason != null && it > 0 } ?: return
        if (isEpisode) episodeGuard.observeDuration(dur)
        val safeCompletion = !isEpisode || (incompleteReason == null && !episodeGuard.isShort(dur))
        if (!safeCompletion) current?.let { episodeGuard.rememberSuspect(sourceId(it)) }
        val progressDuration = if (isEpisode) episodeGuard.progressDuration(dur) else dur
        val vid = playingVideoId ?: video?.id ?: request.videoId ?: request.meta.id
        var base = session.item(request.meta.id) ?: item ?: return
        if (!safeCompletion && watchedAddedDuringPlayback) {
            base = LibraryRules.undoPlaybackWatched(base, vid, meta?.videos.orEmpty(), previouslyWatched)
            watchedAddedDuringPlayback = false
        }
        val wb = meta?.takeIf { it.videos.isNotEmpty() }?.let { base.watchedBitField(it.videos) }
        val clock = SystemClock.elapsedRealtime()
        val delta = p.currentPosition - progressPosition
        val elapsed = (clock - progressClock).coerceAtLeast(0)
        val played = if (progressWasPlaying && delta >= 0 && delta <= elapsed * p.playbackParameters.speed + 2_000) delta else 0L
        resetProgressClock()
        var (next, crossed) = LibraryRules.onTimeChanged(base, vid, p.currentPosition, progressDuration, now, wb,
            allowCompletion = safeCompletion, playedMs = played)
        if (crossed) watchedAddedDuringPlayback = true
        if (final) {
            next = LibraryRules.onPlaybackEnded(next, nextVideo, allowCompletion = safeCompletion)
            completionSaved = safeCompletion
        }
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
            if (isEpisode && firstFrame) {
                episodeGuard.observeDuration(duration)
                if (episodeGuard.isShort(duration)) {
                    current?.let { episodeGuard.rememberSuspect(sourceId(it)) }
                    upNext = false
                }
            }
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
                val seg = skipOffer.at(position, duration)
                when {
                    seg == null -> skipSegment = null
                    settings.skipIntro == SkipIntroMode.AUTOMATIC -> {
                        skipSegment = seg; skipNow()
                        showNotice(s(when { seg.kind == SegmentKind.RECAP -> com.vyrk.tv.R.string.skipped_recap; seg.kind == SegmentKind.OUTRO -> com.vyrk.tv.R.string.skipped_ending; seg.anime -> com.vyrk.tv.R.string.skipped_opening; else -> com.vyrk.tv.R.string.skipped_intro }))
                    }
                    seg != skipSegment -> { skipSegment = seg; skipShownAt = clock; vyrkEvent("skip_offered ${seg.kind}") }
                    clock - skipShownAt > 10_000 -> { skipOffer.dismiss(seg); skipSegment = null } // shown 10 s and left alone
                }
            }
            if (++n % 2 == 0 && playing) saveProgress()
            if (sleep == SleepMode.MINUTES_60 && sleepDeadline > 0 && SystemClock.elapsedRealtime() > sleepDeadline) { p.pause(); finishRequested = true }
            // Up Next in the last 30 seconds (or past the credits threshold).
            val nv = nextVideo
            if (nv != null && firstFrame && phase == Phase.PLAYING && duration > 0 && !isLive &&
                incompleteReason == null && (!isEpisode || !episodeGuard.isShort(duration)) &&
                settings.autoplayNext && !upNextDismissed && sleep != SleepMode.END_OF_EPISODE) {
                // Credits start from IntroDB when known, otherwise the last 30 s or past 90%.
                val credits = skipOffer.creditsStart(duration)
                val near = episodeGuard.upNextDue(position, duration, credits)
                if (!near) { upNext = false; upNextCountdown = 10 }
                if (near && !upNext && playing) { upNext = true; upNextCountdown = 10; vyrkEvent("upnext_offered ${video?.id}") }
                if (upNext && playing && panel == Panel.NONE && !scrubber.active && n % 2 == 0) { upNextCountdown--; if (upNextCountdown <= 0) playNext(auto = true) }
            } else upNext = false
        }
    }

    fun close() {
        saveProgress(final = engine.player.duration > 0 && engine.player.currentPosition > engine.player.duration * LibraryRules.CREDITS_THRESHOLD_COEF)
        session.pushNow()
    }
}

/** Playback events for the test journeys, off unless switched on with `adb shell setprop log.tag.VyrkEvent DEBUG`. */
private fun vyrkEvent(message: String) {
    if (android.util.Log.isLoggable("VyrkEvent", android.util.Log.DEBUG)) android.util.Log.i("VyrkEvent", message)
}
