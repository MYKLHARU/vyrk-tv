package com.vyrk.tv.ui.screens

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import androidx.tv.material3.Text
import com.vyrk.core.lang.Languages
import com.vyrk.core.playback.SubtitleMode
import com.vyrk.core.stream.FormatPref
import com.vyrk.core.stream.HdrPref
import com.vyrk.core.stream.Preset
import com.vyrk.tv.BuildConfig
import com.vyrk.tv.R
import com.vyrk.tv.data.AccountState
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.PreviewMode
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.data.Updater
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.ChoicePill
import androidx.compose.foundation.layout.size
import com.vyrk.tv.ui.components.glassPanel
import com.vyrk.tv.ui.components.ColorSwatch
import com.vyrk.tv.ui.components.CheckChip
import com.vyrk.tv.ui.components.SegmentedControl
import com.vyrk.tv.ui.components.OnOffControl
import com.vyrk.tv.ui.components.ColumnDivider
import com.vyrk.tv.ui.components.RowDivider
import com.vyrk.tv.ui.components.SettingRow
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

private enum class Group(val title: Int, val icon: Int, val subtitle: Int) {
    PROFILE(R.string.settings_profile, R.drawable.ic_profile, R.string.settings_profile_sub),
    STREAMS(R.string.settings_streams, R.drawable.ic_live, R.string.settings_streams_sub),
    PICTURE(R.string.settings_picture, R.drawable.ic_display, R.string.settings_picture_sub),
    LANGUAGES(R.string.settings_languages, R.drawable.ic_globe, R.string.settings_languages_sub),
    SUBTITLES(R.string.settings_subtitles, R.drawable.ic_subtitles, R.string.settings_subtitles_sub),
    PREVIEWS(R.string.settings_previews, R.drawable.ic_preview, R.string.settings_previews_sub),
    ADDONS(R.string.settings_addons, R.drawable.ic_puzzle, R.string.settings_addons_sub),
    ABOUT(R.string.settings_about, R.drawable.ic_info, R.string.settings_about_sub),
}

@Composable
fun SettingsScreen(session: ProfileSession) {
    var group by remember { mutableStateOf(Group.PROFILE) }
    // One requester per group, so Left from any setting returns to the group it belongs to.
    val groupReqs = remember { Group.entries.associateWith { FocusRequester() } }
    val contentReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { groupReqs.getValue(group).requestFocus() } }
    val addons by session.addons.collectAsState()
    var typing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(group) { typing = null }
    val c = Vyrk.colors
    Row(Modifier.fillMaxSize()) {
        // The category column: dark steel carved into the page, edged in gold on its right.
        Column(
            Modifier.width(Dimens.screenMargin + 196.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(c.shade.copy(alpha = 0.86f), c.panel.copy(alpha = 0.62f))))
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.15f to c.gold.copy(alpha = 0.40f), 0.85f to c.gold.copy(alpha = 0.40f), 1f to Color.Transparent),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), size = androidx.compose.ui.geometry.Size(1f, size.height))
                }
                .padding(start = Dimens.screenMargin - 12.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                com.vyrk.tv.ui.components.VyrkMark(18.dp, accent = c.gold, highlight = Color(0xFFF3DEB0))
                Spacer(Modifier.width(10.dp))
                Text(str(R.string.settings).uppercase(), style = Vyrk.type.eyebrow.copy(fontSize = Vyrk.type.eyebrow.fontSize * 1.1f), color = c.text, maxLines = 1)
            }
            com.vyrk.tv.ui.components.RuneDivider(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp), centered = false)
            Group.entries.forEach { g ->
                SidebarEntry(g, selected = group == g, onClick = { group = g },
                    modifier = Modifier
                        .focusRequester(groupReqs.getValue(g))
                        .onFocusChanged { if (it.isFocused) group = g }
                        // Right enters the page at its top row, not at whichever setting is level with this
                        // entry, nor at the left-most option (controls sit on the right, so Profile's first
                        // row, Default tab, is further right than Hidden tabs below it).
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { runCatching { contentReq.requestFocus(FocusDirection.Down) }; true } else false
                        }
                        .focusProperties { right = contentReq }
                        .traceFocus("settings:${g.name.lowercase()}"))
            }
            Spacer(Modifier.weight(1f))
            Text(str(R.string.tagline), style = Vyrk.type.labelM, color = c.gold.copy(alpha = 0.8f), modifier = Modifier.padding(start = 12.dp), maxLines = 1)
            Text("VYRK ${BuildConfig.VERSION_NAME}", style = Vyrk.type.labelS, color = c.textMuted, modifier = Modifier.padding(start = 12.dp), maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = Dimens.screenMargin, top = 14.dp)
            // Focus search would pick the sidebar entry level with the focused setting, and the
            // page follows sidebar focus, so Left from a lower setting used to open another group.
            .focusProperties {
                onExit = {
                    when (requestedFocusDirection) {
                        FocusDirection.Left -> runCatching { groupReqs.getValue(group).requestFocus() }
                        // Down past the last setting would land on a sidebar entry below and open that group.
                        FocusDirection.Down -> cancelFocusChange()
                        else -> {}
                    }
                }
            }
            .focusGroup()
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalGroupReq provides groupReqs.getValue(group)) {
            // The group's header stays put; only the rows scroll beneath it.
            Column {
            GroupHeader(group, session)
            // Each group opens at its top: a shared scroll position opened the next group part-way down, so Right
            // entered at a lower row.
            LazyColumn(Modifier.weight(1f).focusRequester(contentReq).focusGroup(), state = remember(group) { androidx.compose.foundation.lazy.LazyListState() }, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 60.dp, start = 2.dp, end = 2.dp)) {
                if (group == Group.ADDONS) {
                    item(key = "addon-actions") { AddonActions(session, typing) { typing = it } }
                    itemsIndexed(addons, key = { _, d -> "addon:" + d.manifest.id }) { i, d -> AddonCard(session, d, i, addons.lastIndex) }
                } else {
                    item(key = group) { GroupContent(session, group) }
                }
            }
            }
            }
        }
    }
}

