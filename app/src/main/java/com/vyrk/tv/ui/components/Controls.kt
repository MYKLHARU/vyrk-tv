package com.vyrk.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vyrk.tv.R
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import androidx.compose.ui.draw.shadow

/** Focus is a soft accent glow plus a slight zoom. Never a white border. */
@Composable
fun focusGlow(): Glow = Glow(elevationColor = Vyrk.colors.accent.copy(alpha = 0.6f), elevation = Dimens.glowRadius)

/** True inside menus, dialogs and Settings: buttons there take focus in brand gold, not white and the accent. */
val LocalMenuColors = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Gives buttons in [content] the gold menu focus. */
@Composable
fun MenuColors(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalMenuColors provides true, content = content)

/**
 * A soft glow centred on the item, spreading [spread] beyond it on every side. Unlike an
 * elevation shadow it does not slide away from the screen's light source, so items at the
 * screen's edges glow evenly. [alpha] fades it in and out.
 */
fun Modifier.centeredGlow(color: Color, spread: Dp = 14.dp, alpha: Float = 1f): Modifier =
    if (alpha <= 0f) this else this.drawBehind {
        val s = spread.toPx()
        val w = size.width + s * 2
        val h = size.height + s * 2
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        scale(scaleX = 1f, scaleY = h / w, pivot = c) {
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to color.copy(alpha = color.alpha * alpha), 0.55f to color.copy(alpha = color.alpha * alpha * 0.7f), 1f to Color.Transparent,
                    center = c, radius = w / 2,
                ),
                radius = w / 2, center = c,
            )
        }
    }

/** Focus halo for menu and settings items: brand gold instead of the content accent. */
@Composable
fun menuGlow(): Glow = Glow(elevationColor = Vyrk.colors.goldGlow, elevation = Dimens.glowRadius)

/** [BRAND] is the gold call to action used in menus, dialogs and Settings; [PRIMARY] uses the content accent. */
enum class ButtonStyle { PRIMARY, BRAND, SECONDARY, GHOST, OUTLINE }

@Composable
fun VyrkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    height: Dp = Dimens.buttonHeight,
    /** A soft accent halo even without focus: for the one call to action on a screen. */
    glow: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = Dimens.buttonShape,
) {
    val c = Vyrk.colors
    val (bg, fg) = when (style) {
        ButtonStyle.PRIMARY -> c.accent to c.onAccent
        ButtonStyle.BRAND -> c.gold to c.onGold
        ButtonStyle.SECONDARY -> c.raised to c.text
        ButtonStyle.GHOST, ButtonStyle.OUTLINE -> Color.Transparent to c.text
    }
    val brand = style == ButtonStyle.BRAND
    // In menus every button but a content-accent PRIMARY takes focus in gold.
    val goldFocus = brand || (LocalMenuColors.current && style != ButtonStyle.PRIMARY)
    val lit = if (brand) c.gold else c.accent
    val halo = if (glow) Modifier.shadow(18.dp, shape, clip = false, ambientColor = lit, spotColor = lit) else Modifier
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.then(halo).height(height).defaultMinSize(minWidth = 40.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg, contentColor = fg,
            focusedContainerColor = when { style == ButtonStyle.PRIMARY -> c.accent; goldFocus -> c.goldHighlight; else -> c.text },
            focusedContentColor = when { style == ButtonStyle.PRIMARY -> c.onAccent; goldFocus -> c.onGold; else -> c.background },
            pressedContainerColor = lit, pressedContentColor = if (brand) c.onGold else c.onAccent,
            disabledContainerColor = bg.copy(alpha = 0.4f), disabledContentColor = fg.copy(alpha = 0.5f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 0.97f),
        border = ClickableSurfaceDefaults.border(
            border = if (style == ButtonStyle.OUTLINE) Border(androidx.compose.foundation.BorderStroke(1.dp, c.text.copy(alpha = 0.38f)), shape = shape) else Border.None,
            focusedBorder = Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = if (goldFocus) menuGlow() else focusGlow()),
    ) {
        Row(
            Modifier.padding(horizontal = horizontalPadding).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = androidx.tv.material3.LocalContentColor.current)
            Text(text, style = Vyrk.type.labelL, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon-only button; [description] is required for accessibility. */
@Composable
fun VyrkIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.buttonHeight,
    shape: Shape = CircleShape,
    onLongClick: (() -> Unit)? = null,
    /** An on/off button that is on: filled gold. */
    active: Boolean = false,
) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(size).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) c.gold else c.raised.copy(alpha = 0.8f), contentColor = if (active) c.background else c.text,
            focusedContainerColor = c.text, focusedContentColor = c.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        Icon(icon, null, Modifier.size(size * 0.45f).align(Alignment.Center), tint = androidx.tv.material3.LocalContentColor.current)
    }
}

/** Selectable chip. The selected state carries a tick, never colour alone. */
@Composable
fun VyrkChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    maxLines: Int = 1,
) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.defaultMinSize(minHeight = 32.dp),
        shape = ClickableSurfaceDefaults.shape(if (maxLines > 1) Dimens.chipShape else Dimens.pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.goldActive else c.raised.copy(alpha = 0.6f),
            contentColor = if (selected) c.gold else c.textSecondary,
            focusedContainerColor = c.gold.copy(alpha = 0.18f), focusedContentColor = c.goldHighlight,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 0.97f),
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.dp, if (selected) c.gold.copy(alpha = 0.65f) else c.glassEdge)),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.gold)),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = menuGlow()),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = if (maxLines > 1) 8.dp else 0.dp).align(if (maxLines > 1) Alignment.CenterStart else Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(R.drawable.ic_check, null, Modifier.size(12.dp), tint = androidx.tv.material3.LocalContentColor.current)
                Box(Modifier.width(6.dp))
            }
            Text(text, style = if (maxLines > 1) Vyrk.type.bodyS else Vyrk.type.labelM, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A plain, non-focusable tag. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Vyrk.colors.textSecondary, background: Color = Vyrk.colors.raised) {
    Box(modifier.background(background, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, style = Vyrk.type.labelS, color = color, maxLines = 1)
    }
}

@Composable
fun Icon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Vyrk.colors.text) {
    Image(painterResource(id), contentDescription, modifier, colorFilter = ColorFilter.tint(tint))
}

/** A row that wraps its children onto new lines instead of clipping long translations. */
@Composable
fun WrapRow(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}

@Composable
fun RowScope.Spacer(w: Dp) = Box(Modifier.width(w))
