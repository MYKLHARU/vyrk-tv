package com.vyrk.core.brand

/**
 * VYRK's V emblem: three flat pieces in a 0–100 box (y down), exactly as in site/img/mark.svg and
 * res/drawable/ic_launcher.xml. Everything that draws the mark takes its geometry from here.
 */
object Emblem {
    data class Point(val x: Float, val y: Float)

    /** The long left blade, from its top-left corner round to the tip. Antique gold. */
    val LEFT = listOf(Point(8f, 14f), Point(30f, 14f), Point(54f, 70f), Point(50f, 88f))

    /** The short right blade. Pale gold. */
    val RIGHT = listOf(Point(92f, 14f), Point(70f, 14f), Point(56f, 47f), Point(63f, 63f))

    /** The small notch between the blades' tops, like a rune's cut. Antique gold at 55%. */
    val NOTCH = listOf(Point(40f, 14f), Point(60f, 14f), Point(50f, 36f))

    val PIECES = listOf(LEFT, RIGHT, NOTCH)

    /** Where the drawn V sits in its box: x 8–92, y 14–88. */
    const val TOP = 14f
    const val BOTTOM = 88f
    const val LEFT_EDGE = 8f
    const val RIGHT_EDGE = 92f

    /**
     * The order the opening's molten line runs round each piece: from a top corner down the
     * outside edge first, so the two blades are drawn like the two strokes of a V. Same corners,
     * same shape; only the starting corner and direction differ from [PIECES].
     */
    fun traceOrder(piece: List<Point>): List<Point> = listOf(piece.first()) + piece.drop(1).reversed()

    /** The length of a closed outline, in box units. */
    fun perimeter(piece: List<Point>): Float = piece.indices.sumOf { i ->
        val a = piece[i]
        val b = piece[(i + 1) % piece.size]
        kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
    }.toFloat()
}