/** The open group's sidebar entry, for rows to return to on Left. */
private val LocalGroupReq = androidx.compose.runtime.staticCompositionLocalOf<FocusRequester?> { null }

/**
 * A settings row is its own focus group: Left moves between its options, and Left from its first
 * option returns to the group's sidebar entry. Controls sit on the right, so plain focus search
 * would find an option further left in a row above (Left from a switch landed on Interface size).
 */
@Composable
private fun Modifier.leftToSidebar(): Modifier {
    val req = LocalGroupReq.current ?: return this
    return this
        .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Left) runCatching { req.requestFocus() } } }
        .focusGroup()
}

private val TILE_SHAPE = RoundedCornerShape(14.dp)

/**
 * A setting's tile: dark steel glass with a hairline edge. While focus is inside it, the tile
 * lifts slightly and its edge lights cyan, so the row being changed is always obvious.
 */
@Composable
private fun Modifier.settingTile(): Modifier {
    val c = Vyrk.colors
    var within by remember { mutableStateOf(false) }
    val edge by androidx.compose.animation.animateColorAsState(if (within) c.accent.copy(alpha = 0.42f) else c.text.copy(alpha = 0.07f), com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.FAST), label = "tile-edge")
    val fill by androidx.compose.animation.animateColorAsState(if (within) c.raised.copy(alpha = 0.78f) else c.panel.copy(alpha = 0.66f), com.vyrk.tv.ui.theme.motionSpec(com.vyrk.tv.ui.theme.Motion.FAST), label = "tile-fill")
    return this.onFocusChanged { within = it.hasFocus }
        .background(fill, TILE_SHAPE)
        .border(1.dp, edge, TILE_SHAPE)
        .padding(horizontal = 16.dp, vertical = 3.dp)
}

/**
 * Behind Settings: the ambient aurora and mountain light, with the artwork last featured on a
 * catalogue page faint over it, decoded tiny and stretched so it is soft on every TV, blurred
 * further on Android 12+, under a fade that keeps text readable.
 */
@Composable
fun SettingsBackdrop(url: String?) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize()) {
        com.vyrk.tv.ui.components.AmbientBackground()
        if (url != null) {
            val ctx = LocalContext.current
            val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(Size(64, 36)).build() }
            AsyncImage(req, null, contentScale = ContentScale.Crop, alpha = 0.26f,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.shade.copy(alpha = 0.62f), 0.5f to c.shade.copy(alpha = 0.36f), 1f to c.shade.copy(alpha = 0.20f))))
        // A cold aurora breath high on the right, as on Home.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.ambientTeal.copy(alpha = 0.10f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(1500f, 120f), radius = 900f)))
    }
}

