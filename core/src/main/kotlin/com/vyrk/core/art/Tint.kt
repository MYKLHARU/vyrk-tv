package com.vyrk.core.art

/**
 * The colour an artwork "feels" like, for the glow behind a page: the most present vivid hue
 * (pixels weighted by saturation and brightness, grouped in 12 hue bands), evened out to a
 * mid-bright, fairly saturated colour so any artwork gives a usable glow. Null for greyscale art.
 */
object Tint {
    fun of(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = DoubleArray(BANDS); val r = DoubleArray(BANDS); val g = DoubleArray(BANDS); val b = DoubleArray(BANDS)
        val hsv = FloatArray(3)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            toHsv(pr, pg, pb, hsv)
            val s = hsv[1]; val v = hsv[2]
            if (v < 0.15f || s < 0.20f) continue
            val w = (s * v).toDouble()
            val band = ((hsv[0] / 360f) * BANDS).toInt().coerceIn(0, BANDS - 1)
            weight[band] += w; r[band] += pr * w; g[band] += pg * w; b[band] += pb * w
        }
        val best = weight.indices.maxBy { weight[it] }
        if (weight[best] < pixels.size * 0.02) return null
        toHsv((r[best] / weight[best]).toInt(), (g[best] / weight[best]).toInt(), (b[best] / weight[best]).toInt(), hsv)
        return fromHsv(hsv[0], hsv[1].coerceIn(0.45f, 0.85f), 0.72f)
    }

    private const val BANDS = 12

    fun toHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / d) % 6f)
            max == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        out[0] = if (h < 0) h + 360f else h
        out[1] = if (max == 0f) 0f else d / max
        out[2] = max
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
