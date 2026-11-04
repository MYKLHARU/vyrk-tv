package com.vyrk.core.net

/**
 * How much of a byte download to keep. Servers that ignore `Range` answer 200 with the whole
 * file from byte 0: reading that into memory pulled entire videos into the heap (an
 * OutOfMemoryError while playing). So a ranged read keeps at most the bytes asked for, an
 * unranged one at most [maxBytes], and a range that starts past 0 is unusable without a 206.
 */
object ByteRange {
    const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
    private val header = Regex("""bytes=(\d+)-(\d*)""")

    /** Bytes to read from the response, or null to read none (the data would be from the wrong offset). */
    fun readLimit(rangeHeader: String?, responseCode: Int, maxBytes: Long = DEFAULT_MAX_BYTES): Long? {
        val m = rangeHeader?.trim()?.let { header.matchEntire(it) } ?: return maxBytes
        val start = m.groupValues[1].toLongOrNull() ?: return maxBytes
        val end = m.groupValues[2].toLongOrNull()
        if (start > 0 && responseCode != 206) return null
        return if (end != null && end >= start) minOf(end - start + 1, maxBytes) else maxBytes
    }
}
