package com.vyrk.tv.ui.phone

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.tv.ui.browse.CardCorner
import com.vyrk.tv.ui.browse.PortraitFoot
import com.vyrk.core.addon.MetaPreview
import com.vyrk.tv.R
import com.vyrk.tv.ui.browse.PosterState
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk

/** Horizontal page margin on phones. */
val PhoneMargin = 16.dp

/** Minimum touch target (Android's 48 dp). */
val TouchTarget = 48.dp

/**
 * Tap and long-press with a small press-down (a slight shrink and dim) instead of a ripple, so
 * buttons, cards and posters all answer a touch the same way. [tag] is exposed to UI tests.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.press(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    tag: String? = null,
    pressedScale: Float = 0.96f,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "press")
    return this
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) (if (pressed) 0.85f else 1f) else 0.45f }
        .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, role = role,
            onLongClick = onLongClick, onClick = onClick)
}

enum class PhoneButtonStyle { PRIMARY, SECONDARY, OUTLINE, GHOST }

/** A pill button: gold for the main action on a screen, steel or outlined for the rest. */
@Composable
fun PhoneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    style: PhoneButtonStyle = PhoneButtonStyle.SECONDARY,
    enabled: Boolean = true,
    height: Dp = TouchTarget,
    tag: String? = null,
) {
    val c = Vyrk.colors
    val (bg, fg, edge) = when (style) {
        PhoneButtonStyle.PRIMARY -> Triple(c.accent, c.onAccent, Color.Transparent)
        PhoneButtonStyle.SECONDARY -> Triple(c.steel.copy(alpha = 0.85f), c.text, Color.White.copy(alpha = 0.08f))
        PhoneButtonStyle.OUTLINE -> Triple(Color.Transparent, c.text, c.text.copy(alpha = 0.35f))
        PhoneButtonStyle.GHOST -> Triple(Color.Transparent, c.gold, Color.Transparent)
    }
    Row(
        modifier.heightIn(min = height)
            .press(onClick, enabled = enabled, tag = tag)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, edge, CircleShape)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Text(text, style = Vyrk.type.titleM, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon button with a 48 dp target. [active] lights it gold. */
@Composable
fun PhoneIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = TouchTarget,
    filled: Boolean = true,
    tag: String? = null,
) {
    val c = Vyrk.colors
    Box(
        modifier.size(size)
            .press(onClick, tag = tag)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(if (!filled) Color.Transparent else if (active) c.gold.copy(alpha = 0.18f) else c.steel.copy(alpha = 0.7f), CircleShape)
            .border(1.dp, if (active) c.gold.copy(alpha = 0.7f) else if (filled) Color.White.copy(alpha = 0.08f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.42f), tint = if (active) c.goldHighlight else c.text)
    }
}

/** A filter or choice chip. */
@Composable
fun PhoneChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String? = null, @DrawableRes icon: Int? = null) {
    val c = Vyrk.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 36.dp)
            .press(onClick, role = Role.Tab, tag = tag)
            .semantics { this.selected = selected }
            .clip(shape)
            .background(if (selected) c.gold.copy(alpha = 0.18f) else c.raised.copy(alpha = 0.7f), shape)
            .border(1.dp, if (selected) c.gold.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = if (selected) c.goldHighlight else c.textSecondary)
        Text(text, style = Vyrk.type.labelL, color = if (selected) c.goldHighlight else c.text, maxLines = 1)
    }
}

/** Chips that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
}

/** A poster: the add-on's artwork (Cinemeta's when it fails), progress, watched and New marks. */
@Composable
fun PhonePoster(
    meta: MetaPreview,
    width: Dp,
    state: PosterState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val c = Vyrk.colors
    val shape = RoundedCornerShape(10.dp)
    val newLabel = str(R.string.badge_new)
    val facts = com.vyrk.tv.ui.browse.rememberCardFacts(meta)
    val spoken = com.vyrk.tv.ui.browse.spokenFacts(facts)
    val desc = if (spoken.isEmpty()) meta.name else "${meta.name}, $spoken"
    val scale = com.vyrk.tv.ui.browse.CardScale.Phone
    Box(
        modifier.width(width).height(width * 1.5f)
            .press(onClick, onLongClick, tag = tag)
            .semantics { contentDescription = desc }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.raised, c.panel)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape),
    ) {
        // Clear of the facts row at the foot, so a long name never runs into it.
        Text(
            meta.name.ifBlank { str(if (meta.type == "series") R.string.type_series else R.string.type_movie) },
            style = Vyrk.type.labelL, color = c.textSecondary, maxLines = if (facts.hasRow) 3 else 4, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (facts.hasRow) 38.dp else 8.dp),
        )
        val candidates = remember(meta.poster, meta.id) { listOfNotNull(meta.poster, com.vyrk.core.art.Artwork.metahubPoster(meta.id)).distinct() }
        var attempt by remember(candidates) { mutableIntStateOf(0) }
        val url = candidates.getOrNull(attempt)
        if (url != null) com.vyrk.tv.ui.browse.CardArtwork(url, cardAspect = 2f / 3f, onError = { if (candidates.getOrNull(attempt) == url) attempt += 1 })
        // The foot: anime key art doesn't print the title the way other posters do, so it gets the
        // logo (or name) too; every poster gets the facts row (the name-only card too).
        val keyArt = url != null && com.vyrk.core.art.Artwork.isKeyArt(url)
        PortraitFoot(meta, facts, showTitle = keyArt, logos = if (keyArt) com.vyrk.tv.ui.browse.cardLogos(meta) else emptyList(),
            width = width, scale = scale, bottomInset = if (state.progress > 0f) 8.dp else 0.dp)
        if (state.isNew) Box(Modifier.align(Alignment.TopStart).padding(5.dp).background(c.accent, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
            Text(newLabel, style = Vyrk.type.labelS, color = c.onAccent)
        }
        CardCorner(state.watched, facts.certificate, scale, tickSize = 18.dp)
        if (state.progress > 0f) Box(
            Modifier.align(Alignment.BottomCenter).padding(start = 6.dp, end = 6.dp, bottom = 6.dp).fillMaxWidth().height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)),
        ) { Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent)) }
    }
}

