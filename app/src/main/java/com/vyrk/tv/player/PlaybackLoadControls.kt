package com.vyrk.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.vyrk.core.playback.PlaybackBuffering

@OptIn(UnstableApi::class)
object PlaybackLoadControls {
    fun create(config: PlaybackBuffering, allocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(config.minMs, config.maxMs, config.startMs, config.resumeMs)
            .setTargetBufferBytes(config.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
}