@Composable
private fun Setting(label: String, hint: String? = null, icon: Int? = null, content: @Composable () -> Unit) {
    SettingRow(label, hint, Modifier.settingTile().leftToSidebar(), icon = icon) { content() }
}

/**
 * On / Off as a switch. [inPair] is for one half of a [SettingPair]: controls at the right of the
 * half, and the pair supplies the tile and the focus group.
 */
@Composable
private fun Toggle(label: String, value: Boolean, trace: String, hint: String? = null, icon: Int? = null, inPair: Boolean = false, split: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(label, hint, if (inPair) Modifier else Modifier.settingTile().leftToSidebar(), icon = icon, split = split) { OnOffControl(value, trace, str(R.string.on), str(R.string.off), onChange) }
}

/**
 * One of several values. Two values are a joined track; more are separate pills that wrap within
 * the controls column, so short and long lists look the same.
 */
@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, trace: String, hint: String? = null, icon: Int? = null, optionIcons: List<Int?> = emptyList(), inPair: Boolean = false, split: Boolean = true, onChange: (T) -> Unit) {
    SettingRow(label, hint, if (inPair) Modifier else Modifier.settingTile().leftToSidebar(), icon = icon, split = split) {
        if (options.size == 2) SegmentedControl(options.map { it.second }, options.indexOfFirst { it.first == value }, options.indices.map { "$trace:$it" }, { onChange(options[it].first) }, icons = optionIcons)
        else options.forEachIndexed { i, (v, l) -> ChoicePill(l, v == value, { onChange(v) }, "$trace:$i", optionIcons.getOrNull(i)) }
    }
}

/**
 * Two short settings side by side in one tile when each half has room; at the Large interface
 * size they become two tiles instead of squeezing. Each half gets `split`: false beside each
 * other (controls at the right of the half), true when stacked.
 */
@Composable
private fun SettingPair(left: @Composable (split: Boolean) -> Unit, right: @Composable (split: Boolean) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 660.dp) Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).settingTile().leftToSidebar()) {
            Box(Modifier.weight(1f)) { left(false) }
            ColumnDivider(Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            Box(Modifier.weight(1f)) { right(false) }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.settingTile().leftToSidebar()) { left(true) }
            Box(Modifier.settingTile().leftToSidebar()) { right(true) }
        }
    }
}

/**
 * A category. The open one carries a gold bar and a gold-lit icon on dark steel; remote focus is a
 * frosted cyan pill with a lit edge. They combine when the open category also has focus.
 */
