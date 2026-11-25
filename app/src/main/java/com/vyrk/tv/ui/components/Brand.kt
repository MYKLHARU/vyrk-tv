package com.vyrk.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vyrk.core.brand.Emblem
import com.vyrk.tv.ui.theme.Vyrk

/**
 * VYRK mark: an angular V built from two faceted blades meeting at a point, inspired by
 * Norse knotwork geometry. Its geometry is [Emblem] (the same as res/drawable/ic_launcher.xml).
 */
@Composable
fun VyrkMark(size: Dp, accent: Color = Vyrk.colors.accent, modifier: Modifier = Modifier, highlight: Color = Color.White) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun p(pt: Emblem.Point) = Offset(pt.x / 100f * w, pt.y / 100f * h)
        fun path(pts: List<Emblem.Point>) = Path().apply {
            moveTo(p(pts[0]).x, p(pts[0]).y)
            for (pt in pts.drop(1)) lineTo(p(pt).x, p(pt).y)
            close()
        }
        drawPath(path(Emblem.LEFT), Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.75f)), p(Emblem.LEFT[0]), p(Emblem.LEFT[3])))
        drawPath(path(Emblem.RIGHT), Brush.linearGradient(listOf(highlight, accent), p(Emblem.RIGHT[0]), Offset(0.60f * w, 0.60f * h)))
        drawPath(path(Emblem.NOTCH), accent.copy(alpha = 0.55f)) // inner facet, like a rune notch
    }
}

/**
 * The VYRK wordmark: the angular V in antique gold, then the name in spaced serif capitals, like
 * letters cut into stone. Gold is kept for the brand; focus and controls use the frost accent.
 */
@Composable
fun Wordmark(height: Dp = 26.dp, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(height * 0.34f)) {
        VyrkMark(height, accent = c.gold, highlight = Color(0xFFF3DEB0))
        Text(
            "VYRK",
            color = c.text,
            fontFamily = com.vyrk.tv.ui.theme.NorseDisplay,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            fontSize = (height.value * 0.78f).sp,
            letterSpacing = (height.value * 0.30f).sp,
            maxLines = 1,
        )
    }
}

/** QR code rendered with ZXing; dark modules on white for reliable phone scanning. */
@Composable
fun QrCode(content: String, size: Dp, modifier: Modifier = Modifier, lowCorrection: Boolean = false) {
    // Long content (a playback report) uses the lowest error correction: fewer, larger squares.
    val bmp = remember(content) { qrBitmap(content, if (lowCorrection) 720 else 512, if (lowCorrection) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M) }
    Image(bmp, contentDescription = null, modifier = modifier.size(size), filterQuality = FilterQuality.None)
}

fun qrBitmap(content: String, px: Int, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ecc, EncodeHintType.MARGIN to 2)
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF0B141D.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
