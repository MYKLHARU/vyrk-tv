package com.vyrk.core.library

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Port of stremio-watched-bitfield `BitField8`: little-endian bits, zlib + base64 when serialised. */
class BitField8 private constructor(var length: Int, internal var values: ByteArray) {

    fun get(i: Int): Boolean {
        val index = i / 8
        if (index >= values.size) return false
        return (values[index].toInt() shr (i % 8)) and 1 != 0
    }

    fun set(i: Int, v: Boolean) {
        val index = i / 8
        val mask = 1 shl (i % 8)
        if (index >= values.size) {
            values = values.copyOf(index + 1)
            length = values.size * 8
        }
        values[index] = if (v) (values[index].toInt() or mask).toByte() else (values[index].toInt() and mask.inv()).toByte()
    }

    fun lastIndexOf(v: Boolean): Int? {
        for (i in length - 1 downTo 0) if (get(i) == v) return i
        return null
    }

    fun encode(): String {
        val deflater = Deflater(6)
        deflater.setInput(values)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    override fun equals(other: Any?): Boolean =
        other is BitField8 && other.length == length && other.values.contentEquals(values)

    override fun hashCode(): Int = 31 * length + values.contentHashCode()

    companion object {
        fun new(length: Int): BitField8 = BitField8(length, ByteArray(bytesFor(length)))

        fun withValues(values: ByteArray, length: Int?): BitField8 {
            val len = length ?: (values.size * 8)
            val bytes = bytesFor(len)
            val v = if (bytes > values.size) values.copyOf(bytes) else values.copyOf()
            return BitField8(len, v)
        }

        fun decode(encoded: String, length: Int? = null): BitField8 {
            val compressed = Base64.getDecoder().decode(encoded)
            val inflater = Inflater()
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inflater.end()
            return withValues(out.toByteArray(), length)
        }

        private fun bytesFor(length: Int) = (length + 7) / 8
    }
}

/** `{anchorVideoId}:{anchorLength}:{bitfield8}`; the anchor ID may itself contain colons. */
data class WatchedField(val anchorVideo: String, val anchorLength: Int, val bitfield: BitField8) {
    override fun toString(): String = "$anchorVideo:$anchorLength:${bitfield.encode()}"

    companion object {
        fun parse(s: String): WatchedField {
            val parts = s.split(':').toMutableList()
            require(parts.size >= 3) { "Not enough components" }
            val buf = parts.removeAt(parts.lastIndex)
            val len = parts.removeAt(parts.lastIndex).toInt()
            return WatchedField(parts.joinToString(":"), len, BitField8.decode(buf))
        }
    }
}

/** Port of `WatchedBitField`: a bitfield over an ordered list of video IDs. */
class WatchedBitField private constructor(private val bitfield: BitField8, val videoIds: List<String>) {

    fun get(idx: Int): Boolean = bitfield.get(idx)
    fun set(idx: Int, v: Boolean) = bitfield.set(idx, v)

    fun getVideo(videoId: String): Boolean {
        val pos = videoIds.indexOf(videoId)
        return pos >= 0 && bitfield.get(pos)
    }

    fun setVideo(videoId: String, v: Boolean) {
        val pos = videoIds.indexOf(videoId)
        if (pos >= 0) bitfield.set(pos, v)
    }

    fun toField(): WatchedField {
        val last = bitfield.lastIndexOf(true) ?: 0
        return WatchedField(videoIds.getOrNull(last) ?: "undefined", last + 1, bitfield)
    }

    override fun toString(): String = toField().toString()

    companion object {
        fun fromArray(arr: List<Boolean>, videoIds: List<String>): WatchedBitField {
            val bf = BitField8.new(videoIds.size)
            arr.forEachIndexed { i, v -> bf.set(i, v) }
            return WatchedBitField(bf, videoIds)
        }

        fun withVideos(field: WatchedField, videoIds: List<String>): WatchedBitField {
            val anchorIdx = videoIds.indexOf(field.anchorVideo)
            if (anchorIdx < 0) return WatchedBitField(BitField8.new(videoIds.size), videoIds)
            val offset = field.anchorLength - anchorIdx - 1
            val bf = BitField8.withValues(field.bitfield.values, videoIds.size)
            if (offset == 0) return WatchedBitField(bf, videoIds)
            val resized = WatchedBitField(BitField8.new(videoIds.size), videoIds)
            for (i in videoIds.indices) {
                val prev = i + offset
                if (prev >= 0 && prev < bf.length) resized.set(i, bf.get(prev))
            }
            return resized
        }

        fun constructAndResize(serialized: String, videoIds: List<String>): WatchedBitField =
            withVideos(WatchedField.parse(serialized), videoIds)
    }
}
