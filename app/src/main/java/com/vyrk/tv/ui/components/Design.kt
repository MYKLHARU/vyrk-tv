package com.vyrk.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vyrk.tv.R
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus

/*
 * VYRK's design system for the redesigned surfaces: ambient light, glass panels, segmented
 * controls, check chips, colour swatches, setting rows and section headings. Screens compose
 * these instead of styling their own controls.
 */

/** The page behind panels: deep navy with a soft violet glow low on the left and teal high on the right. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Box(
        modifier.fillMaxSize().background(c.background).drawBehind {
            drawRect(Brush.radialGradient(listOf(c.ambientViolet.copy(alpha = 0.24f), Color.Transparent), center = Offset(size.width * 0.04f, size.height * 1.02f), radius = size.width * 0.46f))
            drawRect(Brush.radialGradient(listOf(c.ambientTeal.copy(alpha = 0.18f), Color.Transparent), center = Offset(size.width * 0.98f, size.height * 0.08f), radius = size.width * 0.52f))
            drawRect(Brush.linearGradient(0f to Color.Transparent, 0.55f to c.ambientTeal.copy(alpha = 0.05f), 1f to Color.Transparent, start = Offset(size.width * 0.45f, 0f), end = Offset(size.width, size.height)))
        },
    )
}

/** Frosted glass: translucent navy, a faint top highlight, a hairline edge and a soft shadow. */
fun Modifier.glassPanel(shape: Shape = Dimens.panelShape, elevation: Dp = 14.dp): Modifier = composed {
    val c = Vyrk.colors
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
        .background(c.glass, shape)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.045f), 0.4f to Color.Transparent), shape)
        .border(1.dp, c.glassEdge, shape)
}

/** Row heading with an optional chevron, like "Continue Watching ›". */
@Composable
fun SectionHeading(title: String, active: Boolean, modifier: Modifier = Modifier, chevron: Boolean = false) {
    val c = Vyrk.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Vyrk.type.titleL, color = if (active) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) Icon(R.drawable.ic_right, null, Modifier.padding(start = 4.dp).size(14.dp), tint = if (active) c.accent else c.textMuted)
    }
}

/**
 * Joined pill of options; the chosen one is filled with the accent and ticked. Each segment is
 * focusable on its own and keeps the trace name the journeys look for.
 */
@Composable
fun SegmentedControl(
    labels: List<String>,
    selected: Int,
    traces: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icons: List<Int?> = emptyList(),
) {
    val c = Vyrk.colors
    Row(
        modifier.background(c.raised.copy(alpha = 0.55f), Dimens.pillShape).border(1.dp, c.glassEdge, Dimens.pillShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.height(30.dp).traceFocus(traces.getOrElse(i) { "segment:$i" }),
                shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (on) c.accent else Color.Transparent, contentColor = if (on) c.onAccent else c.textSecondary,
                    focusedContainerColor = if (on) c.accent else c.text, focusedContentColor = if (on) c.onAccent else c.background,
                    pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border(BorderStroke(2.dp, c.text))),
                glow = ClickableSurfaceDefaults.glow(glow = if (on) Glow(c.accent.copy(alpha = 0.45f), 6.dp) else Glow.None, focusedGlow = focusGlow()),
            ) {
                Row(Modifier.padding(horizontal = 13.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (on) Icon(R.drawable.ic_check, null, Modifier.size(12.dp), tint = LocalContentColor.current)
                    icons.getOrNull(i)?.let { Icon(it, null, Modifier.size(14.dp), tint = LocalContentColor.current) }
                    Text(label, style = Vyrk.type.labelL, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** On / Off as a two-part segmented control. */
@Composable
fun OnOffControl(value: Boolean, trace: String, onLabel: String, offLabel: String, onChange: (Boolean) -> Unit) =
    SegmentedControl(listOf(onLabel, offLabel), if (value) 0 else 1, listOf("$trace:on", "$trace:off"), { onChange(it == 0) })

/** A chip with a checkbox, for choices that can be combined (hidden tabs). */
@Composable
fun CheckChip(label: String, checked: Boolean, onClick: () -> Unit, trace: String, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (checked) c.accent.copy(alpha = 0.14f) else c.raised.copy(alpha = 0.55f), contentColor = if (checked) c.text else c.textSecondary,
            focusedContainerColor = c.text, focusedContentColor = c.background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (checked) c.accent.copy(alpha = 0.7f) else c.glassEdge)),
            focusedBorder = Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
    ) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val box = RoundedCornerShape(4.dp)
            Box(
                Modifier.size(14.dp)
                    .background(if (checked) c.accent else Color.Transparent, box)
                    .border(1.5.dp, if (checked) c.accent else LocalContentColor.current.copy(alpha = 0.6f), box),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(R.drawable.ic_check, null, Modifier.size(10.dp), tint = c.onAccent) }
            Text(label, style = Vyrk.type.labelL, maxLines = 1, softWrap = false)
        }
    }
}

/** A round colour swatch with its name underneath; the chosen one is ticked and ringed. */
@Composable
fun ColorSwatch(color: Color, onColor: Color, label: String, selected: Boolean, onClick: () -> Unit, trace: String) {
    val c = Vyrk.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(24.dp).traceFocus(trace),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color, pressedContainerColor = color),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.22f),
            border = ClickableSurfaceDefaults.border(
                border = if (selected) Border(BorderStroke(2.dp, c.text.copy(alpha = 0.9f))) else Border.None,
                focusedBorder = Border(BorderStroke(2.dp, c.text)),
            ),
            glow = ClickableSurfaceDefaults.glow(glow = if (selected) Glow(color.copy(alpha = 0.55f), 8.dp) else Glow.None, focusedGlow = Glow(color.copy(alpha = 0.7f), 12.dp)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (selected) Icon(R.drawable.ic_check, null, Modifier.size(13.dp), tint = onColor)
            }
        }
        Text(label, style = Vyrk.type.labelS, color = if (selected) c.text else c.textMuted, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * One setting: icon, name and a short description on the left, its controls on the right. With
 * [stacked], controls that need the full width (long option lists) sit underneath instead.
 */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    stacked: Boolean = false,
    controls: @Composable RowScope.() -> Unit,
) {
    val c = Vyrk.colors
    val label: @Composable RowScope.(Modifier) -> Unit = { m ->
        if (icon != null) Icon(icon, null, Modifier.padding(end = 12.dp).size(18.dp), tint = c.textSecondary)
        Column(m) {
            Text(title, style = Vyrk.type.titleS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!description.isNullOrBlank()) Text(description, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (stacked) {
        Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { label(Modifier) }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(start = if (icon != null) 30.dp else 0.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { controls() }
        }
    } else {
        Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            label(Modifier.weight(1f).padding(end = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = controls)
        }
    }
}

/** Hairline between setting rows. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))

/** Vertical hairline between two setting columns. */
@Composable
fun ColumnDivider(modifier: Modifier = Modifier) =
    Box(modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.06f)))
