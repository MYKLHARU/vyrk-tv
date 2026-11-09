package com.vyrk.tv.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.vyrk.core.playback.PlaybackBuffering
import kotlin.test.*

/** Uses the pinned Media3 implementation, not a duplicate of its buffering decisions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackLoadControlsTest {
    private val timeline = SinglePeriodTimeline(120_000_000L, true, false, false, null, MediaItem.EMPTY)
    private val id = PlayerId.UNSET
    private val period = MediaPeriodId(timeline.getUidOfPeriod(0))
    private fun parameters(ms: Long, rebuffer: Boolean) = LoadControl.Parameters(
        id, timeline, period, 0, ms * 1000, 1f, true, rebuffer, C.TIME_UNSET, C.TIME_UNSET)

    @Test fun vodWaitsForARealCushionAfterNetworkDepletion() {
        val old = DefaultLoadControl.Builder().build().also { it.onPrepared(id) }
        val tuned = PlaybackLoadControls.create(PlaybackBuffering.forHeap(512L * PlaybackBuffering.MIB)).also { it.onPrepared(id) }
        try {
            assertTrue(old.shouldStartPlayback(parameters(2000, true)))
            assertFalse(tuned.shouldStartPlayback(parameters(2000, true)))
            assertFalse(tuned.shouldStartPlayback(parameters(9999, true)))
            assertTrue(tuned.shouldStartPlayback(parameters(10_000, true)))
            assertFalse(tuned.shouldStartPlayback(parameters(1000, false)))
            assertTrue(tuned.shouldStartPlayback(parameters(4000, false)))
        } finally { old.onReleased(id); tuned.onReleased(id) }
    }

    @Test fun byteBudgetStopsLoadingAndAllowsPlaybackEvenWhenTimeTargetCannotFit() {
        // Scale down the byte budget so this assertion doesn't allocate a remux-sized heap in CI.
        val config = PlaybackBuffering.forHeap(32L * PlaybackBuffering.MIB).copy(targetBytes = 1024 * 1024)
        val control = PlaybackLoadControls.create(config).also { it.onPrepared(id) }
        val allocator = control.getAllocator(id)
        val blocks = mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        try {
            assertTrue(control.shouldContinueLoading(parameters(1500, true)))
            while (allocator.totalBytesAllocated < config.targetBytes) blocks += allocator.allocate()
            assertFalse(control.shouldContinueLoading(parameters(1500, true)), "Time targets must never bypass the memory budget")
            assertTrue(control.shouldStartPlayback(parameters(1500, true)), "A stream too large to fit ten seconds must not deadlock")
            blocks.forEach { allocator.release(it) }; blocks.clear()
            assertTrue(control.shouldContinueLoading(parameters(1500, true)))
        } finally { blocks.forEach { allocator.release(it) }; control.onReleased(id) }
    }

    @Test fun liveDoesNotWaitForTheVodRefillThreshold() {
        val control = PlaybackLoadControls.create(PlaybackBuffering.forHeap(512L * PlaybackBuffering.MIB, true)).also { it.onPrepared(id) }
        try {
            assertTrue(control.shouldStartPlayback(parameters(1000, false)))
            assertTrue(control.shouldStartPlayback(parameters(2000, true)))
        } finally { control.onReleased(id) }
    }
}
