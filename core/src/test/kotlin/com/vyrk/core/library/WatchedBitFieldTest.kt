package com.vyrk.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Vectors copied from stremio-core stremio-watched-bitfield/src/{watched_bitfield,bitfield8}.rs. */
class WatchedBitFieldTest {

    @Test
    fun parseAndModify() {
        val videos = (1..9).map { "tt2934286:1:$it" }
        val watched = "tt2934286:1:5:5:eJyTZwAAAEAAIA=="
        val wb = WatchedBitField.constructAndResize(watched, videos)
        assertTrue(wb.getVideo("tt2934286:1:5"))
        assertFalse(wb.getVideo("tt2934286:1:6"))
        assertEquals(watched, wb.toString())
        wb.setVideo("tt2934286:1:6", true)
        assertTrue(wb.getVideo("tt2934286:1:6"))
    }

    @Test
    fun constructFromArrayRoundTrip() {
        val ids = (1 until 500).map { "tt2934286:1:$it" }
        val wb = WatchedBitField.fromArray(List(500) { false }, ids)
        ids.forEachIndexed { i, id -> assertFalse(wb.get(i)); assertFalse(wb.getVideo(id)) }
        ids.indices.forEach { wb.set(it, it % 2 == 0) }
        val wb2 = WatchedBitField.constructAndResize(wb.toString(), ids)
        ids.forEachIndexed { i, id -> assertEquals(i % 2 == 0, wb2.get(i)); assertEquals(i % 2 == 0, wb2.getVideo(id)) }
    }

    @Test
    fun toStringEmpty() {
        assertEquals("undefined:1:eJwDAAAAAAE=", WatchedBitField.fromArray(emptyList(), emptyList()).toString())
    }

    @Test
    fun deserializeEmpty() {
        val wb = WatchedBitField.constructAndResize("undefined:1:eJwDAAAAAAE=", emptyList())
        assertEquals(emptyList(), wb.videoIds)
    }

    @Test
    fun watchedFieldParsesAnchorWithColons() {
        val f = WatchedField.parse("tt7767422:3:8:24:eJz7//8/AAX9Av4=")
        assertEquals("tt7767422:3:8", f.anchorVideo)
        assertEquals(24, f.anchorLength)
        assertEquals("eJz7//8/AAX9Av4=", f.bitfield.encode())
        assertEquals("tt7767422:3:8:24:eJz7//8/AAX9Av4=", f.toString())
    }

    @Test
    fun anchorPointsToLastWatchedVideo() {
        val ids = (1..20).map { "tt0:1:$it" }
        val wb = WatchedBitField.fromArray(emptyList(), ids)
        wb.setVideo("tt0:1:16", true)
        assertTrue(wb.toString().startsWith("tt0:1:16:16:"))
    }

    @Test
    fun bitfield8DecodeAndLength() {
        val bf = BitField8.decode("eJyTZwAAAEAAIA==")
        assertEquals(16, bf.length)
        assertEquals(BitField8.withValues(byteArrayOf(31, 0), null), bf)
        assertEquals("eJyTZwAAAEAAIA==", bf.encode())
        assertEquals(9, BitField8.decode("eJyTZwAAAEAAIA==", 9).length)
    }

    @Test
    fun newUsesBitLength() {
        val bf = BitField8.new(20)
        assertEquals(20, bf.length)
        bf.set(15, true)
        assertEquals(15, bf.lastIndexOf(true))
    }

    @Test
    fun shiftsWhenEpisodesAreInsertedBeforeAnchor() {
        // Watched 1..3 of a 3-episode list, then the add-on prepends a special at index 0.
        val before = listOf("s:1:1", "s:1:2", "s:1:3")
        val wb = WatchedBitField.fromArray(listOf(true, true, true), before)
        val after = listOf("s:0:1") + before
        val wb2 = WatchedBitField.constructAndResize(wb.toString(), after)
        assertFalse(wb2.getVideo("s:0:1"))
        before.forEach { assertTrue(wb2.getVideo(it), it) }
    }
}
