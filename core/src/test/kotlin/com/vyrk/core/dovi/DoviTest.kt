package com.vyrk.core.dovi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoviTest {
    private fun asset(name: String) = File("src/test/resources/dovi/$name.bin").readBytes()
    /** dovi_tool's files are a start code and the escaped payload, without the 0x7C01 NAL header. */
    private fun payload(bytes: ByteArray) = bytes.copyOfRange(4, bytes.size)
    private fun nal(bytes: ByteArray) = byteArrayOf(0x7C, 0x01) + payload(bytes)

    @Test
    fun rpusWriteBackBitExact() {
        for (name in listOf("fel_orig", "mel_orig", "fel_to_81", "mel_to_81", "profile8", "profile5")) {
            val src = asset(name)
            val rpu = DoviRpu.parseNal(src)
            assertContentEquals(payload(src), rpu.toNal().copyOfRange(2, rpu.toNal().size), name)
        }
    }

    @Test
    fun profilesAndEnhancementLayers() {
        assertEquals(7, DoviRpu.parseNal(asset("fel_orig")).profile)
        assertTrue(DoviRpu.parseNal(asset("fel_orig")).fullEnhancementLayer)
        assertEquals(7, DoviRpu.parseNal(asset("mel_orig")).profile)
        assertEquals(false, DoviRpu.parseNal(asset("mel_orig")).fullEnhancementLayer)
        assertEquals(8, DoviRpu.parseNal(asset("profile8")).profile)
        assertEquals(5, DoviRpu.parseNal(asset("profile5")).profile)
    }

    @Test
    fun melConvertsTo81LikeDoviTool() {
        val rpu = DoviRpu.parseNal(asset("mel_orig"))
        assertTrue(rpu.convertTo81())
        val out = rpu.toNal()
        assertContentEquals(payload(asset("mel_to_81")), out.copyOfRange(2, out.size))
    }

    @Test
    fun felConvertsTo81WithMappingKeptLikeDoviTool() {
        val rpu = DoviRpu.parseNal(asset("fel_orig"))
        assertTrue(rpu.convertTo81(keepMapping = true))
        val out = rpu.toNal()
        assertContentEquals(payload(asset("fel_to_81")), out.copyOfRange(2, out.size))
    }

    @Test
    fun felConvertsTo81WithTheIdentityMapping() {
        val rpu = DoviRpu.parseNal(asset("fel_orig"))
        assertTrue(rpu.convertTo81())
        val again = DoviRpu.parseNal(rpu.toNal())
        assertEquals(8, again.profile)
        assertContentEquals(again.toNal(), rpu.toNal())
        assertTrue(rpu.toNal().size < nal(asset("fel_orig")).size)
    }

    @Test
    fun profile5IsNotConverted() {
        assertEquals(false, DoviRpu.parseNal(asset("profile5")).convertTo81())
    }

    @Test
    fun brokenRpusAreRejected() {
        val bad = nal(asset("mel_orig")).also { it[20] = (it[20].toInt() xor 0x10).toByte() }
        assertFailsWith<IllegalArgumentException> { DoviRpu.parseNal(bad) }
        assertFailsWith<IllegalArgumentException> { DoviRpu.parseNal(byteArrayOf(0x7C, 0x01, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)) }
    }

    private val sc = byteArrayOf(0, 0, 0, 1)
    private fun au(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { acc, n -> acc + sc + n }
    private val slice = byteArrayOf(0x02, 0x01, 0x55, 0x66, 0x00, 0x00, 0x03, 0x01, 0x77)
    private val el = byteArrayOf(0x7E, 0x01, 0x11, 0x22, 0x33)

    @Test
    fun hdr10ModeDropsTheDolbyVisionLayers() {
        val sample = au(slice, el, nal(asset("fel_orig")))
        val stats = Dv7Stats()
        val out = DoviSamples.rewrite(sample, 0, sample.size, Dv7Mode.HDR10, stats)
        assertContentEquals(au(slice), out)
        assertEquals(2, stats.dropped)
    }

    @Test
    fun to81ModeConvertsTheRpuAndDropsTheEnhancementLayer() {
        val sample = au(slice, el, nal(asset("mel_orig")))
        val out = assertNotNull(DoviSamples.rewrite(sample, 0, sample.size, Dv7Mode.TO_81))
        val expected = au(slice, byteArrayOf(0x7C, 0x01) + payload(asset("mel_to_81")))
        assertContentEquals(expected, out)
    }

    @Test
    fun anUnreadableRpuIsDroppedAndOtherSamplesAreLeftAlone() {
        val broken = byteArrayOf(0x7C, 0x01, 0x19, 0x08, 0x09, 0x00, 0x00, 0x03, 0x01, 0x02)
        val sample = au(slice, broken)
        val stats = Dv7Stats()
        assertContentEquals(au(slice), DoviSamples.rewrite(sample, 0, sample.size, Dv7Mode.TO_81, stats))
        assertEquals(1, stats.failed)
        val plain = au(slice, slice)
        assertNull(DoviSamples.rewrite(plain, 0, plain.size, Dv7Mode.TO_81))
        assertNull(DoviSamples.rewrite(sample, 0, sample.size, Dv7Mode.PASS))
    }

    @Test
    fun startCodesWithThreeAndFourBytesSplitTheSameWay() {
        val sample = byteArrayOf(0, 0, 1) + slice + byteArrayOf(0, 0, 0, 1) + el + byteArrayOf(0, 0, 1) + slice
        val out = DoviSamples.rewrite(sample, 0, sample.size, Dv7Mode.HDR10)
        assertContentEquals(au(slice, slice), out)
    }

    @Test
    fun modeFollowsTheDecoderAndTheScreen() {
        assertEquals(Dv7Mode.PASS, Dv7.mode(p7Decoder = true, p8Decoder = true, displayDv = true, asHdr10 = false))
        assertEquals(Dv7Mode.TO_81, Dv7.mode(p7Decoder = false, p8Decoder = true, displayDv = null, asHdr10 = false))
        assertEquals(Dv7Mode.HDR10, Dv7.mode(p7Decoder = false, p8Decoder = true, displayDv = false, asHdr10 = false))
        assertEquals(Dv7Mode.HDR10, Dv7.mode(p7Decoder = false, p8Decoder = false, displayDv = true, asHdr10 = false))
        assertEquals(Dv7Mode.HDR10, Dv7.mode(p7Decoder = true, p8Decoder = true, displayDv = true, asHdr10 = true))
    }

    @Test
    fun codecsStrings() {
        assertEquals(7, Dv7.profile("dvhe.07.06"))
        assertEquals(8, Dv7.profile("hvc1.2.4.L153, dvh1.08.06"))
        assertNull(Dv7.profile("hvc1.2.4.L153"))
        assertEquals("dvhe.08.06", Dv7.codecsAs81("dvhe.07.06"))
        assertEquals("dvh1.08.09", Dv7.codecsAs81("dvh1.07.09"))
    }
}
