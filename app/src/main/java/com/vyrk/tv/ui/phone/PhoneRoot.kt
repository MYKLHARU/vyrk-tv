package com.vyrk.tv.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.tv.R
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.Tab
import com.vyrk.tv.ui.AppState
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.BrowseModel
import com.vyrk.tv.ui.browse.RowStore
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.VyrkMark
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.delay

/** The touch interface: sign-in, the tabs, pages and sheets over them, and toasts. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhoneRoot(session: ProfileSession?) {
    val app = LocalApp.current
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(c.background).semantics { testTagsAsResourceId = true }) {
        when (val sc = app.screen) {
            Screen.Loading -> PhoneSplash()
            Screen.Welcome -> PhonePairing(profileId = null, firstRun = true)
            is Screen.Pairing -> PhonePairing(sc.profileId)
            Screen.Main -> session?.let { PhoneMain(it) } ?: PhoneSplash()
        }
        if (app.screen == Screen.Main && session != null) PhoneOverlays()
        PhoneToast(app)
    }
}

@Composable
private fun PhoneSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VyrkMark(72.dp, accent = Vyrk.colors.gold, highlight = Vyrk.colors.goldHighlight)
    }
}

/** Where the bottom bar is: catalogue tabs (Home, Movies…), Search, Library or Settings. */
enum class PhoneNav { BROWSE, SEARCH, LIBRARY, SETTINGS }

fun AppState.phoneNav(): PhoneNav = when {
    section == Section.SEARCH -> PhoneNav.SEARCH
    section == Section.SETTINGS -> PhoneNav.SETTINGS
    tab == Tab.LIBRARY -> PhoneNav.LIBRARY
    else -> PhoneNav.BROWSE
}

@Composable
fun PhoneMain(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val settings by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val loaded by session.loaded.collectAsState()

    // The catalogue pages in the strip at the top of Browse; hidden tabs stay hidden here too.
    val browseTabs = remember(addons, settings.hiddenTabs) {
        listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES, Tab.ANIME, Tab.LIVE, Tab.DISCOVER).filter { t ->
            (t != Tab.LIVE || CatalogRules.hasLiveCatalogs(addons)) && (t == Tab.HOME || t.key !in settings.hiddenTabs)
        }
    }
    // The catalogue page to return to from Search, Library or Settings.
    var browseTab by rememberSaveable(session.profile.id) { mutableStateOf(Tab.HOME.key) }
    LaunchedEffect(session.profile.id) {
        val start = Tab.entries.firstOrNull { it.key == settings.defaultTab } ?: Tab.HOME
        app.section = Section.TABS
        app.tab = if (start == Tab.LIBRARY || start in browseTabs) start else Tab.HOME
        if (start in browseTabs) browseTab = start.key
    }
    LaunchedEffect(app.tab) { if (app.tab != Tab.LIBRARY) browseTab = app.tab.key }
    LaunchedEffect(browseTabs) { if (app.tab != Tab.LIBRARY && app.tab !in browseTabs) app.tab = Tab.HOME }

    val rowStore = remember(session) { RowStore() }
    val models = remember(session, settings.animeGenre) { mutableMapOf<Tab, BrowseModel>() }
    fun model(t: Tab) = models.getOrPut(t) { BrowseModel(graph.addonClient, t, session.scope, rowStore, false, null) }
    LaunchedEffect(addons, settings.hiddenRows, browseTabs) {
        val list = session.effectiveAddons()
        (listOf(app.tab) + browseTabs).distinct().filter { it != Tab.DISCOVER && it != Tab.LIBRARY }.forEach { t ->
            model(t).rebuild(list, settings.hiddenRows)
            model(t).prefetch(if (t == app.tab) com.vyrk.tv.ui.browse.HERO_SOURCE_ROWS else 3)
        }
    }

    val nav = app.phoneNav()
    // Back: Search, Library and Settings return to the catalogue page; a catalogue page other
    // than Home returns to Home; Home leaves the app.
    BackHandler(enabled = app.overlays.isEmpty() && (nav != PhoneNav.BROWSE || app.tab != Tab.HOME)) {
        when {
            nav != PhoneNav.BROWSE -> { app.section = Section.TABS; app.tab = Tab.entries.firstOrNull { it.key == browseTab } ?: Tab.HOME }
            else -> app.tab = Tab.HOME
        }
    }

    fun go(to: PhoneNav) {
        when (to) {
            PhoneNav.BROWSE -> {
                val target = Tab.entries.firstOrNull { it.key == browseTab }?.takeIf { it in browseTabs } ?: Tab.HOME
                // Tapping Home while already there goes back to Home's own page.
                app.tab = if (nav == PhoneNav.BROWSE) Tab.HOME else target
                app.section = Section.TABS
            }
            PhoneNav.SEARCH -> app.section = Section.SEARCH
            PhoneNav.LIBRARY -> { app.section = Section.TABS; app.tab = Tab.LIBRARY }
            PhoneNav.SETTINGS -> app.section = Section.SETTINGS
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (loaded) Crossfade(nav, label = "phone-nav") { n ->
            when (n) {
                PhoneNav.BROWSE -> PhoneBrowse(session, browseTabs, ::model)
                PhoneNav.SEARCH -> PhoneSearch(session)
                PhoneNav.LIBRARY -> PhoneLibrary(session)
                PhoneNav.SETTINGS -> PhoneSettings(session)
            }
        } else PhoneSplash()
        PhoneBottomBar(nav, ::go, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PhoneBottomBar(current: PhoneNav, onSelect: (PhoneNav) -> Unit, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.0f), c.background.copy(alpha = 0.92f), c.background), startY = 0f, endY = 60f))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(BottomBarHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                Triple(PhoneNav.BROWSE, R.drawable.ic_home, R.string.tab_home),
                Triple(PhoneNav.SEARCH, R.drawable.ic_search, R.string.search),
                Triple(PhoneNav.LIBRARY, R.drawable.ic_library, R.string.tab_library),
                Triple(PhoneNav.SETTINGS, R.drawable.ic_cog, R.string.settings),
            ).forEach { (n, icon, label) ->
                val on = n == current
                val text = str(label)
                Column(
                    Modifier.weight(1f).height(BottomBarHeight)
                        .press({ onSelect(n) }, role = Role.Tab, tag = "nav:${n.name.lowercase()}")
                        .semantics { selected = on; contentDescription = text },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.height(30.dp).padding(horizontal = 4.dp)
                            .background(if (on) c.gold.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(50))
                            .padding(horizontal = 18.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(20.dp), tint = if (on) c.goldHighlight else c.textSecondary) }
                    Text(text, style = Vyrk.type.labelM, color = if (on) c.goldHighlight else c.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun PhoneToast(app: AppState) {
    val t = app.toast
    LaunchedEffect(t?.id) { if (t != null) { delay(3_500); if (app.toast?.id == t.id) app.toast = null } }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = BottomBarHeight + 16.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(t != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.background(Vyrk.colors.raised, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(t?.text ?: "", style = Vyrk.type.titleS, color = Vyrk.colors.text)
            }
        }
    }
}