@Composable
private fun SidebarEntry(g: Group, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.fillMaxWidth().height(42.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.steel.copy(alpha = 0.75f) else Color.Transparent, contentColor = if (selected) c.text else c.textSecondary,
            focusedContainerColor = c.accent.copy(alpha = 0.22f), focusedContentColor = c.text,
            pressedContainerColor = c.accent.copy(alpha = 0.35f), pressedContentColor = c.text,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.accent), shape = shape),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(c.accent.copy(alpha = 0.32f), 10.dp)),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(20.dp).background(if (selected) c.gold else Color.Transparent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(9.dp))
            Box(
                Modifier.size(28.dp).background(if (selected) c.gold.copy(alpha = 0.14f) else c.steel.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .border(1.dp, if (selected) c.gold.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                com.vyrk.tv.ui.components.Icon(g.icon, null, Modifier.size(15.dp), tint = when { focused -> c.accent; selected -> c.gold; else -> c.textSecondary })
            }
            Spacer(Modifier.width(11.dp))
            Text(str(g.title), style = Vyrk.type.titleS, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * Page header: the group's icon in a gold-edged badge, its name in the cinematic serif and what it
 * covers, over a rune-cut rule. Subtitles also show a live preview of the current subtitle style
 * here, where it stays in view while the options below change.
 */
@Composable
private fun GroupHeader(g: Group, session: ProfileSession) {
    val c = Vyrk.colors
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(Brush.linearGradient(listOf(c.steel, c.panel)), RoundedCornerShape(12.dp))
                    .border(1.dp, c.gold.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { com.vyrk.tv.ui.components.Icon(g.icon, null, Modifier.size(21.dp), tint = c.gold) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(str(g.title), style = Vyrk.type.cinematicM, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(str(g.subtitle), style = Vyrk.type.bodyM, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (g == Group.SUBTITLES) SubtitlePreview(session)
        }
        com.vyrk.tv.ui.components.RuneDivider(Modifier.padding(top = 10.dp), centered = false)
    }
}

/** The current subtitle style on a dark film frame, drawn exactly as the player draws it. */
@Composable
private fun SubtitlePreview(session: ProfileSession) {
    val c = Vyrk.colors
    val s by session.settings.collectAsState()
    val (fg, bg, scale) = com.vyrk.tv.player.subtitleStyleOf(s.subtitleColor, s.subtitleBackground, s.subtitleSize)
    Box(
        Modifier.size(300.dp, 76.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A3A44), Color(0xFF0E1A21), Color(0xFF06090C))))
            .border(1.dp, c.text.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            str(R.string.subtitle_preview), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = fg, maxLines = 2,
            style = Vyrk.type.headlineS.copy(fontSize = Vyrk.type.headlineS.fontSize * scale, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
            modifier = Modifier.padding(bottom = 8.dp, start = 8.dp, end = 8.dp).background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** What this TV can play, as a read-only panel. Focusable so Down reaches it and scrolls it into view. */
@Composable
private fun CapabilityPanel(items: List<Pair<String, String>>) {
    val c = Vyrk.colors
    Column(Modifier.fillMaxWidth().settingTile().leftToSidebar().traceFocus("set:this_tv").focusable().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.vyrk.tv.ui.components.RuneGlyph(10.dp, color = c.gold)
            Spacer(Modifier.width(8.dp))
            Text(str(R.string.this_tv).uppercase(), style = Vyrk.type.eyebrow, color = c.gold, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (k, v) ->
                Column(Modifier.width(152.dp).background(c.shade.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(k, style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        v, style = Vyrk.type.titleS, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = when (v) { "✓" -> c.success; "✗" -> c.textMuted; else -> c.text },
                    )
                }
            }
        }
    }
}

private val LANG_CHOICES = listOf("eng", "spa", "por", "fre", "ger", "ita", "hin", "jpn", "kor")

@Composable
private fun GroupContent(session: ProfileSession, group: Group) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val s by session.settings.collectAsState()
    val d by graph.deviceSettings.collectAsState()
    val c = Vyrk.colors
    fun upd(f: (com.vyrk.tv.data.ProfileSettings) -> com.vyrk.tv.data.ProfileSettings) = session.updateSettings(f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (group) {
            Group.PROFILE -> {
                val defaultTabs = listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES, Tab.DISCOVER, Tab.ANIME, Tab.LIBRARY)
                Choice(str(R.string.default_tab), defaultTabs.map { it.key to tabLabel(it) }, s.defaultTab, "set:default_tab", str(R.string.default_tab_desc), R.drawable.ic_home, defaultTabs.map { tabIcon(it) }) { v -> upd { it.copy(defaultTab = v) } }
                SettingRow(str(R.string.hidden_tabs), str(R.string.hidden_tabs_desc), Modifier.settingTile().leftToSidebar(), icon = R.drawable.ic_eye_off) {
                    listOf(Tab.MOVIES, Tab.SERIES, Tab.DISCOVER, Tab.ANIME, Tab.LIBRARY).forEach { t ->
                        CheckChip(tabLabel(t), t.key in s.hiddenTabs, { upd { it.copy(hiddenTabs = if (t.key in it.hiddenTabs) it.hiddenTabs - t.key else it.hiddenTabs + t.key) } }, "set:hide_tab:${t.key}")
                    }
                }
                SettingPair(
                    { split -> Toggle(str(R.string.featured_hero), s.heroEnabled, "set:hero", str(R.string.featured_hero_desc), R.drawable.ic_image, inPair = true, split = split) { v -> upd { it.copy(heroEnabled = v) } } },
                    { split -> Toggle(str(R.string.hero_rotation), s.heroRotation, "set:hero_rotation", str(R.string.hero_rotation_desc), R.drawable.ic_refresh, inPair = true, split = split) { v -> upd { it.copy(heroRotation = v) } } },
                )
                Choice(str(R.string.poster_size), listOf(104 to str(R.string.size_small), 118 to str(R.string.size_medium), 136 to str(R.string.size_large)), s.posterSize, "set:poster", str(R.string.poster_size_desc), R.drawable.ic_crop) { v -> upd { it.copy(posterSize = v) } }
                // Its own row: six swatches with their names need the full controls column.
                Setting(str(R.string.accent_colour), str(R.string.accent_desc), R.drawable.ic_drop) {
                    AccentChoice.entries.forEachIndexed { i, a ->
                        ColorSwatch(a.color, a.onColor, accentName(a), s.accent == a.name, { upd { it.copy(accent = a.name) } }, "set:accent:$i")
                    }
                }
                Toggle(str(R.string.reduce_motion), s.reduceMotion, "set:reduce_motion", str(R.string.reduce_motion_desc), R.drawable.ic_motion) { v -> upd { it.copy(reduceMotion = v) } }
                Toggle(str(R.string.blur_unwatched), s.blurUnwatched, "set:blur", str(R.string.blur_unwatched_desc), R.drawable.ic_blur) { v -> upd { it.copy(blurUnwatched = v) } }
                Setting(str(R.string.hidden_rows), str(R.string.hidden_rows_desc), R.drawable.ic_rows) {
                    if (s.hiddenRows.isNotEmpty()) VyrkButton(str(R.string.restore_rows, s.hiddenRows.size), onClick = { upd { it.copy(hiddenRows = emptySet()) } }, style = ButtonStyle.OUTLINE, height = 32.dp, shape = Dimens.pillShape, modifier = Modifier.traceFocus("set:restore_rows"))
                    else Text(str(R.string.none_hidden), style = Vyrk.type.labelL, color = Vyrk.colors.textMuted)
                }
                if (s.top10CardDismissed) Setting(str(R.string.top10_suggestion), str(R.string.top10_suggestion_desc), R.drawable.ic_trending) {
                    VyrkButton(str(R.string.show_again), onClick = { upd { it.copy(top10CardDismissed = false) } }, style = ButtonStyle.OUTLINE, height = 32.dp, shape = Dimens.pillShape, modifier = Modifier.traceFocus("set:show_top10"))
                }
            }
            Group.STREAMS -> {
                Choice(str(R.string.source_preset), listOf(Preset.BALANCED to str(R.string.preset_balanced), Preset.BEST_PICTURE to str(R.string.preset_best), Preset.FAST_START to str(R.string.preset_fast), Preset.ADDON_ORDER to str(R.string.preset_addon_order)), s.preset, "set:preset", str(R.string.preset_hint)) { v -> upd { it.copy(preset = v) } }
                Choice(str(R.string.max_resolution), listOf(null to str(R.string.any), 2160 to "4K", 1080 to "1080p", 720 to "720p"), s.maxResolution, "set:max_res") { v -> upd { it.copy(maxResolution = v) } }
                Choice(str(R.string.max_file_size), listOf(null to str(R.string.any), 50 to "50 GB", 20 to "20 GB", 10 to "10 GB", 4 to "4 GB"), s.maxSizeGb, "set:max_size") { v -> upd { it.copy(maxSizeGb = v) } }
                Choice(str(R.string.preferred_format), listOf(FormatPref.ANY to str(R.string.any), FormatPref.REMUX to "Remux", FormatPref.ENCODE to str(R.string.encode), FormatPref.WEB to "WEB"), s.format, "set:format") { v -> upd { it.copy(format = v) } }
                Choice(str(R.string.preferred_hdr), listOf(HdrPref.ANY to str(R.string.any), HdrPref.DOLBY_VISION to "Dolby Vision", HdrPref.HDR10 to "HDR10", HdrPref.SDR to "SDR"), s.hdr, "set:hdr") { v -> upd { it.copy(hdr = v) } }
                Toggle(str(R.string.always_show_sources), s.alwaysShowSources, "set:always_sources") { v -> upd { it.copy(alwaysShowSources = v) } }
                Toggle(str(R.string.sources_on_info), s.sourcesOnInfoPage, "set:sources_info") { v -> upd { it.copy(sourcesOnInfoPage = v) } }
                Toggle(str(R.string.load_sources_on_open), s.loadSourcesOnOpen, "set:load_sources") { v -> upd { it.copy(loadSourcesOnOpen = v) } }
                Toggle(str(R.string.autoplay_next), s.autoplayNext, "set:autoplay") { v -> upd { it.copy(autoplayNext = v) } }
                Toggle(str(R.string.ask_still_watching), s.askStillWatching, "set:still_watching") { v -> upd { it.copy(askStillWatching = v) } }
                Choice(str(R.string.setting_skip_intro), listOf(com.vyrk.tv.data.SkipIntroMode.BUTTON to str(R.string.skip_show_button), com.vyrk.tv.data.SkipIntroMode.AUTOMATIC to str(R.string.skip_automatic), com.vyrk.tv.data.SkipIntroMode.OFF to str(R.string.off)), s.skipIntro, "set:skip_intro", str(R.string.skip_intro_hint)) { v -> upd { it.copy(skipIntro = v) } }
                Toggle(str(R.string.setting_chapter_markers), s.chapterMarkers, "set:chapter_markers", str(R.string.chapter_markers_hint)) { v -> upd { it.copy(chapterMarkers = v) } }
            }
            Group.PICTURE -> {
                Choice(str(R.string.interface_size), listOf("SMALL" to str(R.string.size_small), "MEDIUM" to str(R.string.size_medium), "LARGE" to str(R.string.size_large)), d.interfaceSize, "set:ui_size") { v -> graph.updateDevice { it.copy(interfaceSize = v) } }
                Toggle(str(R.string.frame_rate_matching), d.frameRateMatching, "set:frame_rate") { v -> graph.updateDevice { it.copy(frameRateMatching = v) } }
                Toggle(str(R.string.passthrough), d.passthrough, "set:passthrough", str(R.string.passthrough_hint)) { v -> graph.updateDevice { it.copy(passthrough = v) } }
                Toggle(str(R.string.tv_speaker_mode), d.tvSpeakerMode, "set:tv_speaker") { v -> graph.updateDevice { it.copy(tvSpeakerMode = v) } }
                Toggle(str(R.string.software_audio), d.softwareAudio, "set:software_audio") { v -> graph.updateDevice { it.copy(softwareAudio = v) } }
                Toggle(str(R.string.clear_dialogue), d.clearDialogue, "set:clear_dialogue", str(R.string.clear_dialogue_hint)) { v -> graph.updateDevice { it.copy(clearDialogue = v) } }
                val r = graph.device.report
                val unknown = str(R.string.unknown)
                fun yn(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> unknown }
                listOf(
                    "H.264" to (r.decoders[com.vyrk.core.stream.VideoCodec.H264]?.let { "${it}p" } ?: unknown),
                    "HEVC" to (r.decoders[com.vyrk.core.stream.VideoCodec.HEVC]?.let { "${it}p" } ?: unknown),
                    "HEVC 10-bit" to yn(r.hevc10),
                    str(R.string.dolby_vision_decoder) to yn(r.dolbyVisionDecoder),
                    "AV1" to (r.decoders[com.vyrk.core.stream.VideoCodec.AV1]?.let { "${it}p" } ?: unknown),
                    str(R.string.display) to if (r.displayWidth > 0) "${r.displayWidth}×${r.displayHeight}" else unknown,
                    "HDR10" to yn(r.hdr10), "HDR10+" to yn(r.hdr10Plus), "Dolby Vision" to yn(r.dolbyVision), "HLG" to yn(r.hlg),
                    str(R.string.audio_formats) to r.audioEncodings.joinToString(", ").ifEmpty { "PCM" },
                    str(R.string.max_channels) to r.maxChannels.toString(),
                ).let { CapabilityPanel(it) }
            }
            Group.LANGUAGES -> {
                Choice(str(R.string.app_language), listOf("" to str(R.string.system_default), "en" to "English", "es" to "Español", "pt-BR" to "Português (Brasil)", "fr" to "Français", "de" to "Deutsch", "hi" to "हिन्दी", "it" to "Italiano"), d.appLanguage, "set:app_lang") { v ->
                    graph.updateDevice { it.copy(appLanguage = v) }; activity.setAppLanguage(v)
                }
                Setting(str(R.string.audio_languages), str(R.string.languages_order_hint)) {
                    LANG_CHOICES.forEach { l -> CheckChip(Languages.label(l), l in s.audioLanguages, { upd { it.copy(audioLanguages = if (l in it.audioLanguages) it.audioLanguages - l else it.audioLanguages + l) } }, "set:audio_lang:$l") }
                }
                Toggle(str(R.string.original_language), s.originalLanguage, "set:original_lang") { v -> upd { it.copy(originalLanguage = v) } }
                Toggle(str(R.string.avoid_commentary), s.avoidCommentary, "set:avoid_commentary") { v -> upd { it.copy(avoidCommentary = v) } }
            }
            Group.SUBTITLES -> {
                Choice(str(R.string.subtitle_mode), listOf(SubtitleMode.ALWAYS to str(R.string.sub_always), SubtitleMode.FOREIGN_ONLY to str(R.string.sub_foreign), SubtitleMode.OFF to str(R.string.off)), s.subtitleMode, "set:sub_mode") { v -> upd { it.copy(subtitleMode = v) } }
                Setting(str(R.string.subtitle_languages)) {
                    LANG_CHOICES.forEach { l -> CheckChip(Languages.label(l), l in s.subtitleLanguages, { upd { it.copy(subtitleLanguages = if (l in it.subtitleLanguages) it.subtitleLanguages - l else it.subtitleLanguages + l) } }, "set:sub_lang:$l") }
                }
                Choice(str(R.string.subtitle_size), listOf(80 to "80%", 100 to "100%", 125 to "125%", 150 to "150%"), s.subtitleSize, "set:sub_size") { v -> upd { it.copy(subtitleSize = v) } }
                Choice(str(R.string.subtitle_colour), listOf("WHITE" to str(R.string.colour_white), "YELLOW" to str(R.string.colour_yellow), "CYAN" to str(R.string.colour_frost)), s.subtitleColor, "set:sub_colour") { v -> upd { it.copy(subtitleColor = v) } }
                Choice(str(R.string.subtitle_background), listOf("SHADOW" to str(R.string.bg_shadow), "BOX" to str(R.string.bg_box), "NONE" to str(R.string.none)), s.subtitleBackground, "set:sub_bg") { v -> upd { it.copy(subtitleBackground = v) } }
            }
            Group.PREVIEWS -> {
                Choice(str(R.string.previews), listOf(PreviewMode.OFF to str(R.string.off), PreviewMode.MANUAL to str(R.string.preview_manual), PreviewMode.AUTOMATIC to str(R.string.preview_automatic)), s.previews, "set:previews") { v -> upd { it.copy(previews = v) } }
                Toggle(str(R.string.preview_sound), s.previewSound, "set:preview_sound") { v -> upd { it.copy(previewSound = v) } }
            }
            Group.ADDONS -> {}
            Group.ABOUT -> AboutGroup(session)
        }
    }
}

@Composable
private fun accentName(a: AccentChoice) = when (a) {
    AccentChoice.FROST -> str(R.string.accent_frost); AccentChoice.BLUE -> str(R.string.accent_blue); AccentChoice.TEAL -> str(R.string.accent_teal)
    AccentChoice.VIOLET -> str(R.string.accent_violet); AccentChoice.AMBER -> str(R.string.accent_amber); AccentChoice.PINK -> str(R.string.accent_pink)
}

@Composable
private fun AboutGroup(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val d by graph.deviceSettings.collectAsState()
    val account by session.account.collectAsState()
    val updater = remember { Updater(graph.context, graph.http) }
    var status by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Updater.Check.Available?>(null) }
    val c = Vyrk.colors
    val upToDate = str(R.string.up_to_date)
    val failedFmt = R.string.update_failed
    val ctx = LocalActivity.current
    // The VYRK crest: the gold V, the name in spaced serif capitals, the tagline and this build.
    Row(
        Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(c.steel.copy(alpha = 0.72f), c.panel.copy(alpha = 0.5f))), RoundedCornerShape(16.dp))
            .border(1.dp, c.gold.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.vyrk.tv.ui.components.VyrkMark(54.dp, accent = c.gold, highlight = Color(0xFFF3DEB0))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("VYRK", color = c.text, fontFamily = com.vyrk.tv.ui.theme.NorseDisplay, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 30.sp, letterSpacing = 9.sp, maxLines = 1)
            Text(str(R.string.tagline).uppercase(), style = Vyrk.type.eyebrow, color = c.gold, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text(str(R.string.about_body), style = Vyrk.type.bodyS, color = c.textSecondary)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.background(c.shade.copy(alpha = 0.5f), Dimens.pillShape).border(1.dp, c.text.copy(alpha = 0.14f), Dimens.pillShape).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Vyrk.type.labelL, color = c.text, maxLines = 1)
        }
    }
    when (val a = account) {
        is AccountState.Connected -> Setting(str(R.string.stremio_account), a.email) { VyrkButton(str(R.string.sign_out), onClick = { scope.launch { session.logout() } }, modifier = Modifier.traceFocus("about:logout")) }
        is AccountState.Expired -> Setting(str(R.string.stremio_account), str(R.string.session_expired)) { VyrkButton(str(R.string.reconnect), style = ButtonStyle.PRIMARY, onClick = { app.screen = Screen.Pairing(session.profile.id) }) }
        AccountState.None -> Setting(str(R.string.stremio_account), str(R.string.no_account)) { VyrkButton(str(R.string.sign_in_stremio), onClick = { app.screen = Screen.Pairing(session.profile.id) }, modifier = Modifier.traceFocus("about:sign_in")) }
    }
    if (!BuildConfig.STORE_BUILD) {
        Setting(str(R.string.updates), str(R.string.update_source, BuildConfig.UPDATE_REPO.ifEmpty { "—" })) {
            VyrkButton(str(R.string.check_for_updates), onClick = {
                status = null
                scope.launch {
                    when (val r = updater.check()) {
                        is Updater.Check.Available -> { available = r; status = r.release.name ?: r.release.tag_name }
                        is Updater.Check.Failed -> status = ctx.getString(failedFmt, r.reason)
                        else -> status = upToDate
                    }
                    graph.updateDevice { it.copy(lastUpdateCheckMs = System.currentTimeMillis()) }
                }
            }, modifier = Modifier.traceFocus("about:check_updates"))
            CheckChip(str(R.string.automatic_updates), d.autoUpdateChecks, { graph.updateDevice { it.copy(autoUpdateChecks = !it.autoUpdateChecks) } }, "about:auto_checks")
        }
        status?.let { Text(it, style = Vyrk.type.bodyM, color = c.textSecondary) }
        available?.let { a ->
            Text(a.release.body.orEmpty().take(600), style = Vyrk.type.bodyS, color = c.textMuted)
            VyrkButton(str(R.string.download_install), style = ButtonStyle.PRIMARY, onClick = {
                if (!updater.canInstall()) { updater.requestInstallPermission(); return@VyrkButton }
                scope.launch {
                    updater.download(a.asset) { p -> status = "${(p * 100).toInt()}%" }
                        .onSuccess { updater.install(it) }
                        .onFailure { status = ctx.getString(failedFmt, it.message ?: "") }
                }
            })
        }
    }
    Spacer(Modifier.height(10.dp))
    WrapRow {
        VyrkButton(str(R.string.third_party_notices), onClick = {
            val text = graph.context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
            app.push(Overlay.Message(ctx.getString(R.string.third_party_notices), text))
        }, modifier = Modifier.traceFocus("about:notices"))
        val copied = str(R.string.diagnostics_copied)
        VyrkButton(str(R.string.copy_diagnostics), onClick = {
            val r = graph.device.report
            val text = buildString {
                appendLine("VYRK ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
                appendLine("decoders=${r.decoders} display=${r.displayWidth}x${r.displayHeight} hdr10=${r.hdr10} dv=${r.dolbyVision}")
                appendLine("addons=${session.addons.value.size} library=${session.library.value.size}")
                Diagnostics.dump().forEach { appendLine(it) }
            }
            (graph.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VYRK diagnostics", text))
            app.showToast(copied)
        }, modifier = Modifier.traceFocus("about:diagnostics"))
    }
}
