package com.vyrk.tv.ui.components

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
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

/**
 * The page behind panels: deep obsidian under a faint aurora (green-teal ribbons high on the right,
 * a cold fjord blue low on the left) and, very dim along the bottom, a distant ridge of mountains.
 * Drawn once per size into cached brushes and paths; nothing animates.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Box(
        modifier.fillMaxSize().background(c.background).drawWithCache {
            val w = size.width; val h = size.height
            val ridge = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h * 0.86f)
                val peaks = floatArrayOf(0.06f, 0.80f, 0.13f, 0.84f, 0.22f, 0.74f, 0.30f, 0.82f, 0.41f, 0.77f, 0.50f, 0.85f, 0.61f, 0.72f, 0.70f, 0.81f, 0.79f, 0.75f, 0.88f, 0.83f, 0.95f, 0.78f, 1f, 0.82f)
                for (i in peaks.indices step 2) lineTo(w * peaks[i], h * peaks[i + 1])
                lineTo(w, h); lineTo(0f, h); close()
            }
            val auroraA = Brush.radialGradient(listOf(c.ambientTeal.copy(alpha = 0.20f), Color.Transparent), center = Offset(w * 0.78f, h * 0.02f), radius = w * 0.42f)
            val auroraB = Brush.radialGradient(listOf(c.accent.copy(alpha = 0.10f), Color.Transparent), center = Offset(w * 0.55f, -h * 0.10f), radius = w * 0.36f)
            val fjord = Brush.radialGradient(listOf(c.ambientViolet.copy(alpha = 0.26f), Color.Transparent), center = Offset(w * 0.04f, h * 1.02f), radius = w * 0.46f)
            val ridgeFill = Brush.verticalGradient(listOf(c.steel.copy(alpha = 0.30f), c.background.copy(alpha = 0f)), startY = h * 0.72f, endY = h)
            onDrawBehind {
                drawRect(fjord); drawRect(auroraA); drawRect(auroraB)
                drawPath(ridge, ridgeFill)
            }
        },
    )
}

/**
 * A rune-cut separator: a hairline that fades in from both ends to a small gold lozenge knot in the
 * middle (or at the start with [centered] false). Decorative; not focusable.
 */
@Composable
fun RuneDivider(modifier: Modifier = Modifier, centered: Boolean = true, color: Color = Vyrk.colors.gold) {
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(9.dp)) {
        val cy = size.height / 2f
        val cx = if (centered) size.width / 2f else size.height * 0.9f
        val r = size.height / 2f
        val line = if (centered) Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color.copy(alpha = 0.55f), 1f to Color.Transparent, startX = 0f, endX = size.width)
            else Brush.horizontalGradient(0f to color.copy(alpha = 0.55f), 1f to Color.Transparent, startX = 0f, endX = size.width)
        // The line stops short of the knot on both sides, so the knot reads on any surface.
        val gap = r * 2.4f
        if (centered) drawRect(line, topLeft = Offset(0f, cy - 0.5f), size = androidx.compose.ui.geometry.Size((cx - gap).coerceAtLeast(0f), 1f))
        drawRect(line, topLeft = Offset(cx + gap, cy - 0.5f), size = androidx.compose.ui.geometry.Size((size.width - cx - gap).coerceAtLeast(0f), 1f))
        // Two nested lozenges, like a knot boss.
        fun lozenge(rr: Float) = androidx.compose.ui.graphics.Path().apply { moveTo(cx - rr * 1.6f, cy); lineTo(cx, cy - rr); lineTo(cx + rr * 1.6f, cy); lineTo(cx, cy + rr); close() }
        drawPath(lozenge(r), color.copy(alpha = 0.9f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
        drawPath(lozenge(r * 0.42f), color)
    }
}

/** A small carved rune (Algiz, the elk-sedge of protection) in gold, ahead of section headings. */
@Composable
fun RuneGlyph(size: Dp, modifier: Modifier = Modifier, color: Color = Vyrk.colors.gold) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val stroke = w * 0.13f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(color, Offset(w / 2f, h * 0.08f), Offset(w / 2f, h * 0.94f), stroke, cap)
        drawLine(color, Offset(w / 2f, h * 0.52f), Offset(w * 0.12f, h * 0.10f), stroke, cap)
        drawLine(color, Offset(w / 2f, h * 0.52f), Offset(w * 0.88f, h * 0.10f), stroke, cap)
    }
}

/**
 * Frosted glass: translucent teal-navy, a faint top highlight, a hairline edge and a soft shadow.
 * With [aurora], soft light rises from one corner (teal and violet from the top right, or violet
 * from the bottom left), clipped to the panel by drawing, not by clipping a layer, so focused
 * controls may still zoom past the edge.
 */
