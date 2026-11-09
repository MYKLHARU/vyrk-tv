package com.vyrk.core.playback

import com.vyrk.core.stream.ReleaseType
import com.vyrk.core.stream.StreamInfo

/** Buffer time is useful only while the compressed samples still fit comfortably in the heap. */
data class PlaybackBuffering(
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
    val resumeMs: Int,
    val targetBytes: Int,
) {
    companion object {
        const val MIB = 1024 * 1024
        private const val SEGMENT = 64 * 1024

        fun forHeap(maxHeapBytes: Long, live: Boolean = false): PlaybackBuffering {
            val heap = maxHeapBytes.takeIf { it > 0 } ?: 128L * MIB
            // Leave at least half the Java heap for UI, demuxing and everything other than samples.
            // Never prioritize buffer time over this byte budget: a remux can need >1 GB/minute.
            val limit = if (live) 32L * MIB else 256L * MIB
            val bytes = (minOf(heap / 2, limit) / SEGMENT * SEGMENT).coerceAtLeast(SEGMENT.toLong()).toInt()
            return if (live) PlaybackBuffering(15_000, 30_000, 1_000, 2_000, bytes)
            else PlaybackBuffering(60_000, 120_000, 4_000, 10_000, bytes)
        }

        /** Requesting a larger heap must not also multiply the artwork cache's memory allowance. */
        fun artworkBytes(maxHeapBytes: Long): Long =
            minOf((maxHeapBytes.takeIf { it > 0 } ?: 128L * MIB) / 8, 32L * MIB).coerceAtLeast(1L * MIB)

        fun startupTimeoutMs(live: Boolean, stream: StreamInfo): Long =
            if (!live && (stream.releaseType == ReleaseType.REMUX || (stream.sizeBytes ?: 0) >= 20_000_000_000L)) 30_000 else 12_000
    }
}
