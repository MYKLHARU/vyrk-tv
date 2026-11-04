package com.vyrk.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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

    val tabs = remember(addons, settings.hiddenTabs) {
        Tab.entries.filter { t ->
            (t != Tab.LIVE || CatalogRules.hasLiveCatalogs(addons)) && (t == Tab.HOME || t.key !in settings.hiddenTabs)
        }
    }
    // One permanent requester per tab and per right-side button.
    val tabReqs = remember { Tab.entries.associateWith { FocusRequester() } }
    val searchReq = remember { FocusRequester() }
    val profileReq = remember { FocusRequester() }
    val settingsReq = remember { FocusRequester() }
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
                Section.PROFILES -> profileReq.requestFocus()
                Section.TABS -> tabReqs.getValue(app.tab).requestFocus()
            }
        }
    }

    val rowStore = remember(session) { com.vyrk.tv.ui.browse.RowStore() }
    val models = remember(session) { mutableMapOf<Tab, BrowseModel>() }
    fun model(t: Tab) = models.getOrPut(t) { BrowseModel(session, graph.addonClient, t, session.scope, rowStore) }

    // Build every catalogue tab as soon as the add-ons are known and start its first rows,
    // the open tab first, so switching to Movies or Series finds them already loaded.
    LaunchedEffect(addons, settings.hiddenRows, tabs) {
        val list = session.effectiveAddons()
        (listOf(app.tab) + tabs).distinct()
            .filter { it == Tab.HOME || it == Tab.MOVIES || it == Tab.SERIES || it == Tab.ANIME || it == Tab.LIVE }
            .forEach { t -> model(t).rebuild(list, settings.hiddenRows); model(t).prefetch(if (t == app.tab) com.vyrk.tv.ui.browse.HERO_SOURCE_ROWS else 3) }
    }


    // Catalogue pages visited this session stay composed while hidden (not measured, drawn or
    // focusable), so coming back shows them as they were instead of rebuilding them.
    val keptPages = remember(session) { mutableListOf<Tab>() }
    // A kept page is shown in the same frame as the tab highlight (it costs a few milliseconds).
    // A page not built yet follows one frame later, so the highlight is never held back by
    // building it; a further press before then skips it (Home → Anime builds neither Movies nor
    // Series on the way).
    var laggedTab by remember { mutableStateOf(app.tab) }
    val shownTab = if (app.tab in keptPages) app.tab else laggedTab
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
        if (app.section == Section.SETTINGS) SettingsBackdrop(app.backdrop)
        // Pages start under the bar; artwork may reach up behind it.
        Box(Modifier.fillMaxSize().padding(top = Dimens.navHeight)) {
            if (!loaded) return@Box
            when (app.section) {
                Section.SEARCH -> SearchScreen(session)
                Section.SETTINGS -> SettingsScreen(session)
                Section.PROFILES -> ProfilesScreen(session)
                Section.TABS -> if (shownTab == Tab.LIBRARY) LibraryScreen(session, onPlay)
            }
            val onTabs = app.section == Section.TABS
            if (onTabs && shownTab.isCatalogue() && shownTab !in keptPages) keptPages += shownTab
            keptPages.removeAll { it !in tabs }
            keptPages.forEach { t ->
                androidx.compose.runtime.key(t) {
                    val visible = onTabs && shownTab == t
                    Box(Modifier.fillMaxSize().keepAlive(visible)) { BrowsePage(session, model(t), t.key, onPlay, active = visible) }
                }
            }
        }
        // The bar has no solid edge: it fades from the page colour down into the artwork (catalogue
        // pages, and Settings over its blurred backdrop). Library, Search and Profiles keep a plain bar.
        val bg = Vyrk.colors.background
        val artwork = (app.section == Section.TABS && shownTab != Tab.LIBRARY) || app.section == Section.SETTINGS
        // Light enough not to tint the artwork (it was 94 % navy at the top), dark enough for the tabs.
        val shade = Vyrk.colors.shade
        if (artwork) Box(Modifier.fillMaxWidth().height(Dimens.navHeight + 56.dp).background(Brush.verticalGradient(
            0f to shade.copy(alpha = 0.78f), 0.45f to shade.copy(alpha = 0.42f), 0.75f to shade.copy(alpha = 0.12f), 1f to Color.Transparent,
        )))
        else Box(Modifier.fillMaxWidth().height(Dimens.navHeight).background(bg))
        Row(
            Modifier.fillMaxWidth().height(Dimens.navHeight)
                .padding(horizontal = Dimens.screenMargin)
                .onFocusChanged { navHasFocus = it.hasFocus; app.navFocused = it.hasFocus }
                .focusProperties {
                    onEnter = {
                        val target = when (app.section) {
                            Section.SEARCH -> searchReq; Section.SETTINGS -> settingsReq; Section.PROFILES -> profileReq
                            Section.TABS -> tabReqs.getValue(app.tab)
                        }
                        runCatching { target.requestFocus() }
                    }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark(20.dp)
            Spacer(Modifier.width(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { t ->
                    NavTab(
                        label = tabLabel(t),
                        active = app.section == Section.TABS && app.tab == t,
                        requester = tabReqs.getValue(t),
                        onSelect = { app.section = Section.TABS; app.tab = t },
                        trace = "tab:${t.key}",
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            NavIcon(R.drawable.ic_search, str(R.string.search), app.section == Section.SEARCH, searchReq, "nav:search") { app.section = Section.SEARCH }
            Spacer(Modifier.width(6.dp))
            NavAvatar(session, app.section == Section.PROFILES, profileReq) { app.section = Section.PROFILES }
            Spacer(Modifier.width(6.dp))
            NavIcon(R.drawable.ic_settings, str(R.string.settings), app.section == Section.SETTINGS, settingsReq, "nav:settings") { app.section = Section.SETTINGS }
        }

    }
}

@Composable
fun tabLabel(t: Tab): String = when (t) {
    Tab.HOME -> str(R.string.tab_home)
    Tab.MOVIES -> str(R.string.tab_movies)
    Tab.SERIES -> str(R.string.tab_series)
    Tab.ANIME -> str(R.string.tab_anime)
    Tab.LIVE -> str(R.string.tab_live)
    Tab.LIBRARY -> str(R.string.tab_library)
}

/** Soft fading light on focus (white 20% → accent 16% → transparent) and an underline; never a pill. */
@Composable
private fun NavTab(label: String, active: Boolean, requester: FocusRequester, onSelect: () -> Unit, trace: String) {
    var focused by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    Box(
        Modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
            .traceFocus(trace)
            .semantics { role = Role.Tab; contentDescription = label }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onSelect(); true } else false }
            .focusable()
            .drawBehind {
                if (focused) drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.20f), 0.55f to c.accent.copy(alpha = 0.16f), 1f to Color.Transparent,
                        center = center, radius = size.width * 0.75f,
                    ),
                    radius = size.width * 0.75f,
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Vyrk.type.titleM, color = if (focused || active) c.text else c.textSecondary, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(if (focused || active) 18.dp else 0.dp).height(2.dp).background(if (focused) c.accent else c.text.copy(alpha = 0.7f), RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun NavIcon(icon: Int, desc: String, active: Boolean, requester: FocusRequester, trace: String, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    Box(
        Modifier.size(38.dp).focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .traceFocus(trace)
            .semantics { contentDescription = desc; role = Role.Button }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onSelect(); true } else false }
            .focusable()
            .drawBehind {
                if (focused) drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.2f), 0.6f to c.accent.copy(alpha = 0.16f), 1f to Color.Transparent))
                if (active) drawLine(c.accent, Offset(size.width * 0.3f, size.height - 1f), Offset(size.width * 0.7f, size.height - 1f), 3f)
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = if (focused || active) c.text else c.textSecondary) }
}

@Composable
private fun NavAvatar(session: ProfileSession, active: Boolean, requester: FocusRequester, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = Vyrk.colors
    Box(
        Modifier.size(38.dp).focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .traceFocus("nav:profile")
            .semantics { contentDescription = session.profile.name; role = Role.Button }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onSelect(); true } else false }
            .focusable()
            .drawBehind { if (focused || active) drawCircle(c.accent.copy(alpha = if (focused) 0.6f else 0.3f), radius = size.minDimension / 2f) },
        contentAlignment = Alignment.Center,
    ) { ProfileAvatar(session.profile, 28.dp) }
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