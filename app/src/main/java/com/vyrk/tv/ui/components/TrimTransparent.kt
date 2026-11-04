package com.vyrk.tv.ui.components

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Crops fully transparent margins from a logo. Many title logos sit centred on a fixed canvas
 * (fanart.tv's are 800×310) with wide empty bands, so they drew small and left a gap above the
 * text. Runs on Coil's decode thread; images without alpha are returned unchanged.
 */
class TrimTransparent(private val alphaThreshold: Int = 10) : Transformation() {
    override val cacheKey: String = "trimTransparent:$alphaThreshold"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!input.hasAlpha()) return input
        val w = input.width
        val h = input.height
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        fun opaque(x: Int, y: Int) = (px[y * w + x] ushr 24) > alphaThreshold
        fun rowEmpty(y: Int): Boolean { for (x in 0 until w) if (opaque(x, y)) return false; return true }
        fun colEmpty(x: Int, y0: Int, y1: Int): Boolean { for (y in y0..y1) if (opaque(x, y)) return false; return true }
        var top = 0
        var bottom = h - 1
        while (top < bottom && rowEmpty(top)) top++
        while (bottom > top && rowEmpty(bottom)) bottom--
        var left = 0
        var right = w - 1
        while (left < right && colEmpty(left, top, bottom)) left++
        while (right > left && colEmpty(right, top, bottom)) right--
        // Nothing visible (or almost): keep the original rather than a sliver.
        if (right - left < 8 || bottom - top < 8) return input
        // One pixel of margin keeps anti-aliased edges.
        top = (top - 1).coerceAtLeast(0); left = (left - 1).coerceAtLeast(0)
        bottom = (bottom + 1).coerceAtMost(h - 1); right = (right + 1).coerceAtMost(w - 1)
        if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) return input
        return Bitmap.createBitmap(input, left, top, right - left + 1, bottom - top + 1)
    }
}
