package com.vyrk.core.brand

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmblemTest {
    private fun repoFile(path: String): File =
        listOf(File(path), File("../$path")).firstOrNull { it.exists() } ?: error("$path not found from ${File(".").absolutePath}")

    /** The corner points of each path in an SVG or vector drawable (M, L and H commands). */
    private fun pieces(text: String, attr: String): List<List<Emblem.Point>> =
        Regex("""$attr="([^"]+)"""").findAll(text).map { m ->
            val pts = ArrayList<Emblem.Point>()
            var y = 0f
            Regex("""([MLH])\s*([-\d.]+)(?:[ ,]([-\d.]+))?""").findAll(m.groupValues[1]).forEach { c ->
                when (c.groupValues[1]) {
                    "H" -> pts += Emblem.Point(c.groupValues[2].toFloat(), y)
                    else -> { y = c.groupValues[3].toFloat(); pts += Emblem.Point(c.groupValues[2].toFloat(), y) }
                }
            }
            pts
        }.toList()

    @Test
    fun matchesTheBrandMarkAndTheLauncherIcon() {
        assertEquals(Emblem.PIECES, pieces(repoFile("site/img/mark.svg").readText(), "d"))
        assertEquals(Emblem.PIECES, pieces(repoFile("app/src/main/res/drawable/ic_launcher.xml").readText(), "android:pathData"))
    }

    @Test
    fun traceOrderKeepsTheShape() {
        for (piece in Emblem.PIECES) {
            val order = Emblem.traceOrder(piece)
            assertEquals(piece.first(), order.first())
            assertEquals(piece.toSet(), order.toSet())
            assertEquals(Emblem.perimeter(piece), Emblem.perimeter(order), 1e-3f)
        }
        // The blades are drawn down their outside edge first: from the top corner to the lowest point.
        assertEquals(Emblem.Point(50f, 88f), Emblem.traceOrder(Emblem.LEFT)[1])
        assertEquals(Emblem.Point(63f, 63f), Emblem.traceOrder(Emblem.RIGHT)[1])
    }

    @Test
    fun boundsAreTheDrawnV() {
        val all = Emblem.PIECES.flatten()
        assertEquals(Emblem.TOP, all.minOf { it.y })
        assertEquals(Emblem.BOTTOM, all.maxOf { it.y })
        assertEquals(Emblem.LEFT_EDGE, all.minOf { it.x })
        assertEquals(Emblem.RIGHT_EDGE, all.maxOf { it.x })
        assertTrue(Emblem.perimeter(Emblem.LEFT) > Emblem.perimeter(Emblem.RIGHT))
    }
}
