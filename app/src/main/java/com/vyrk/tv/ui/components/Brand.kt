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
import com.vyrk.tv.ui.theme.Jakarta
import com.vyrk.tv.ui.theme.Vyrk

/**
 * VYRK mark: an angular V built from two faceted blades meeting at a point, inspired by
 * Norse knotwork geometry. Original vector, drawn in code (same paths as res/drawable/ic_vyrk_mark.xml).
 */
@Composable
fun VyrkMark(size: Dp, accent: Color = Vyrk.colors.accent, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun p(x: Float, y: Float) = Offset(x / 100f * w, y / 100f * h)
        val left = Path().apply {
            moveTo(p(8f, 14f).x, p(8f, 14f).y); lineTo(p(30f, 14f).x, p(30f, 14f).y)
            lineTo(p(54f, 70f).x, p(54f, 70f).y); lineTo(p(50f, 88f).x, p(50f, 88f).y); close()
        }
        val right = Path().apply {
            moveTo(p(92f, 14f).x, p(92f, 14f).y); lineTo(p(70f, 14f).x, p(70f, 14f).y)
            lineTo(p(56f, 47f).x, p(56f, 47f).y); lineTo(p(63f, 63f).x, p(63f, 63f).y); close()
        }
        val rune = Path().apply { // inner facet, like a rune notch
            moveTo(p(40f, 14f).x, p(40f, 14f).y); lineTo(p(60f, 14f).x, p(60f, 14f).y)
            lineTo(p(50f, 36f).x, p(50f, 36f).y); close()
        }
        drawPath(left, Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.75f)), p(8f, 14f), p(50f, 88f)))
        drawPath(right, Brush.linearGradient(listOf(Color.White, accent), p(92f, 14f), p(60f, 60f)))
        drawPath(rune, accent.copy(alpha = 0.55f))
    }
}

/** Lower-case "vyrk" wordmark beside the angular V. */
@Composable
fun Wordmark(height: Dp = 26.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(height * 0.28f)) {
        VyrkMark(height)
        Text(
            "vyrk",
            color = Vyrk.colors.text,
            fontFamily = Jakarta,
            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
            fontSize = (height.value * 0.92f).sp,
            letterSpacing = (height.value * 0.04f).sp,
        )
    }
}

/** QR code rendered with ZXing; dark modules on white for reliable phone scanning. */
@Composable
fun QrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(content) { qrBitmap(content, 512) }
    Image(bmp, contentDescription = null, modifier = modifier.size(size), filterQuality = FilterQuality.None)
}

fun qrBitmap(content: String, px: Int): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF0B141D.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
