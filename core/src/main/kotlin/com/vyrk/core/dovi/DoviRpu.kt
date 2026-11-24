package com.vyrk.core.dovi

/**
 * A Dolby Vision RPU (the per-frame metadata NAL, HEVC type 62), parsed far enough to convert
 * profile 7 to profile 8.1 and write it back bit-exact. Ported from quietvoid's dovi_tool
 * (dolby_vision crate, MIT); the extension metadata blocks are carried through as raw bits.
 */
class DoviRpu private constructor(
    private val header: Header,
    private val mapping: Mapping?,
    private val dm: DmData?,
    private val remaining: RawBits?,
    private val trailingZeroes: Int,
) {
    /** 4, 5, 7 or 8; 0 when unknown. */
    val profile: Int get() = header.profile

    /** Profile 7 with a full enhancement layer (FEL), as opposed to the minimal one (MEL). */
    val fullEnhancementLayer: Boolean get() = mapping?.nlq?.isMel == false

    /**
     * Profile 7 → 8.1 (dovi_tool mode 2): the residual enhancement layer is dropped; with a full
     * enhancement layer the mapping becomes the identity (the base layer is the picture), with a
     * minimal one the mapping is kept ([keepMapping] keeps it in both cases, dovi_tool mode 4).
     * Returns false for profiles it can't convert.
     */
    fun convertTo81(keepMapping: Boolean = false): Boolean {
        if (profile != 7 && profile != 8) return false
        val fel = fullEnhancementLayer && !keepMapping
        header.elSpatialResampling = false
        header.disableResidual = true
        mapping?.apply {
            nlqMethod = null; nlqPivots = null; nlq = null
            numXPartitions = 0; numYPartitions = 0
            if (fel) curves.forEach { it.setIdentity() }
        }
        dm?.setP81Coefficients()
        return true
    }

    /** The RPU as a complete HEVC NAL unit (header 0x7C01, emulation prevention applied), no start code. */
    fun toNal(): ByteArray {
        val w = BitWriter()
        w.bits(8, PREFIX.toLong())
        header.write(w)
        if (!header.usePrev) mapping?.write(w, header)
        if (header.dmPresent) dm?.write(w)
        remaining?.let { w.raw(it) }
        w.byteAlign()
        val body = w.bytes()
        val crc = Crc32Mpeg2.of(body, 1, body.size)
        val full = body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(), FINAL_BYTE.toByte()) + ByteArray(trailingZeroes)
        return byteArrayOf(0x7C, 0x01) + Emulation.add(full)
    }

    companion object {
        private const val PREFIX = 25
        private const val FINAL_BYTE = 0x80
        private const val CRC_TERMINATOR_BITS = 40L
        private const val CMV40_MIN_BITS = 56L

        /**
         * Parses an RPU from a type-62 NAL unit: [from] points at the NAL header (0x7C 0x01) or
         * straight at the 0x19 prefix. Throws when the data isn't a valid RPU.
         */
        fun parseNal(data: ByteArray, from: Int = 0, to: Int = data.size): DoviRpu {
            var start = from
            while (start < to && data[start].toInt() == 0) start++
            if (start < to && data[start].toInt() == 1) start++ // after a start code
            if (to - start >= 2 && data[start].toInt() and 0xFF == 0x7C && data[start + 1].toInt() == 1) start += 2
            val bytes = Emulation.remove(data, start, to)
            require(bytes.size >= 7 && bytes[0].toInt() == PREFIX) { "not an RPU" }
            val zeroes = bytes.reversed().takeWhile { it.toInt() == 0 }.size
            val end = bytes.size - zeroes
            require(end >= 7 && bytes[end - 1].toInt() and 0xFF == FINAL_BYTE) { "bad RPU end" }
            val crcStart = end - 5
            val expected = Crc32Mpeg2.of(bytes, 1, crcStart)
            val received = ((bytes[crcStart].toLong() and 0xFF) shl 24) or ((bytes[crcStart + 1].toLong() and 0xFF) shl 16) or
                ((bytes[crcStart + 2].toLong() and 0xFF) shl 8) or (bytes[crcStart + 3].toLong() and 0xFF)
            require(expected == received) { "RPU CRC mismatch" }

            val r = BitReader(bytes, end * 8L)
            r.bits(8)
            val header = Header.parse(r)
            header.validate()
            val mapping = if (!header.usePrev) Mapping.parse(r, header) else null
            val dm = if (header.dmPresent) DmData.parse(r, header) else null
            while (!r.byteAligned) require(!r.bit()) { "rpu_alignment_zero_bit" }
            val remaining = if (r.available > CRC_TERMINATOR_BITS) r.raw(r.available - CRC_TERMINATOR_BITS) else null
            require(r.available == CRC_TERMINATOR_BITS) { "bad RPU length" }
            return DoviRpu(header, mapping, dm, remaining, zeroes)
        }

        internal fun cmv40Follows(r: BitReader) = r.available >= CMV40_MIN_BITS
    }

    private class Header(
        val rpuType: Int, val rpuFormat: Int, val vdrRpuProfile: Int, val vdrRpuLevel: Int, val seqInfo: Boolean,
    ) {
        var chromaResamplingExplicit = false
        var coefDataType = 0
        var log2Denom = 0L
        var normalizedIdc = 0
        var blFullRange = false
        var blBitDepthMinus8 = 0L
        /** el_bit_depth_minus8 as coded: the low byte is the depth, the rest ext_mapping_idc. */
        var elBitDepthCoded = 0L
        var vdrBitDepthMinus8 = 0L
        var spatialResampling = false
        var reserved3 = 0
        var elSpatialResampling = false
        var disableResidual = false
        var dmPresent = false
        var usePrev = false
        var prevId = 0L

        val hasBitDepths get() = rpuFormat and 0x700 == 0
        val elBitDepthMinus8 get() = elBitDepthCoded and 0xFF
        val coefLength: Int get() = if (coefDataType == 0) log2Denom.toInt() else 32
        val blBitDepth get() = (blBitDepthMinus8 + 8).toInt()

        val profile: Int get() = when (vdrRpuProfile) {
            0 -> if (blFullRange) 5 else 0
            1 -> if (elSpatialResampling && !disableResidual) (if (vdrBitDepthMinus8 == 4L) 7 else 4) else 8
            else -> 0
        }

        fun validate() {
            require(rpuType == 2) { "rpu_type" }
            require(vdrRpuLevel == 0) { "vdr_rpu_level" }
            require(blBitDepthMinus8 == 2L && elBitDepthMinus8 == 2L) { "bit depth" }
            require(vdrBitDepthMinus8 <= 6 && log2Denom <= 23) { "header range" }
            require(coefDataType == 0 || coefDataType == 1) { "coefficient_data_type" }
        }

        fun write(w: BitWriter) {
            w.bits(6, rpuType.toLong()); w.bits(11, rpuFormat.toLong()); w.bits(4, vdrRpuProfile.toLong()); w.bits(4, vdrRpuLevel.toLong())
            w.bit(seqInfo)
            if (seqInfo) {
                w.bit(chromaResamplingExplicit)
                w.bits(2, coefDataType.toLong())
                if (coefDataType == 0) w.ue(log2Denom)
                w.bits(2, normalizedIdc.toLong())
                w.bit(blFullRange)
                if (hasBitDepths) {
                    w.ue(blBitDepthMinus8); w.ue(elBitDepthCoded); w.ue(vdrBitDepthMinus8)
                    w.bit(spatialResampling); w.bits(3, reserved3.toLong()); w.bit(elSpatialResampling); w.bit(disableResidual)
                }
            }
            w.bit(dmPresent); w.bit(usePrev)
            if (usePrev) w.ue(prevId)
        }

        companion object {
            fun parse(r: BitReader): Header {
                val h = Header(r.bits(6).toInt(), r.bits(11).toInt(), r.bits(4).toInt(), r.bits(4).toInt(), r.bit())
                require(h.rpuType == 2) { "rpu_type" }
                if (h.seqInfo) {
                    h.chromaResamplingExplicit = r.bit()
                    h.coefDataType = r.bits(2).toInt()
                    if (h.coefDataType == 0) h.log2Denom = r.ue()
                    h.normalizedIdc = r.bits(2).toInt()
                    h.blFullRange = r.bit()
                    if (h.hasBitDepths) {
                        h.blBitDepthMinus8 = r.ue(); h.elBitDepthCoded = r.ue(); h.vdrBitDepthMinus8 = r.ue()
                        h.spatialResampling = r.bit(); h.reserved3 = r.bits(3).toInt(); h.elSpatialResampling = r.bit(); h.disableResidual = r.bit()
                    }
                }
                h.dmPresent = r.bit(); h.usePrev = r.bit()
                if (h.usePrev) h.prevId = r.ue()
                return h
            }
        }
    }

    private sealed interface Piece {
        fun write(w: BitWriter, h: Header)

        class Poly(val orderMinus1: Long, val linearInterp: Boolean, val coefInt: LongArray, val coef: LongArray) : Piece {
            override fun write(w: BitWriter, h: Header) {
                w.ue(0); w.ue(orderMinus1)
                if (orderMinus1 == 0L) w.bit(linearInterp)
                for (j in coef.indices) { if (h.coefDataType == 0) w.se(coefInt[j]); w.bits(h.coefLength, coef[j]) }
            }
        }

        class Mmr(val orderMinus1: Int, val constInt: Long, val const: Long, val coefInt: Array<LongArray>, val coef: Array<LongArray>) : Piece {
            override fun write(w: BitWriter, h: Header) {
                w.ue(1); w.bits(2, orderMinus1.toLong())
                if (h.coefDataType == 0) w.se(constInt)
                w.bits(h.coefLength, const)
                for (j in coef.indices) for (k in 0 until MMR_COEFFS) { if (h.coefDataType == 0) w.se(coefInt[j][k]); w.bits(h.coefLength, coef[j][k]) }
            }
        }

        companion object {
            const val MMR_COEFFS = 7

            fun parse(r: BitReader, h: Header): Piece = when (r.ue()) {
                0L -> {
                    val order = r.ue()
                    require(order <= 1) { "poly order" }
                    val interp = order == 0L && r.bit()
                    require(!interp) { "polynomial interpolation" }
                    val n = (order + 2).toInt()
                    val ci = LongArray(n); val c = LongArray(n)
                    for (j in 0 until n) { if (h.coefDataType == 0) ci[j] = r.se(); c[j] = r.bits(h.coefLength) }
                    Poly(order, interp, ci, c)
                }
                1L -> {
                    val order = r.bits(2).toInt()
                    require(order <= 2) { "mmr order" }
                    val constInt = if (h.coefDataType == 0) r.se() else 0L
                    val const = r.bits(h.coefLength)
                    val ci = Array(order + 1) { LongArray(MMR_COEFFS) }; val c = Array(order + 1) { LongArray(MMR_COEFFS) }
                    for (j in 0..order) for (k in 0 until MMR_COEFFS) { if (h.coefDataType == 0) ci[j][k] = r.se(); c[j][k] = r.bits(h.coefLength) }
                    Mmr(order, constInt, const, ci, c)
                }
                else -> throw IllegalArgumentException("mapping_idc")
            }
        }
    }

    private class Curve(var pivots: LongArray, val pieces: MutableList<Piece> = mutableListOf()) {
        fun setIdentity() {
            pivots = longArrayOf(0, 1023)
            pieces.clear()
            pieces += Piece.Poly(0, false, longArrayOf(0, 1), longArrayOf(0, 0))
        }
    }

    private class Nlq(val offset: LongArray, val inMaxInt: LongArray, val inMax: LongArray, val slopeInt: LongArray, val slope: LongArray, val thrInt: LongArray, val thr: LongArray) {
        val isMel get() = offset.all { it == 0L } && inMaxInt.all { it == 1L } && inMax.all { it == 0L } &&
            slopeInt.all { it == 0L } && slope.all { it == 0L } && thrInt.all { it == 0L } && thr.all { it == 0L }

        fun write(w: BitWriter, h: Header) {
            for (c in 0 until 3) {
                w.bits((h.elBitDepthMinus8 + 8).toInt(), offset[c])
                if (h.coefDataType == 0) w.ue(inMaxInt[c])
                w.bits(h.coefLength, inMax[c])
                if (h.coefDataType == 0) w.ue(slopeInt[c])
                w.bits(h.coefLength, slope[c])
                if (h.coefDataType == 0) w.ue(thrInt[c])
                w.bits(h.coefLength, thr[c])
            }
        }

        companion object {
            fun parse(r: BitReader, h: Header): Nlq {
                val a = Array(7) { LongArray(3) }
                for (c in 0 until 3) {
                    a[0][c] = r.bits((h.elBitDepthMinus8 + 8).toInt())
                    if (h.coefDataType == 0) a[1][c] = r.ue()
                    a[2][c] = r.bits(h.coefLength)
                    if (h.coefDataType == 0) a[3][c] = r.ue()
                    a[4][c] = r.bits(h.coefLength)
                    if (h.coefDataType == 0) a[5][c] = r.ue()
                    a[6][c] = r.bits(h.coefLength)
                }
                return Nlq(a[0], a[1], a[2], a[3], a[4], a[5], a[6])
            }
        }
    }

    private class Mapping(val rpuId: Long, val colorSpace: Long, val chromaFormat: Long, val curves: List<Curve>) {
        var nlqMethod: Int? = null
        var nlqPivots: LongArray? = null
        var numXPartitions = 0L
        var numYPartitions = 0L
        var nlq: Nlq? = null

        fun write(w: BitWriter, h: Header) {
            w.ue(rpuId); w.ue(colorSpace); w.ue(chromaFormat)
            for (c in curves) { w.ue((c.pivots.size - 2).toLong()); c.pivots.forEach { w.bits(h.blBitDepth, it) } }
            if (h.hasBitDepths && !h.disableResidual) {
                nlqMethod?.let { w.bits(3, it.toLong()) }
                nlqPivots?.forEach { w.bits(h.blBitDepth, it) }
            }
            w.ue(numXPartitions); w.ue(numYPartitions)
            for (c in curves) c.pieces.forEach { it.write(w, h) }
            nlq?.write(w, h)
        }

        companion object {
            fun parse(r: BitReader, h: Header): Mapping {
                val m = Mapping(r.ue(), r.ue(), r.ue(), List(3) {
                    val n = r.ue()
                    require(n < 8) { "pivots" }
                    Curve(LongArray((n + 2).toInt()) { r.bits(h.blBitDepth) })
                })
                if (h.hasBitDepths && !h.disableResidual) {
                    m.nlqMethod = r.bits(3).toInt().also { require(it == 0) { "nlq_method_idc" } }
                    m.nlqPivots = LongArray(2) { r.bits(h.blBitDepth) }
                }
                m.numXPartitions = r.ue(); m.numYPartitions = r.ue()
                for (c in m.curves) repeat(c.pivots.size - 1) { c.pieces += Piece.parse(r, h) }
                if (m.nlqMethod != null) m.nlq = Nlq.parse(r, h)
                return m
            }
        }
    }

    /** One extension metadata block: its level and payload (ext_block_length bytes), kept as raw bits. */
    private class ExtBlock(val lengthBytes: Long, val level: Int, val payload: RawBits)

    private class ExtBlocks(val blocks: List<ExtBlock>) {
        fun write(w: BitWriter) {
            w.ue(blocks.size.toLong()); w.byteAlign()
            for (b in blocks) { w.ue(b.lengthBytes); w.bits(8, b.level.toLong()); w.raw(b.payload) }
        }

        companion object {
            fun parse(r: BitReader): ExtBlocks {
                val n = r.ue()
                require(n <= 255) { "ext blocks" }
                while (!r.byteAligned) require(!r.bit()) { "dm_alignment_zero_bit" }
                return ExtBlocks(List(n.toInt()) {
                    val len = r.ue()
                    require(len in 1..4096) { "ext_block_length" }
                    ExtBlock(len, r.bits(8).toInt(), r.raw(len * 8))
                })
            }
        }
    }

    private class DmData(val compressed: Boolean, val ids: LongArray, val fields: LongArray?, val cmv29: ExtBlocks, val cmv40: ExtBlocks?) {
        fun setP81Coefficients() {
            val f = fields ?: return
            P81_YCC_TO_RGB.copyInto(f, 0)
            P81_RGB_TO_LMS.copyInto(f, 12)
            f[SIGNAL_COLOR_SPACE] = 0
        }

        fun write(w: BitWriter) {
            ids.forEach { w.ue(it) }
            if (!compressed) fields?.forEachIndexed { i, v -> w.bits(WIDTHS[i], v) }
            cmv29.write(w)
            cmv40?.write(w)
        }

        companion object {
            /** ycc_to_rgb coef ×9, offset ×3, rgb_to_lms ×9, eotf, eotf params ×3, bit depth, colour space, chroma format, full range, min/max PQ, diagonal. */
            val WIDTHS = IntArray(9) { 16 } + IntArray(3) { 32 } + IntArray(9) { 16 } + intArrayOf(16, 16, 16, 32, 5, 2, 2, 2, 12, 12, 10)
            const val SIGNAL_COLOR_SPACE = 26
            private fun s16(v: Int) = v.toLong() and 0xFFFF
            val P81_YCC_TO_RGB = longArrayOf(s16(9574), 0, s16(13802), s16(9574), s16(-1540), s16(-5348), s16(9574), s16(17610), 0, 16777216, 134217728, 134217728)
            val P81_RGB_TO_LMS = longArrayOf(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962)

            fun parse(r: BitReader, h: Header): DmData {
                val compressed = h.reserved3 == 1
                val ids = LongArray(3) { r.ue() }
                val fields = if (compressed) null else LongArray(WIDTHS.size) { r.bits(WIDTHS[it]) }
                val cmv29 = ExtBlocks.parse(r)
                val cmv40 = if (cmv40Follows(r)) ExtBlocks.parse(r) else null
                return DmData(compressed, ids, fields, cmv29, cmv40)
            }
        }
    }
}