enum class Aurora { TOP_END, BOTTOM_START }

fun Modifier.glassPanel(shape: Shape = Dimens.panelShape, elevation: Dp = 14.dp, aurora: Aurora? = null): Modifier = composed {
    val c = Vyrk.colors
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
        .background(c.glass, shape)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.045f), 0.4f to Color.Transparent), shape)
        .then(if (aurora != null) Modifier.drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            clipPath(androidx.compose.ui.graphics.Path().apply { addOutline(outline) }) {
                when (aurora) {
                    Aurora.TOP_END -> {
                        drawRect(Brush.radialGradient(listOf(c.ambientTeal.copy(alpha = 0.30f), Color.Transparent), center = Offset(size.width * 0.84f, -size.height * 0.06f), radius = size.width * 0.46f))
                        drawRect(Brush.radialGradient(listOf(c.ambientViolet.copy(alpha = 0.24f), Color.Transparent), center = Offset(size.width * 1.02f, size.height * 0.12f), radius = size.width * 0.32f))
                    }
                    Aurora.BOTTOM_START ->
                        drawRect(Brush.radialGradient(listOf(c.ambientViolet.copy(alpha = 0.30f), Color.Transparent), center = Offset(0f, size.height * 1.04f), radius = size.width * 0.95f))
                }
            }
        } else Modifier)
        .border(1.dp, c.glassEdge, shape)
}

/**
 * Row heading with a gold rune ahead of it and an optional chevron, like "ᛉ Continue Watching ›".
 * With [rule], a carved hairline runs on from the title across the row and fades out, so
 * sections are set apart without boxes.
 */
@Composable
fun SectionHeading(title: String, active: Boolean, modifier: Modifier = Modifier, chevron: Boolean = false, rule: Boolean = false) {
    val c = Vyrk.colors
    Row(if (rule) modifier.fillMaxWidth() else modifier, verticalAlignment = Alignment.CenterVertically) {
        RuneGlyph(11.dp, Modifier.padding(end = 8.dp), color = if (active) c.gold else c.gold.copy(alpha = 0.55f))
        Text(title, style = Vyrk.type.titleL, color = if (active) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (rule) Modifier.weight(1f, fill = false) else Modifier)
        if (chevron) Icon(R.drawable.ic_right, null, Modifier.padding(start = 4.dp).size(14.dp), tint = if (active) c.accent else c.textMuted)
        if (rule) Box(
            Modifier.padding(start = 14.dp).weight(1f).height(1.dp)
                .background(Brush.horizontalGradient(listOf(c.gold.copy(alpha = if (active) 0.45f else 0.24f), c.steel.copy(alpha = 0.35f), Color.Transparent))),
        )
    }
}

/**
 * A remote key and what it does, like [OK] Resume or [☰] More info: the hero and title header
 * name their actions this way instead of adding buttons that would change where the arrows go.
 * [primary] fills the hint with the accent, as the screen's main call to action.
 */
