package com.vyrk.core.playback

/** A timeline landmark: a container chapter or an IntroDB segment boundary. */
data class Chapter(val startMs: Long, val title: String?, val kind: SegmentKind? = null)

object Markers {
    /**
     * Merges container chapters with IntroDB segments. A segment start replaces a chapter within
     * ±5 s (IntroDB is usually more precise); segment ends become untitled boundaries so the part
     * after an intro starts its own chapter.
     */
    fun merge(chapters: List<Chapter>, segments: List<Segment>, durationMs: Long): List<Chapter> {
        val out = chapters.filter { it.startMs in 0 until (if (durationMs > 0) durationMs else Long.MAX_VALUE) }.toMutableList()
        for (s in segments) {
            out.removeAll { kotlin.math.abs(it.startMs - s.startMs) <= 5_000 }
            out += Chapter(s.startMs, null, s.kind)
            if (out.none { kotlin.math.abs(it.startMs - s.endMs) <= 5_000 } && (durationMs <= 0 || s.endMs < durationMs - 1_000)) {
                out += Chapter(s.endMs, null, null)
            }
        }
        return out.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /** The chapter playing at [positionMs]: the last one that started at or before it. */
    fun at(markers: List<Chapter>, positionMs: Long): Chapter? = markers.lastOrNull { it.startMs <= positionMs }

    fun previous(markers: List<Chapter>, positionMs: Long): Long? =
        markers.lastOrNull { it.startMs < positionMs - 1_000 }?.startMs ?: if (markers.isNotEmpty()) 0L else null

    fun next(markers: List<Chapter>, positionMs: Long): Long? = markers.firstOrNull { it.startMs > positionMs + 1_000 }?.startMs
}

/**
 * Smart scrubbing on the timeline. Left/Right move a preview position with acceleration
 * (10 s, then 30 s, then 60 s steps while presses keep coming); fine scrubs snap to a chapter start
 * within 3 s. Chapter mode jumps straight between chapter starts. Nothing seeks until [commit].
 */
class Scrubber(private val markers: () -> List<Chapter>, private val durationMs: () -> Long) {
    var targetMs: Long? = null
        private set
    var chapterMode = false
        private set
    private var lastPressAt = Long.MIN_VALUE / 2
    private var streak = 0

    val active: Boolean get() = targetMs != null

    fun step(forward: Boolean, fromMs: Long, nowMs: Long) {
        val base = targetMs ?: fromMs
        val dur = durationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
        if (chapterMode) {
            val m = markers()
            targetMs = (if (forward) Markers.next(m, base) else Markers.previous(m, base)) ?: base
            return
        }
        streak = if (nowMs - lastPressAt <= 1_200) streak + 1 else 0
        lastPressAt = nowMs
        val stepMs = when {
            streak >= 8 -> 60_000L
            streak >= 4 -> 30_000L
            else -> 10_000L
        }
        var t = (base + if (forward) stepMs else -stepMs).coerceIn(0, dur)
        // Snap to a nearby chapter start so a cut lands on its first frame.
        markers().minByOrNull { kotlin.math.abs(it.startMs - t) }?.let { c ->
            if (kotlin.math.abs(c.startMs - t) <= 3_000 && (if (forward) c.startMs > base else c.startMs < base)) t = c.startMs
        }
        targetMs = t
    }

    /** Up on the timeline: chapter mode, snapping to the chapter that contains the current target. */
    fun enterChapterMode(fromMs: Long): Boolean {
        val m = markers()
        if (m.isEmpty()) return false
        chapterMode = true
        targetMs = Markers.at(m, targetMs ?: fromMs)?.startMs ?: 0L
        return true
    }

    fun exitChapterMode() { chapterMode = false }

    /** Returns the position to seek to and resets. */
    fun commit(): Long? {
        val t = targetMs
        cancel()
        return t
    }

    fun cancel() { targetMs = null; chapterMode = false; streak = 0 }

    /** Commits automatically when the viewer stops pressing for [idleMs]. */
    fun shouldAutoCommit(nowMs: Long, idleMs: Long = 1_000): Boolean = active && !chapterMode && nowMs - lastPressAt >= idleMs
}
