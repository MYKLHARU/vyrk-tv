package com.vyrk.core.playback

import com.vyrk.core.addon.Stream
import com.vyrk.core.stream.StreamParser
import kotlin.test.*

class PlaybackBufferingTest {
    private val mib = PlaybackBuffering.MIB.toLong()

    @Test fun compressedSamplesAndArtworkLeaveHeapHeadroomOnSmallAndLargeDevices() {
        for (heapMiB in listOf(32, 64, 128, 192, 256, 384, 512, 576, 1024, 4096)) {
            val heap = heapMiB * mib
            for (live in listOf(false, true)) {
                val c = PlaybackBuffering.forHeap(heap, live)
                assertTrue(c.targetBytes <= heap / 2)
                assertTrue(c.targetBytes <= 256 * mib)
                assertTrue(c.targetBytes + PlaybackBuffering.artworkBytes(heap) <= heap * 5 / 8)
                assertTrue(c.minMs >= c.resumeMs && c.maxMs >= c.minMs)
            }
        }
        assertEquals(256 * mib, PlaybackBuffering.forHeap(576 * mib).targetBytes.toLong())
        assertEquals(32 * mib, PlaybackBuffering.artworkBytes(576 * mib))
    }

    @Test fun vodRebuildsACushionInsteadOfImmediatelyRepeatingAStall() {
        val c = PlaybackBuffering.forHeap(512 * mib)
        assertEquals(4_000, c.startMs)
        assertEquals(10_000, c.resumeMs)
        assertTrue(c.maxMs >= 120_000)
        // At 120 Mb/s, the old ~137.6 MiB audio/video/text target holds under ten seconds.
        val oldBytes = (2000L + 200 + 2) * 65_536
        val newAheadMs = c.targetBytes * 8_000.0 / 120_000_000
        assertTrue(oldBytes * 8_000.0 / 120_000_000 < c.resumeMs)
        assertTrue(newAheadMs > c.resumeMs)
    }

    @Test fun livePlaybackKeepsShortStartAndResumeTimes() {
        val c = PlaybackBuffering.forHeap(1024 * mib, live = true)
        assertEquals(1000, c.startMs)
        assertEquals(2000, c.resumeMs)
        assertEquals(32 * mib, c.targetBytes.toLong())
    }

    @Test fun remuxStartupHasTimeToBuildItsBufferWithoutSlowingNormalSourceRecovery() {
        fun info(title: String) = StreamParser.parse(Stream(url = "https://synthetic.invalid/video", title = title))
        assertEquals(30_000, PlaybackBuffering.startupTimeoutMs(false, info("Film.2160p.BluRay.REMUX.HEVC 65 GB")))
        assertEquals(30_000, PlaybackBuffering.startupTimeoutMs(false, info("Film.2160p 55 GB")))
        assertEquals(12_000, PlaybackBuffering.startupTimeoutMs(false, info("Film.720p 2 GB")))
        assertEquals(12_000, PlaybackBuffering.startupTimeoutMs(true, info("Film.2160p.REMUX 65 GB")))
    }
}
