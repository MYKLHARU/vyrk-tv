package com.vyrk.tv.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density

/** Interface size → design scale. */
enum class InterfaceSize(val scale: Float) { SMALL(0.72f), MEDIUM(0.84f), LARGE(1.0f) }

/**
 * Lays out the app on a 960×540 dp canvas regardless of the density a TV reports:
 * density = min(widthPx ÷ (960 ÷ scale), heightPx ÷ (540 ÷ scale)), keeping the font scale.
 * Use [fittedDensity] again inside Dialog and Popup windows, which otherwise reset density.
 */
fun fittedDensity(widthPx: Int, heightPx: Int, scale: Float, fontScale: Float): Density {
    val d = minOf(widthPx / (960f / scale), heightPx / (540f / scale))
    return Density(d, fontScale)
}

@Composable
fun FittedCanvas(size: InterfaceSize, content: @Composable () -> Unit) {
    val view = LocalView.current
    val fontScale = LocalConfiguration.current.fontScale
    // Measure the real window in pixels; the configuration's dp size is density-dependent.
    val dm = view.resources.displayMetrics
    BoxWithConstraints {
        val wPx = if (constraints.hasBoundedWidth) constraints.maxWidth else dm.widthPixels
        val hPx = if (constraints.hasBoundedHeight) constraints.maxHeight else dm.heightPixels
        val density = remember(wPx, hPx, size, fontScale) { fittedDensity(wPx, hPx, size.scale, fontScale) }
        CompositionLocalProvider(LocalDensity provides density, LocalAppDensity provides density) { content() }
    }
}

/** The app's fitted density, for re-providing inside dialog windows. */
val LocalAppDensity = androidx.compose.runtime.staticCompositionLocalOf<Density?> { null }

@Composable
fun ReprovideAppDensity(content: @Composable () -> Unit) {
    val d = LocalAppDensity.current
    if (d == null) content() else CompositionLocalProvider(LocalDensity provides d, content = content)
}
