package com.vyrk.core.dovi

/** MSB-first bit reader over [data] up to [endBit]. Throws [IllegalStateException] past the end. */
internal class BitReader(private val data: ByteArray, private val endBit: Long = data.size * 8L) {
    var pos = 0L
        private set
    val available: Long get() = endBit - pos
    val byteAligned: Boolean get() = pos % 8 == 0L

    fun bit(): Boolean {
        check(pos < endBit) { "read past end" }
        val b = (data[(pos ushr 3).toInt()].toInt() shr (7 - (pos and 7).toInt())) and 1
        pos++
        return b == 1
    }

    fun bits(n: Int): Long {
        require(n in 0..64)
        var v = 0L
        repeat(n) { v = (v shl 1) or (if (bit()) 1L else 0L) }
        return v
    }

    /** Unsigned Exp-Golomb. */
    fun ue(): Long {
        var zeros = 0
        while (!bit()) { zeros++; check(zeros < 63) { "bad ue" } }
        return if (zeros == 0) 0L else (1L shl zeros) - 1 + bits(zeros)
    }

    /** Signed Exp-Golomb. */
    fun se(): Long {
        val k = ue()
        return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
    }

    /** The next [n] bits, kept as they are (for blocks VYRK passes through untouched). */
    fun raw(n: Long): RawBits {
        val out = ByteArray(((n + 7) / 8).toInt())
        for (i in 0 until n) if (bit()) out[(i ushr 3).toInt()] = (out[(i ushr 3).toInt()].toInt() or (0x80 ushr (i and 7).toInt())).toByte()
        return RawBits(out, n)
    }
}

/** A run of bits, MSB first. */
internal class RawBits(val bytes: ByteArray, val length: Long)

internal class BitWriter {
    private var buf = ByteArray(512)
    var pos = 0L
        private set

    fun bit(b: Boolean) {
        val i = (pos ushr 3).toInt()
        if (i >= buf.size) buf = buf.copyOf(buf.size * 2)
        if (b) buf[i] = (buf[i].toInt() or (0x80 ushr (pos and 7).toInt())).toByte()
        pos++
    }

    fun bits(n: Int, v: Long) { for (i in n - 1 downTo 0) bit((v ushr i) and 1L == 1L) }

    fun ue(v: Long) {
        val x = v + 1
        val len = 64 - java.lang.Long.numberOfLeadingZeros(x)
        repeat(len - 1) { bit(false) }
        bits(len, x)
    }

    fun se(v: Long) = ue(if (v > 0) 2 * v - 1 else -2 * v)

    fun raw(r: RawBits) { for (i in 0 until r.length) bit((r.bytes[(i ushr 3).toInt()].toInt() shr (7 - (i and 7).toInt())) and 1 == 1) }

    fun byteAlign() { while (pos % 8 != 0L) bit(false) }

    fun bytes(): ByteArray = buf.copyOf(((pos + 7) / 8).toInt())
}

/** CRC-32/MPEG-2: polynomial 0x04C11DB7, initial 0xFFFFFFFF, no reflection, no final XOR. */
internal object Crc32Mpeg2 {
    private val table = IntArray(256) { n ->
        var c = n shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    fun of(data: ByteArray, from: Int, to: Int): Long {
        var crc = -1
        for (i in from until to) crc = (crc shl 8) xor table[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        return crc.toLong() and 0xFFFFFFFFL
    }
}

/** HEVC emulation prevention: the 0x03 bytes that keep a NAL payload from looking like a start code. */
internal object Emulation {
    fun remove(data: ByteArray, from: Int, to: Int): ByteArray {
        val len = to - from
        if (len <= 2) return data.copyOfRange(from, to)
        val out = java.io.ByteArrayOutputStream(len)
        out.write(data[from].toInt()); out.write(data[from + 1].toInt())
        for (i in from + 2 until to) {
            if (!(data[i - 2].toInt() == 0 && data[i - 1].toInt() == 0 && data[i].toInt() == 3)) out.write(data[i].toInt())
        }
        return out.toByteArray()
    }

    fun add(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var zeros = 0
        for ((i, b) in data.withIndex()) {
            val v = b.toInt() and 0xFF
            // Same rule as dovi_tool: never at the first three bytes, which start with the 0x19 prefix.
            if (i > 2 && zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}
