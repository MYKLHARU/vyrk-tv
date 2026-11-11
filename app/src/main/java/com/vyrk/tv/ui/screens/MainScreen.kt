package com.vyrk.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import com.vyrk.tv.ui.theme.motionSpec
import com.vyrk.tv.ui.theme.Motion
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.player.Playback
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Origin
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.BrowseModel
import com.vyrk.tv.ui.browse.BrowsePage
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.Wordmark
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus

@Composable
fun MainScreen(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val settings by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val loaded by session.loaded.collectAsState()

    val tabs = remember(addons, settings.hiddenTabs, false) {
        Tab.entries.filter { t ->
                            (t != Tab.LIVE || CatalogRules.hasLiveCatalogs(addons)) && (t == Tab.HOME || t.key !in settings.hiddenTabs)
        }
    }
    LaunchedEffect(tabs) {
        if (app.tab !in tabs) { app.tab = Tab.HOME; app.focusTabsTick++ }
    }
    // One permanent requester per tab and per right-side button.
    val tabReqs = remember { Tab.entries.associateWith { FocusRequester() } }
    val searchReq = remember { FocusRequester() }
    val settingsReq = remember { FocusRequester() }
    var discoverVisited by remember(session) { mutableStateOf(false) }
    var navHasFocus by remember { mutableStateOf(false) }

    // Default tab once per session.
    LaunchedEffect(session.profile.id) {
        app.tab = Tab.entries.firstOrNull { it.key == settings.defaultTab && it in tabs } ?: Tab.HOME
        app.section = Section.TABS
    }
    LaunchedEffect(app.focusTabsTick) {
        if (app.focusTabsTick > 0) runCatching {
            when (app.section) {
                Section.SEARCH -> searchReq.requestFocus()
                Section.SETTINGS -> settingsReq.requestFocus()
                Section.TABS -> tabReqs.getValue(app.tab).requestFocus()
            }
        }
    }

    val rowStore = remember(session) { com.vyrk.tv.ui.browse.RowStore() }
    val models = remember(session, false, settings.animeGenre) { mutableMapOf<Tab, BrowseModel>() }
    fun model(t: Tab) = models.getOrPut(t) { BrowseModel(graph.addonClient, t, session.scope, rowStore, false, null) }

    // Build every catalogue tab as soon as the add-ons are known and start its first rows,
    // the open tab first, so switching to Movies or Series finds them already loaded.
    LaunchedEffect(addons, settings.hiddenRows, tabs, false, settings.animeGenre) {
        val list = session.effectiveAddons()
        (listOf(app.tab) + tabs).distinct()
            .filter { it == Tab.HOME || it == Tab.MOVIES || it == Tab.SERIES || it == Tab.ANIME || it == Tab.LIVE }
            .forEach { t -> model(t).rebuild(list, settings.hiddenRows); model(t).prefetch(if (t == app.tab) com.vyrk.tv.ui.browse.HERO_SOURCE_ROWS else 3) }
    }


    // Catalogue pages visited this session stay composed while hidden (not measured, drawn or
    // focusable), so coming back shows them as they were instead of rebuilding them.
    val keptPages = remember(session, false, settings.animeGenre) { mutableListOf<Tab>() }
    // A kept page is shown in the same frame as the tab highlight (it costs a few milliseconds).
    // A page not built yet follows one frame later, so the highlight is never held back by
    // building it; a further press before then skips it (Home → Anime builds neither Movies nor
    // Series on the way).
    var laggedTab by remember { mutableStateOf(app.tab) }
    val shownTab = com.vyrk.tv.data.visibleTab(if (app.tab in keptPages) app.tab else laggedTab, tabs)
    LaunchedEffect(app.tab) {
        val target = app.tab
        val kept = target in keptPages
        if (kept) laggedTab = target
        androidx.compose.runtime.withFrameNanos { }
        com.vyrk.tv.ui.Perf.log("tab ${target.key} key=${com.vyrk.tv.ui.Perf.keyName} highlight+${com.vyrk.tv.ui.Perf.sinceKey()}ms")
        if (!kept && app.tab == target) laggedTab = target
    }
    LaunchedEffect(shownTab) {
        androidx.compose.runtime.withFrameNanos { }
        com.vyrk.tv.ui.Perf.log("page ${shownTab.key} key=${com.vyrk.tv.ui.Perf.keyName} drawn+${com.vyrk.tv.ui.Perf.sinceKey()}ms")
    }


    val onPlay: (MetaPreview, Origin?) -> Unit = { meta, origin -> Playback.start(activity, graph, session, meta, origin = origin) }

    // Back: innermost layer first (overlays handle their own), then section → Home → tab strip.
    BackHandler(enabled = app.overlays.isEmpty() && !(navHasFocus && app.section == Section.TABS && app.tab == Tab.HOME)) {
        when {
            app.section != Section.TABS -> { app.section = Section.TABS; app.focusTabsTick++ }
            app.tab != Tab.HOME -> { app.tab = Tab.HOME; app.focusTabsTick++ }
            else -> app.focusTabsTick++
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Settings sits on glass over the last featured artwork, blurred, reaching up behind the bar.
        // Library, Search and Profiles have no artwork: they sit in the ambient teal/violet light.
        if (app.section == Section.SETTINGS) SettingsBackdrop(app.backdrop)
        else if (app.section != Section.TABS || shownTab in setOf(Tab.LIBRARY, Tab.DISCOVER)) com.vyrk.tv.ui.components.AmbientBackground()
        // Pages start under the bar; artwork may reach up behind it.
        Box(Modifier.fillMaxSize().padding(top = Dimens.navHeight)) {
            if (!loaded) return@Box
            when (app.section) {
                Section.SEARCH -> SearchScreen(session)
                Section.SETTINGS -> SettingsScreen(session)
                Section.TABS -> if (shownTab == Tab.LIBRARY) LibraryScreen(session, onPlay)
            }
            val onTabs = app.section == Section.TABS
            if (onTabs && shownTab == Tab.DISCOVER) discoverVisited = true
            if (discoverVisited) Box(Modifier.fillMaxSize().keepAlive(onTabs && shownTab == Tab.DISCOVER)) { DiscoveryScreen(session) }
            if (onTabs && shownTab.isCatalogue() && shownTab !in keptPages) keptPages += shownTab
            keptPages.removeAll { it !in tabs }
            keptPages.forEach { t ->
                androidx.compose.runtime.key(session, t, false, settings.animeGenre) {
                    val visible = onTabs && shownTab == t
                    Box(Modifier.fillMaxSize().keepAlive(visible)) { BrowsePage(session, model(t), t.key, onPlay, active = visible) }
                }
            }
        }
        // The bar has no solid edge: it fades from the page colour down into the artwork (catalogue
        // pages, and Settings over its blurred backdrop). Library, Search and Profiles get a lighter
        // fade over the ambient light, so the bar never reads as a solid band.
        val artwork = (app.section == Section.TABS && shownTab !in setOf(Tab.LIBRARY, Tab.DISCOVER)) || app.section == Section.SETTINGS
        // Light enough not to tint the artwork (it was 94 % navy at the top), dark enough for the tabs.
        val shade = Vyrk.colors.shade
        if (artwork) Box(Modifier.fillMaxWidth().height(Dimens.navHeight + 56.dp).background(Brush.verticalGradient(
            0f to shade.copy(alpha = 0.78f), 0.45f to shade.copy(alpha = 0.42f), 0.75f to shade.copy(alpha = 0.12f), 1f to Color.Transparent,
        )))
        else Box(Modifier.fillMaxWidth().height(Dimens.navHeight).background(Brush.verticalGradient(0f to shade.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Row(
            Modifier.fillMaxWidth().height(Dimens.navHeight)
                .padding(horizontal = Dimens.screenMargin)
                .onFocusChanged { navHasFocus = it.hasFocus; app.navFocused = it.hasFocus }
                .focusProperties {
                    onEnter = {
                        val target = when (app.section) {
                            Section.SEARCH -> searchReq; Section.SETTINGS -> settingsReq
                            Section.TABS -> tabReqs.getValue(app.tab)
                        }
                        runCatching { target.requestFocus() }
                    }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark(20.dp)
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { t ->
                    NavTab(
                        label = tabLabel(t),
                        icon = tabIcon(t),
                        active = app.section == Section.TABS && app.tab == t,
                        requester = tabReqs.getValue(t),
                        onSelect = { app.section = Section.TABS; app.tab = t },
                        trace = "tab:${t.key}",
                    )
                }
            }
            // Search takes what the tabs leave, up to a compact field; it shrinks before anything overlaps.
            Box(Modifier.weight(1f).padding(start = 16.dp), contentAlignment = Alignment.CenterEnd) {
                SearchPill(app.section == Section.SEARCH, searchReq, Modifier.widthIn(max = 188.dp).fillMaxWidth()) { app.section = Section.SEARCH }
            }
            Spacer(Modifier.width(10.dp))
            NavGear(active = app.section == Section.SETTINGS, settingsReq) { app.section = Section.SETTINGS }
        }

    }
}

@Composable
fun tabLabel(t: Tab): String = when (t) {
    Tab.HOME -> str(R.string.tab_home)
    Tab.MOVIES -> str(R.string.tab_movies)
    Tab.SERIES -> str(R.string.tab_series)
    Tab.DISCOVER -> str(R.string.tab_discover)
    Tab.ANIME -> str(R.string.tab_anime)
    Tab.LIVE -> str(R.string.tab_live)
    Tab.LIBRARY -> str(R.string.tab_library)
}

fun tabIcon(t: Tab): Int = when (t) {
    Tab.HOME -> R.drawable.ic_home
    Tab.MOVIES -> R.drawable.ic_movie
    Tab.SERIES -> R.drawable.ic_tv
    Tab.DISCOVER -> R.drawable.ic_search
    Tab.ANIME -> R.drawable.ic_anime
    Tab.LIVE -> R.drawable.ic_live
    Tab.LIBRARY -> R.drawable.ic_library
}

/**
 * A top-bar tab: icon and label. The open tab is a cyan-edged pill with a soft glow; the focused
 * tab fills with the accent. Changes animate unless Reduce motion is on.
 */
@Composable
private fun NavTab(label: String, icon: Int, active: Boolean, requester: FocusRequester, onSelect: () -> Unit, trace: String) {
    var focused by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    val fill by androidx.compose.animation.animateColorAsState(
        when { focused -> c.accent; active -> c.accent.copy(alpha = 0.16f); else -> Color.Transparent }, motionSpec(Motion.FAST), label = "tab-fill")
    val edge by androidx.compose.animation.animateColorAsState(
        when { focused -> c.accent; active -> c.accent.copy(alpha = 0.6f); else -> Color.Transparent }, motionSpec(Motion.FAST), label = "tab-edge")
    val ink = when { focused -> c.onAccent; active -> c.text; else -> c.textSecondary }
    Row(
        Modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
            .traceFocus(trace)
            .semantics { role = Role.Tab; contentDescription = label }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onSelect(); true } else false }
            .focusable()
            .then(if (active || focused) Modifier.shadow(if (focused) 16.dp else 10.dp, Dimens.pillShape, clip = false, ambientColor = c.accent, spotColor = c.accent) else Modifier)
            .background(fill, Dimens.pillShape)
            .border(1.dp, edge, Dimens.pillShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = ink)
        Text(label, style = Vyrk.type.titleS, color = ink, maxLines = 1)
    }
}

/** Compact search field in the top bar; OK opens Search. */
@Composable
private fun SearchPill(active: Boolean, requester: FocusRequester, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    val desc = str(R.string.search)
    Row(
        modifier.height(36.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .traceFocus("nav:search")
            .semantics { contentDescription = desc; role = Role.Button }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onSelect(); true } else false }
            .focusable()
            .then(if (focused) Modifier.shadow(14.dp, Dimens.pillShape, clip = false, ambientColor = c.accent, spotColor = c.accent) else Modifier)
            .background(if (focused) c.text else c.raised.copy(alpha = 0.55f), Dimens.pillShape)
            .border(1.dp, if (active && !focused) c.accent.copy(alpha = 0.7f) else c.glassEdge, Dimens.pillShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(R.drawable.ic_search, null, Modifier.size(15.dp), tint = if (focused) c.background else c.textSecondary)
        Text(str(R.string.search), style = Vyrk.type.labelL, color = if (focused) c.background else c.textMuted, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Settings, as a round gear button; ringed while Settings is open. */
@Composable
private fun NavGear(active: Boolean, requester: FocusRequester, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    val desc = str(R.string.settings)
    Box(
        Modifier.size(38.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .traceFocus("nav:settings")
            .semantics { contentDescription = desc; role = Role.Button }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onSelect(); true } else false }
            .focusable()
            .then(if (focused) Modifier.shadow(14.dp, CircleShape, clip = false, ambientColor = c.accent, spotColor = c.accent) else Modifier)
            .background(if (focused) c.text else c.raised.copy(alpha = 0.55f), CircleShape)
            .border(1.dp, when { focused -> c.text; active -> c.accent.copy(alpha = 0.7f); else -> c.glassEdge }, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(R.drawable.ic_cog, null, Modifier.size(18.dp), tint = when { focused -> c.background; active -> c.accent; else -> c.textSecondary })
    }
}

private fun Tab.isCatalogue() = this == Tab.HOME || this == Tab.MOVIES || this == Tab.SERIES || this == Tab.ANIME || this == Tab.LIVE

/**
 * Hidden pages are not measured or placed: no layout or drawing work, and lazy lists keep their
 * composed items. Unplaced nodes are skipped by focus search, so the remote never reaches them.
 */
private fun Modifier.keepAlive(visible: Boolean): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        if (!visible) return@layout layout(0, 0) {}
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { p.place(0, 0) }
    }
)
