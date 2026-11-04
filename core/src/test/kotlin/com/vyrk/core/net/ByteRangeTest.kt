package com.vyrk.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ByteRangeTest {
    @Test
    fun rangedReadsKeepOnlyWhatWasAskedFor() {
        assertEquals(65_536L, ByteRange.readLimit("bytes=0-65535", 206))
        // Server ignored Range but we asked from 0: the first bytes are still the right ones.
        assertEquals(65_536L, ByteRange.readLimit("bytes=0-65535", 200))
        assertEquals(4096L, ByteRange.readLimit("bytes=1000-5095", 206))
    }

    @Test
    fun ignoredRangePastZeroIsUnusable() {
        assertNull(ByteRange.readLimit("bytes=1000-5095", 200))
    }

    @Test
    fun openOrMissingRangesAreCapped() {
        assertEquals(ByteRange.DEFAULT_MAX_BYTES, ByteRange.readLimit(null, 200))
        assertEquals(ByteRange.DEFAULT_MAX_BYTES, ByteRange.readLimit("bytes=0-", 206))
        assertEquals(ByteRange.DEFAULT_MAX_BYTES, ByteRange.readLimit("bytes=0-999999999", 206))
        assertEquals(ByteRange.DEFAULT_MAX_BYTES, ByteRange.readLimit("items=0-9", 200))
    }
}