@Composable
fun KeyHint(key: String, label: String, modifier: Modifier = Modifier, primary: Boolean = false, @DrawableRes icon: Int? = null) {
    val c = Vyrk.colors
    val ink = if (primary) c.onAccent else c.text
    Row(
        modifier.height(26.dp)
            .then(if (primary) Modifier.shadow(10.dp, Dimens.pillShape, clip = false, ambientColor = c.accent, spotColor = c.accent).background(c.accent, Dimens.pillShape)
                else Modifier.background(c.shade.copy(alpha = 0.45f), Dimens.pillShape).border(1.dp, c.text.copy(alpha = 0.16f), Dimens.pillShape))
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.height(18.dp).defaultMinSize(minWidth = 22.dp)
                .border(1.dp, ink.copy(alpha = if (primary) 0.55f else 0.38f), RoundedCornerShape(9.dp)).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) { Text(key, style = Vyrk.type.labelS, color = ink, maxLines = 1) }
        if (icon != null) Icon(icon, null, Modifier.size(10.dp), tint = ink)
        Text(label, style = Vyrk.type.labelL, color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * A carved rune pillar down the left edge of the catalogue pages: a fine gold line with knot
 * bosses and rune marks cut into it, fading in and out. Drawn once; never focusable.
 */
@Composable
fun RunePillar(modifier: Modifier = Modifier) {
    val gold = Vyrk.colors.gold
    androidx.compose.foundation.Canvas(modifier.width(14.dp).fillMaxHeight()) {
        val cx = size.width / 2f
        val h = size.height
        val line = Brush.verticalGradient(0f to Color.Transparent, 0.12f to gold.copy(alpha = 0.30f), 0.88f to gold.copy(alpha = 0.30f), 1f to Color.Transparent, startY = 0f, endY = h)
        drawRect(line, topLeft = Offset(cx - 0.5f, 0f), size = androidx.compose.ui.geometry.Size(1f, h))
        val r = size.width * 0.32f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.1f)
        for (k in 1..5) {
            val y = h * k / 6f
            val a = if (k == 1 || k == 5) 0.30f else 0.48f
            val lozenge = androidx.compose.ui.graphics.Path().apply { moveTo(cx, y - r * 1.5f); lineTo(cx + r, y); lineTo(cx, y + r * 1.5f); lineTo(cx - r, y); close() }
            drawPath(lozenge, Color(0xFF050A0E))
            drawPath(lozenge, gold.copy(alpha = a), style = stroke)
            drawCircle(gold.copy(alpha = a), radius = 1.4f, center = Offset(cx, y))
            // A rune notch between bosses: Algiz and its mirror, alternating.
            val ny = y + h / 12f
            if (k < 5) {
                val up = k % 2 == 0
                val dy = if (up) -r else r
                drawLine(gold.copy(alpha = a * 0.8f), Offset(cx, ny), Offset(cx - r, ny + dy), 1.1f)
                drawLine(gold.copy(alpha = a * 0.8f), Offset(cx, ny), Offset(cx + r, ny + dy), 1.1f)
            }
        }
    }
}

/**
 * A set of options. Two options are one joined track; three or more are separate pills that wrap.
 * The chosen option is filled with gold and ticked; remote focus is a light gold ring with a soft
 * lift, so the choice and the focus never look alike. Each option keeps the trace name the
 * journeys look for.
 */
@Composable
fun SegmentedControl(
    labels: List<String>,
    selected: Int,
    traces: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icons: List<Int?> = emptyList(),
    separated: Boolean = labels.size > 2,
) {
    val c = Vyrk.colors
    Row(
        if (separated) modifier
        else modifier.background(c.shade.copy(alpha = 0.55f), Dimens.pillShape).border(1.dp, c.text.copy(alpha = 0.10f), Dimens.pillShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(if (separated) 8.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            OptionPill(label, i == selected, { onSelect(i) }, traces.getOrElse(i) { "segment:$i" }, icons.getOrNull(i), separated)
        }
    }
}

/** One option of a single choice, as a separate pill; filled with gold and ticked when chosen. */
@Composable
fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit, trace: String, @DrawableRes icon: Int? = null) =
    OptionPill(label, selected, onClick, trace, icon, separated = true)

@Composable
private fun OptionPill(label: String, on: Boolean, onClick: () -> Unit, trace: String, @DrawableRes icon: Int?, separated: Boolean) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.height(if (separated) 34.dp else 30.dp).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when { on -> c.gold; separated -> c.steel.copy(alpha = 0.55f); else -> Color.Transparent },
            contentColor = if (on) c.onGold else c.textSecondary,
            focusedContainerColor = if (on) c.goldHighlight else c.gold.copy(alpha = 0.16f), focusedContentColor = if (on) c.onGold else c.goldHighlight,
            pressedContainerColor = c.gold, pressedContentColor = c.onGold,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = if (separated && !on) Border(BorderStroke(1.dp, c.text.copy(alpha = 0.10f))) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, c.goldHighlight)),
        ),
        glow = ClickableSurfaceDefaults.glow(glow = if (on) Glow(c.goldGlow, 6.dp) else Glow.None, focusedGlow = Glow(c.goldGlow, 10.dp)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (on) Icon(R.drawable.ic_check, null, Modifier.size(12.dp), tint = LocalContentColor.current)
            icon?.let { Icon(it, null, Modifier.size(14.dp), tint = LocalContentColor.current) }
            Text(label, style = Vyrk.type.labelL, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * On / Off as a switch: OK flips it. The track fills with gold when on and the thumb slides
 * across (snapping with Reduce motion); remote focus rings it in white. The state is also written
 * beside it and read out for accessibility.
 */
@Composable
fun OnOffControl(value: Boolean, trace: String, onLabel: String, offLabel: String, onChange: (Boolean) -> Unit) {
    val c = Vyrk.colors
    val thumbX by androidx.compose.animation.core.animateDpAsState(if (value) 22.dp else 3.dp, com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.FAST), label = "switch-thumb")
    val track by androidx.compose.animation.animateColorAsState(if (value) c.gold else c.steel, com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.FAST), label = "switch-track")
    val state = if (value) onLabel else offLabel
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            onClick = { onChange(!value) },
            modifier = Modifier.size(46.dp, 26.dp).traceFocus("$trace:switch")
                .semantics { role = androidx.compose.ui.semantics.Role.Switch; stateDescription = state },
            shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = track, focusedContainerColor = track, pressedContainerColor = track),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                border = if (value) Border.None else Border(BorderStroke(1.dp, c.text.copy(alpha = 0.18f))),
                focusedBorder = Border(BorderStroke(2.dp, c.goldHighlight)),
            ),
            glow = ClickableSurfaceDefaults.glow(glow = if (value) Glow(c.goldGlow, 8.dp) else Glow.None, focusedGlow = Glow(c.gold.copy(alpha = 0.40f), 12.dp)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.CenterStart).offset(x = thumbX).size(20.dp).shadow(3.dp, CircleShape).background(if (value) Color.White else c.textSecondary, CircleShape))
            }
        }
        Text(state, style = Vyrk.type.labelL, color = if (value) c.gold else c.textMuted, maxLines = 1, modifier = Modifier.defaultMinSize(minWidth = 28.dp))
    }
}