/** A row heading with an optional action on the right ("See all"). */
@Composable
fun PhoneSectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Vyrk.colors
    Row(modifier.fillMaxWidth().padding(horizontal = PhoneMargin), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Vyrk.type.headlineS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = Vyrk.type.labelL, color = c.gold,
            modifier = Modifier.press(onAction).padding(horizontal = 4.dp, vertical = 12.dp))
    }
}

/** A page's top bar under the status bar: back arrow, title and actions. */
@Composable
fun PhoneTopBar(title: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) PhoneIconButton(R.drawable.ic_left, str(R.string.back), onBack, filled = false, tag = "phone:back")
        else Spacer(Modifier.width(10.dp))
        if (title != null) Text(title, style = Vyrk.type.headlineM, color = Vyrk.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A full-screen page over the tabs, with its own back handling. */
@Composable
fun PhonePage(onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Vyrk.colors.background)) { content() }
}

/**
 * A sheet from the bottom over a dim scrim. Tapping the scrim or Back closes it. The content
 * scrolls when it is taller than most of the screen.
 */
@Composable
fun PhoneSheet(visible: Boolean, onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Vyrk.colors
    if (visible) BackHandler(onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(c.scrim).press(onDismiss, role = Role.Button, pressedScale = 1f))
    }
    AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 620.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(c.panel)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    // Swallow taps so they don't reach the scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(c.textMuted.copy(alpha = 0.5f), CircleShape))
                Spacer(Modifier.height(14.dp))
                if (title != null) {
                    Text(title, style = Vyrk.type.headlineM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                content()
            }
        }
    }
}

/** A full-width row in a sheet or a list: icon, label and an optional value on the right. */
@Composable
fun PhoneListRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    hint: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    tag: String? = null,
) {
    val c = Vyrk.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .press(onClick, tag = tag, pressedScale = 0.985f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.gold.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = if (danger) c.danger else c.gold)
        Column(Modifier.weight(1f)) {
            Text(label, style = Vyrk.type.titleM, color = if (danger) c.danger else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hint != null) Text(hint, style = Vyrk.type.bodyS, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, style = Vyrk.type.labelL, color = c.textSecondary, maxLines = 1)
        if (selected) Icon(R.drawable.ic_check, null, Modifier.size(18.dp), tint = c.gold)
    }
}

/** An on/off switch row. */
@Composable
fun PhoneToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, tag: String? = null) {
    val c = Vyrk.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .press({ onChange(!value) }, role = Role.Switch, tag = tag, pressedScale = 0.99f)
            .semantics { this.selected = value }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = Vyrk.type.titleM, color = c.text)
            if (hint != null) Text(hint, style = Vyrk.type.bodyS, color = c.textMuted)
        }
        val knob by animateFloatAsState(if (value) 1f else 0f, label = "switch")
        Box(
            Modifier.width(48.dp).height(28.dp).clip(CircleShape)
                .background(if (value) c.gold else c.steel, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                .padding(3.dp),
        ) {
            Box(Modifier.padding(start = (20.dp * knob)).size(22.dp).background(if (value) c.onGold else c.textSecondary, CircleShape))
        }
    }
}

/** A setting with a few choices, as wrapping chips under its label. */
@Composable
fun <T> PhoneChoice(label: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, hint: String? = null, tag: String? = null) {
    val c = Vyrk.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = Vyrk.type.titleM, color = c.text)
        if (hint != null) Text(hint, style = Vyrk.type.bodyS, color = c.textMuted)
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            options.forEachIndexed { i, (v, name) -> PhoneChip(name, v == value, { onChange(v) }, tag = tag?.let { "$it:$i" }) }
        }
    }
}

/** A titled group of settings on a card. */
@Composable
fun PhoneCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Vyrk.colors
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.panel.copy(alpha = 0.8f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (title != null) {
            Text(title.uppercase(), style = Vyrk.type.eyebrow, color = c.gold, maxLines = 1)
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

/** Space for the bottom navigation bar and the system's navigation bar under a scrolling page. */
@Composable
fun bottomContentPadding(extra: Dp = 24.dp): PaddingValues {
    val nav = WindowInsets.navigationBars
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navDp = with(density) { nav.getBottom(density).toDp() }
    return PaddingValues(bottom = BottomBarHeight + navDp + extra)
}

/** Height of the bottom navigation bar, without the system navigation bar under it. */
val BottomBarHeight = 64.dp
