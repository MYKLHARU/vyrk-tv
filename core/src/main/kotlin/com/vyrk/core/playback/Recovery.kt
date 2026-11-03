package com.vyrk.core.playback

/** Media3 failures grouped by what can be done about them. */
enum class ErrorClass {
    NETWORK, HTTP_EXPIRED, HTTP_NOT_FOUND, HTTP_SERVER, HTTP_OTHER, CONTAINER, DECODER,
    AUDIO_TRACK, BEHIND_LIVE_WINDOW, TIMEOUT, UNKNOWN
}

object ErrorClassifier {
    /**
     * Maps a Media3 `PlaybackException.errorCode` (and HTTP status when known) to a class.
     * Codes from androidx.media3.common.PlaybackException.
     */
    fun classify(errorCode: Int, httpStatus: Int? = null): ErrorClass {
        if (httpStatus != null) return when (httpStatus) {
            401, 403, 410 -> ErrorClass.HTTP_EXPIRED
            404 -> ErrorClass.HTTP_NOT_FOUND
            in 500..599 -> ErrorClass.HTTP_SERVER
            else -> ErrorClass.HTTP_OTHER
        }
        return when (errorCode) {
            1002 -> ErrorClass.BEHIND_LIVE_WINDOW // ERROR_CODE_BEHIND_LIVE_WINDOW
            1003 -> ErrorClass.TIMEOUT // ERROR_CODE_TIMEOUT
            2001, 2002, 2007 -> ErrorClass.NETWORK // connection failed / timeout / cleartext
            2004 -> ErrorClass.HTTP_OTHER // bad HTTP status without a known code
            2005 -> ErrorClass.HTTP_NOT_FOUND // file not found
            2003, 2006, 2008 -> ErrorClass.HTTP_OTHER
            in 3001..3004 -> ErrorClass.CONTAINER // parsing errors
            in 4001..4005 -> ErrorClass.DECODER
            in 5001..5003 -> ErrorClass.AUDIO_TRACK // audio track init/write/offload
            else -> ErrorClass.UNKNOWN
        }
    }
}

sealed interface RecoveryAction {
    data object Retry : RecoveryAction
    data object RefreshLink : RecoveryAction
    data object NextCandidate : RecoveryAction
    data object TryOtherAudioTrack : RecoveryAction
    data object EnableFfmpegAudio : RecoveryAction
    data object SeekToLiveEdge : RecoveryAction
    /** User picked this source: ask before switching. */
    data object AskUser : RecoveryAction
    data object LinkExpired : RecoveryAction
    data object GiveUp : RecoveryAction
}

/**
 * Start-up and failure policy for one play request. Pure state machine: the player reports
 * events, the policy answers with what to do next.
 */
class RecoveryPolicy(
    val candidateCount: Int,
    val userPicked: Boolean,
    val maxCandidates: Int = 3,
    val retriesPerCandidate: Int = 2,
    val firstFrameTimeoutMs: Long = 12_000,
) {
    var candidateIndex = 0; private set
    private var retries = 0
    private var refreshed = false
    private var audioFallbacks = 0
    private val limit get() = minOf(candidateCount, maxCandidates)

    fun onFailure(cls: ErrorClass, canRefresh: Boolean): RecoveryAction {
        if (cls == ErrorClass.BEHIND_LIVE_WINDOW) return RecoveryAction.SeekToLiveEdge
        if (cls == ErrorClass.AUDIO_TRACK) {
            audioFallbacks++
            return when (audioFallbacks) {
                1 -> RecoveryAction.EnableFfmpegAudio
                2 -> RecoveryAction.TryOtherAudioTrack
                else -> advance()
            }
        }
        if ((cls == ErrorClass.HTTP_EXPIRED || cls == ErrorClass.HTTP_NOT_FOUND) && !refreshed && canRefresh) {
            refreshed = true
            return RecoveryAction.RefreshLink
        }
        if (cls == ErrorClass.HTTP_EXPIRED && userPicked && !canRefresh) return RecoveryAction.LinkExpired
        val retryable = cls == ErrorClass.NETWORK || cls == ErrorClass.TIMEOUT || cls == ErrorClass.HTTP_SERVER || cls == ErrorClass.UNKNOWN
        if (retryable && retries < retriesPerCandidate) {
            retries++
            return RecoveryAction.Retry
        }
        return advance()
    }

    fun onFirstFrameTimeout(): RecoveryAction = onFailure(ErrorClass.TIMEOUT, canRefresh = false)

    private fun advance(): RecoveryAction {
        if (userPicked) return RecoveryAction.AskUser
        if (candidateIndex + 1 >= limit) return RecoveryAction.GiveUp
        candidateIndex++
        retries = 0; refreshed = false; audioFallbacks = 0
        return RecoveryAction.NextCandidate
    }
}

/**
 * Offers a lower quality after [threshold] stalls within [windowMs], ignoring buffering during
 * start-up and right after a seek.
 */
class StallDetector(
    private val threshold: Int = 3,
    private val windowMs: Long = 60_000,
    private val ignoreAfterSeekMs: Long = 5_000,
) {
    private val stalls = ArrayDeque<Long>()
    private var startedAt: Long? = null
    private var lastSeekAt: Long = Long.MIN_VALUE / 2
    private var prompted = false

    fun onFirstFrame(nowMs: Long) { startedAt = nowMs }
    fun onSeek(nowMs: Long) { lastSeekAt = nowMs }
    fun reset() { stalls.clear(); prompted = false }

    /** Returns true when the lower-quality prompt should be shown. */
    fun onBuffering(nowMs: Long): Boolean {
        if (startedAt == null) return false
        if (nowMs - lastSeekAt < ignoreAfterSeekMs) return false
        stalls.addLast(nowMs)
        while (stalls.isNotEmpty() && nowMs - stalls.first() > windowMs) stalls.removeFirst()
        if (!prompted && stalls.size >= threshold) { prompted = true; return true }
        return false
    }
}

/** Smoothed connection speed from Media3 bandwidth samples of at least 512 KB. */
class BandwidthMeter(initialBps: Double? = null, private val alpha: Double = 0.3) {
    var estimateBps: Double? = initialBps; private set

    fun onSample(bytes: Long, elapsedMs: Long): Double? {
        if (bytes < MIN_SAMPLE_BYTES || elapsedMs <= 0) return estimateBps
        val bps = bytes * 8.0 * 1000.0 / elapsedMs
        estimateBps = estimateBps?.let { it + alpha * (bps - it) } ?: bps
        return estimateBps
    }

    companion object { const val MIN_SAMPLE_BYTES = 512L * 1024 }
}
