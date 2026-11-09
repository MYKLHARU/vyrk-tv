package com.vyrk.core.playback

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.RuntimeText
import com.vyrk.core.addon.Video
import com.vyrk.core.library.LibraryRules

data class EpisodeRuntime(val durationMs: Long, val episodeSpecific: Boolean)

enum class IncompleteReason { SHORT_STREAM, EARLY_END, UNKNOWN_END }

/** Shared by progress, Up Next and Media3 end handling. No fixed minimum episode length. */
class EpisodePlaybackGuard(val expected: EpisodeRuntime?) {
    private var source = ""
    private val sourceDurations = mutableMapOf<String, Long>()
    private val largestDurationMs get() = sourceDurations[source] ?: 0L
    private val suspectSources = mutableSetOf<String>()

    fun beginSource(source: String) { this.source = source }

    fun observeDuration(durationMs: Long) {
        if (durationMs > largestDurationMs) sourceDurations[source] = durationMs
    }

    fun isShort(durationMs: Long): Boolean {
        if (durationMs <= 0) return false // Media3 TIME_UNSET and a not-yet-ready timeline.
        val runtime = expected
        val metadataShort = runtime != null && significantlyShorter(
            durationMs, runtime.durationMs,
            if (runtime.episodeSpecific) 0.65 else 0.4,
            if (runtime.episodeSpecific) 120_000 else 300_000,
        )
        return metadataShort || significantlyShorter(durationMs, largestDurationMs, 0.65, 120_000)
    }

    fun endReason(positionMs: Long, durationMs: Long): IncompleteReason? = when {
        isShort(durationMs) -> IncompleteReason.SHORT_STREAM
        // A known timeline can also end without reaching its advertised end.
        durationMs > 0 && significantlyShorter(positionMs.coerceAtLeast(0), durationMs, 0.9, 30_000) -> IncompleteReason.EARLY_END
        expected != null && significantlyShorter(positionMs.coerceAtLeast(0), expected.durationMs,
            if (expected.episodeSpecific) 0.65 else 0.4,
            if (expected.episodeSpecific) 120_000 else 300_000) -> IncompleteReason.EARLY_END
        durationMs <= 0 -> IncompleteReason.UNKNOWN_END
        else -> null
    }

    /** Preserve a useful resume point without displaying the bad source as 100% watched. */
    fun progressDuration(durationMs: Long): Long = if (durationMs <= 0 || isShort(durationMs))
        maxOf(durationMs, expected?.durationMs ?: 0, largestDurationMs) else durationMs

    /** Ignore credits timings from another release when they fall far before this stream's end. */
    fun upNextDue(positionMs: Long, durationMs: Long, creditsStartMs: Long?): Boolean {
        if (durationMs <= 0 || isShort(durationMs)) return false
        val credits = creditsStartMs?.takeIf { it >= durationMs * LibraryRules.WATCHED_THRESHOLD_COEF && it < durationMs }
        return if (credits != null) positionMs >= credits
        else durationMs - positionMs < 30_000 || positionMs > durationMs * LibraryRules.CREDITS_THRESHOLD_COEF
    }

    fun rememberSuspect(source: String) { suspectSources += source }
    fun isSuspect(source: String): Boolean = source in suspectSources

    companion object {
        /** Episode data wins. A show's average is only weak evidence; don't apply it to specials. */
        fun runtime(meta: MetaItem?, video: Video?): EpisodeRuntime? {
            if (meta?.type != "series") return null
            fun ms(raw: String?): Long? = RuntimeText.minutes(raw)?.takeIf { it in 1..720 }?.times(60_000L)
            ms(video?.runtime)?.let { return EpisodeRuntime(it, true) }
            if (video?.season == 0) return null
            return ms(meta.runtime)?.let { EpisodeRuntime(it, false) }
        }

        private fun significantlyShorter(actual: Long, expected: Long, ratio: Double, gap: Long): Boolean =
            expected > 0 && actual < expected * ratio && expected - actual >= gap
    }
}
