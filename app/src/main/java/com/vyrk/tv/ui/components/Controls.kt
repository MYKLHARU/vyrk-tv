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

/** Focus is a soft accent glow plus a slight zoom. Never a white border. */
@Composable
fun focusGlow(): Glow = Glow(elevationColor = Vyrk.colors.accent.copy(alpha = 0.6f), elevation = Dimens.glowRadius)

enum class ButtonStyle { PRIMARY, SECONDARY, GHOST }

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
) {
    val c = Vyrk.colors
    val (bg, fg) = when (style) {
        ButtonStyle.PRIMARY -> c.accent to c.onAccent
        ButtonStyle.SECONDARY -> c.raised to c.text
        ButtonStyle.GHOST -> Color.Transparent to c.text
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.height(Dimens.buttonHeight).defaultMinSize(minWidth = 40.dp),
        shape = ClickableSurfaceDefaults.shape(Dimens.buttonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg, contentColor = fg,
            focusedContainerColor = if (style == ButtonStyle.PRIMARY) c.accent else c.text,
            focusedContentColor = if (style == ButtonStyle.PRIMARY) c.onAccent else c.background,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
            disabledContainerColor = bg.copy(alpha = 0.4f), disabledContentColor = fg.copy(alpha = 0.5f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
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
) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(size).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.raised.copy(alpha = 0.8f), contentColor = c.text,
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
        shape = ClickableSurfaceDefaults.shape(Dimens.chipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.accent.copy(alpha = 0.22f) else c.raised,
            contentColor = if (selected) c.accent else c.textSecondary,
            focusedContainerColor = c.text, focusedContentColor = c.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
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
