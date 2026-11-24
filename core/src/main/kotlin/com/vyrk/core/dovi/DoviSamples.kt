package com.vyrk.core.dovi

/**
 * What to do with a Dolby Vision profile 7 file (Blu-ray remuxes: an HDR10 base layer, an
 * enhancement layer in NAL type 63 and the RPU in NAL type 62).
 */
enum class Dv7Mode {
    /** The decoder handles profile 7 itself. */
    PASS,
    /** Rewrite each RPU to profile 8.1 and drop the enhancement layer: Dolby Vision on decoders that only know profile 8. */
    TO_81,
    /** Drop the RPU and the enhancement layer: plain HDR10 through the HEVC decoder. */
    HDR10,
}

object Dv7 {
    /**
     * The mode for a profile 7 file. [p7Decoder]/[p8Decoder]: a Dolby Vision decoder declares that
     * profile; [displayDv]: the screen takes Dolby Vision (null = unknown); [asHdr10]: the user
     * plays Dolby Vision as HDR10.
     */
    fun mode(p7Decoder: Boolean, p8Decoder: Boolean, displayDv: Boolean?, asHdr10: Boolean): Dv7Mode = when {
        asHdr10 -> Dv7Mode.HDR10
        displayDv == false -> Dv7Mode.HDR10
        p7Decoder -> Dv7Mode.PASS
        p8Decoder -> Dv7Mode.TO_81
        else -> Dv7Mode.HDR10
    }

    /** The profile from a codecs string such as "dvhe.07.06"; null when it isn't Dolby Vision. */
    fun profile(codecs: String?): Int? = codecs?.split(',')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("dvhe.") || it.startsWith("dvh1.") }
        ?.split('.')?.getOrNull(1)?.toIntOrNull()

    /** "dvhe.07.06" → "dvhe.08.06": the codecs string after [Dv7Mode.TO_81]. */
    fun codecsAs81(codecs: String): String = codecs.split(',').joinToString(",") { c ->
        val p = c.trim().split('.')
        if (p.size >= 3 && (p[0] == "dvhe" || p[0] == "dvh1") && p[1] == "07") "${p[0]}.08.${p[2]}" else c
    }
}

/** Counts what [DoviSamples.rewrite] did, for the diagnostics log. */
class Dv7Stats {
    var samples = 0L; var converted = 0L; var dropped = 0L; var failed = 0L
    override fun toString() = "samples=$samples rpu=$converted dropped=$dropped failed=$failed"
}

object DoviSamples {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Rewrites one access unit in Annex-B form (start-code delimited NAL units, as Media3's MKV and
     * MP4 extractors hand them over). Enhancement-layer NALs (63) are dropped; RPU NALs (62) are
     * converted ([Dv7Mode.TO_81]) or dropped ([Dv7Mode.HDR10]). An RPU that can't be parsed is
     * dropped, leaving that frame HDR10. Returns null when the sample has neither.
     */
    fun rewrite(data: ByteArray, from: Int, to: Int, mode: Dv7Mode, stats: Dv7Stats? = null): ByteArray? {
        if (mode == Dv7Mode.PASS) return null
        stats?.let { it.samples++ }
        val nals = split(data, from, to)
        if (nals.none { type(data, it) == 62 || type(data, it) == 63 }) return null
        val out = java.io.ByteArrayOutputStream(to - from)
        for (n in nals) {
            when (type(data, n)) {
                63 -> stats?.let { it.dropped++ }
                62 -> if (mode == Dv7Mode.TO_81) {
                    val nal = runCatching { DoviRpu.parseNal(data, n.first, n.last + 1).takeIf { it.convertTo81() }?.toNal() }.getOrNull()
                    if (nal != null) { out.write(START_CODE); out.write(nal); stats?.let { it.converted++ } } else stats?.let { it.failed++ }
                } else stats?.let { it.dropped++ }
                else -> { out.write(START_CODE); out.write(data, n.first, n.last - n.first + 1) }
            }
        }
        return out.toByteArray()
    }

    private fun type(data: ByteArray, nal: IntRange) = if (nal.isEmpty()) -1 else (data[nal.first].toInt() shr 1) and 0x3F

    /** NAL unit ranges (without start codes or the zero bytes before them). */
    internal fun split(data: ByteArray, from: Int, to: Int): List<IntRange> {
        val starts = mutableListOf<Int>() // index of the first NAL byte
        val codeAt = mutableListOf<Int>() // index where the start code (with leading zeros) begins
        var i = from
        while (i + 2 < to) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                var z = i
                while (z > from && data[z - 1].toInt() == 0 && (codeAt.isEmpty() || z - 1 >= starts.last())) z--
                codeAt += z; starts += i + 3
                i += 3
            } else i++
        }
        return starts.mapIndexed { k, s -> s until (codeAt.getOrNull(k + 1) ?: to) }.filter { !it.isEmpty() }
    }
}