/** A chip with a checkbox, for choices that can be combined (hidden tabs, languages). */
@Composable
fun CheckChip(label: String, checked: Boolean, onClick: () -> Unit, trace: String, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp).traceFocus(trace),
        shape = ClickableSurfaceDefaults.shape(Dimens.pillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (checked) c.goldActive else c.steel.copy(alpha = 0.55f), contentColor = if (checked) c.text else c.textSecondary,
            focusedContainerColor = if (checked) c.gold.copy(alpha = 0.22f) else c.gold.copy(alpha = 0.14f), focusedContentColor = c.goldHighlight,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (checked) c.gold.copy(alpha = 0.7f) else c.text.copy(alpha = 0.10f))),
            focusedBorder = Border(BorderStroke(2.dp, c.goldHighlight)),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(c.goldGlow, 10.dp)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val box = RoundedCornerShape(4.dp)
            Box(
                Modifier.size(15.dp)
                    .background(if (checked) c.gold else Color.Transparent, box)
                    .border(1.5.dp, if (checked) c.gold else LocalContentColor.current.copy(alpha = 0.6f), box),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(R.drawable.ic_check, null, Modifier.size(10.dp), tint = c.onGold) }
            Text(label, style = Vyrk.type.labelL, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A round colour swatch with its name underneath. The chosen one sits inside a ring of its own
 * colour and is ticked; remote focus enlarges it and rings it in white.
 */
@Composable
fun ColorSwatch(color: Color, onColor: Color, label: String, selected: Boolean, onClick: () -> Unit, trace: String) {
    val c = Vyrk.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.matchParentSize().border(2.dp, color, CircleShape))
            Surface(
                onClick = onClick,
                modifier = Modifier.size(28.dp).traceFocus(trace),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color, pressedContainerColor = color),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.22f),
                border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border(BorderStroke(2.dp, c.goldHighlight))),
                glow = ClickableSurfaceDefaults.glow(glow = if (selected) Glow(color.copy(alpha = 0.55f), 8.dp) else Glow.None, focusedGlow = Glow(color.copy(alpha = 0.7f), 12.dp)),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (selected) Icon(R.drawable.ic_check, null, Modifier.size(14.dp), tint = onColor)
                }
            }
        }
        Text(label, style = Vyrk.type.labelS, color = if (selected) c.text else c.textMuted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Share of a full-width setting row taken by its name and description; controls start after it. */
private const val LABEL_COLUMN = 0.40f

/**
 * One setting: icon badge, name and a short description, then its controls. In a full-width row
 * ([split]) every row's controls start at the same column and wrap within it, so a page reads
 * as two aligned columns. In half of a two-column row, controls sit at the right of their half.
 */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    split: Boolean = true,
    controls: @Composable RowScope.() -> Unit,
) {
    val c = Vyrk.colors
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            (if (split) Modifier.fillMaxWidth(LABEL_COLUMN) else Modifier.weight(1f)).padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Box(
                Modifier.padding(end = 12.dp).size(30.dp).background(c.shade.copy(alpha = 0.45f), RoundedCornerShape(9.dp)).border(1.dp, c.text.copy(alpha = 0.08f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(16.dp), tint = c.gold) }
            Column {
                Text(title, style = Vyrk.type.titleM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!description.isNullOrBlank()) Text(description, style = Vyrk.type.labelM.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (split) androidx.compose.foundation.layout.FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { controls() }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = controls)
    }
}

/** Empty page: the section's icon in a soft accent badge, with a short message underneath. */
@Composable
fun EmptyState(@DrawableRes icon: Int, message: String, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Column(modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).background(c.accent.copy(alpha = 0.10f), CircleShape).border(1.dp, c.accent.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(28.dp), tint = c.accent) }
        Text(message, style = Vyrk.type.bodyL, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 16.dp).width(420.dp))
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
