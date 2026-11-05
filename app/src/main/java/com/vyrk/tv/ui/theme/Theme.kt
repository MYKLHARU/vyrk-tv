package com.vyrk.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.vyrk.tv.R

/**
 * VYRK palette: obsidian surfaces with a frost-blue accent. Values are on the 960×540 design
 * canvas; see [FittedDensity].
 */
@Immutable
data class VyrkColors(
    val background: Color = Color(0xFF070E17),
    val panel: Color = Color(0xFF0F1A25),
    val raised: Color = Color(0xFF172533),
    val border: Color = Color.White.copy(alpha = 0.20f),
    val divider: Color = Color.White.copy(alpha = 0.12f),
    val text: Color = Color(0xFFF4F6FA),
    val textSecondary: Color = Color(0xFFB4BAC4),
    val textMuted: Color = Color(0xFF7E8590),
    val accent: Color = Color(0xFFA9E1F3),
    val onAccent: Color = Color(0xFF0B141D),
    val success: Color = Color(0xFF7FD69A),
    val warning: Color = Color(0xFFFFC266),
    val danger: Color = Color(0xFFFF7A7A),
    val scrim: Color = Color(0xFF07080B).copy(alpha = 0.80f),
    /** Black-navy fade over artwork (left and top of the hero); darker and less blue than [background]. */
    val shade: Color = Color(0xFF04080D),
    /** Translucent panel over blurred artwork (Settings); the artwork's colour shows through. */
    val glass: Color = Color(0xFF0E1B27).copy(alpha = 0.66f),
    /** Hairline on [glass] panels so their edge reads over bright artwork. */
    val glassEdge: Color = Color.White.copy(alpha = 0.09f),
    /** Ambient light behind panels: a cool teal from one side, a soft violet from the other. */
    val ambientTeal: Color = Color(0xFF14B8C8),
    val ambientViolet: Color = Color(0xFF6D4CFF),
)

/** Accent choices offered in Settings. Frost is VYRK's own; the rest follow the spec. */
enum class AccentChoice(val color: Color, val onColor: Color) {
    FROST(Color(0xFF5AD8EE), Color(0xFF041219)),
    BLUE(Color(0xFF6EA8FF), Color(0xFF0B1220)),
    TEAL(Color(0xFF7FD1C7), Color(0xFF0B1A18)),
    VIOLET(Color(0xFFB79CFF), Color(0xFF150F24)),
    AMBER(Color(0xFFFFB86B), Color(0xFF221505)),
    PINK(Color(0xFFFF7A90), Color(0xFF240A10)),
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Jakarta = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

/** Small TV type on the 960×540 canvas. */
@Immutable
data class VyrkType(
    val displayL: TextStyle = style(44, 800, -0.5),
    val displayM: TextStyle = style(34, 800, -0.4),
    val displayS: TextStyle = style(28, 700, -0.3),
    val headlineL: TextStyle = style(23, 700),
    val headlineM: TextStyle = style(20, 700),
    val headlineS: TextStyle = style(18, 700),
    val titleL: TextStyle = style(16, 700),
    val titleM: TextStyle = style(14, 600),
    val titleS: TextStyle = style(12.5, 600),
    val bodyL: TextStyle = style(14.5, 400, lh = 1.4),
    val bodyM: TextStyle = style(13, 400, lh = 1.4),
    val bodyS: TextStyle = style(11.5, 400, lh = 1.35),
    val labelL: TextStyle = style(12.5, 600),
    val labelM: TextStyle = style(11, 600),
    val labelS: TextStyle = style(9.5, 600, 0.3),
)

private fun style(size: Number, weight: Int, spacing: Double = 0.0, lh: Double = 1.25) = TextStyle(
    fontFamily = Jakarta,
    fontWeight = FontWeight(weight),
    fontSize = size.toFloat().sp,
    letterSpacing = spacing.sp,
    lineHeight = (size.toFloat() * lh).sp,
)

object Dimens {
    val screenMargin = 48.dp
    val rowGap = 22.dp
    val panelRadius = 22.dp
    val buttonHeight = 40.dp
    val navHeight = 68.dp
    val panelShape = RoundedCornerShape(22.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val chipShape = RoundedCornerShape(10.dp)
    val posterShape = RoundedCornerShape(10.dp)
    val glowRadius = 14.dp

    // Design tokens for the redesigned surfaces (nav, hero, cards, Settings).
    val radiusS = 10.dp
    val radiusM = 14.dp
    val radiusL = 20.dp
    val radiusXL = 24.dp
    val pillShape = RoundedCornerShape(50)
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 24.dp
    /** Continue Watching cards are landscape: this wide, 16:9. */
    val landscapeCardWidth = 172.dp
}

/** Animation durations (ms). Animations use [motionSpec] and card zoom uses [focusZoom], which honour Reduce motion. */
object Motion {
    const val FAST = 140
    const val STANDARD = 240
    const val SLOW = 420
}

/** True when the profile asked for Reduce motion: animations snap instead of moving. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun <T> motionSpec(durationMs: Int = Motion.STANDARD): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) androidx.compose.animation.core.snap()
    else androidx.compose.animation.core.tween(durationMs, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/** Focus zoom for cards; none with Reduce motion (the accent edge still marks focus). */
@Composable
fun focusZoom(scale: Float): Float = if (LocalReduceMotion.current) 1f else scale

val LocalVyrkColors = staticCompositionLocalOf { VyrkColors() }
val LocalVyrkType = staticCompositionLocalOf { VyrkType() }

object Vyrk {
    val colors: VyrkColors @Composable get() = LocalVyrkColors.current
    val type: VyrkType @Composable get() = LocalVyrkType.current
}

@Composable
fun VyrkTheme(accent: AccentChoice = AccentChoice.FROST, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val colors = VyrkColors(accent = accent.color, onAccent = accent.onColor)
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        background = colors.background,
        surface = colors.panel,
        onSurface = colors.text,
        onBackground = colors.text,
        surfaceVariant = colors.raised,
        onSurfaceVariant = colors.textSecondary,
        border = colors.border,
        error = colors.danger,
    )
    CompositionLocalProvider(LocalVyrkColors provides colors, LocalVyrkType provides VyrkType(), LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
