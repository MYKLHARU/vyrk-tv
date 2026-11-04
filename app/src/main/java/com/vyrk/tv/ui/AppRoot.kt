package com.vyrk.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.addon.AddonUrls
import com.vyrk.tv.MainActivity
import com.vyrk.tv.R
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.overlays.Overlays
import com.vyrk.tv.ui.screens.MainScreen
import com.vyrk.tv.ui.screens.PairingScreen
import com.vyrk.tv.ui.screens.ProfilePicker
import com.vyrk.tv.ui.screens.WelcomeScreen
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.FittedCanvas
import com.vyrk.tv.ui.theme.InterfaceSize
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.theme.VyrkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalApp = androidx.compose.runtime.staticCompositionLocalOf<AppState> { error("no AppState") }
val LocalGraph = androidx.compose.runtime.staticCompositionLocalOf<AppGraph> { error("no graph") }
val LocalSession = androidx.compose.runtime.compositionLocalOf<ProfileSession?> { null }
val LocalActivity = androidx.compose.runtime.staticCompositionLocalOf<MainActivity> { error("no activity") }

@Composable
fun AppRoot(graph: AppGraph, ready: Boolean, activity: MainActivity) {
    val app = remember { AppState() }
    val device by graph.deviceSettings.collectAsState()
    val session by graph.session.collectAsState()
    val settings = session?.settings?.collectAsState()?.value
    val accent = runCatching { AccentChoice.valueOf(settings?.accent ?: "FROST") }.getOrDefault(AccentChoice.FROST)
    val size = runCatching { InterfaceSize.valueOf(device.interfaceSize) }.getOrDefault(InterfaceSize.MEDIUM)
    val scope = rememberCoroutineScope()

    // Startup routing.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val profiles = graph.profiles.value
        app.screen = when {
            !device.firstRunDone || profiles.isEmpty() -> Screen.Welcome
            else -> {
                val last = profiles.firstOrNull { it.id == device.lastProfileId }
                if (profiles.size == 1 && last != null && last.pinHash == null) {
                    graph.openProfile(last); Screen.Main
                } else Screen.Profiles
            }
        }
    }

    // Incoming links: confirm before installing; wait until a profile is open.
    val link by activity.incomingLinks.collectAsState()
    LaunchedEffect(link, app.screen, session) {
        val l = link ?: return@LaunchedEffect
        val s = session
        if (app.screen != Screen.Main || s == null) { app.pendingLink = l.uri; app.pendingLinkExternal = l.external; return@LaunchedEffect }
        activity.incomingLinks.value = null
        handleLink(app, s, l.uri, l.external)
    }
    LaunchedEffect(app.screen, session) {
        val s = session ?: return@LaunchedEffect
        val pending = app.pendingLink ?: return@LaunchedEffect
        if (app.screen != Screen.Main) return@LaunchedEffect
        app.pendingLink = null
        activity.incomingLinks.value = null
        handleLink(app, s, pending, app.pendingLinkExternal)
    }

    VyrkTheme(accent) {
        FittedCanvas(size) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalApp provides app, LocalGraph provides graph, LocalSession provides session, LocalActivity provides activity,
            ) {
                Box(Modifier.fillMaxSize().background(Vyrk.colors.background)) {
                    when (val sc = app.screen) {
                        Screen.Loading -> {}
                        Screen.Welcome -> WelcomeScreen()
                        is Screen.Pairing -> PairingScreen(sc.profileId)
                        Screen.Profiles -> ProfilePicker()
                        Screen.Main -> if (session != null) MainScreen(session!!) else ProfilePicker()
                    }
                    Overlays()
                    ToastHost(app)
                }
            }
        }
    }
}

private suspend fun handleLink(app: AppState, s: ProfileSession, uri: String, external: Boolean) {
    if (uri.startsWith("vyrk://open")) return
    val url = AddonUrls.normaliseInstallUrl(uri)
    if (url == null) { app.push(Overlay.InstallConfirm(null, uri, external, error = "invalid")); return }
    app.push(Overlay.InstallConfirm(null, url, external))
    val d = s.fetchManifest(url)
    val top = app.overlays.lastOrNull()
    if (top is Overlay.InstallConfirm && top.input == url) {
        app.replaceTop(top.copy(descriptor = d.getOrNull(), error = d.exceptionOrNull()?.let { "fetch" }))
    }
}

@Composable
private fun ToastHost(app: AppState) {
    val t = app.toast
    LaunchedEffect(t?.id) { if (t != null) { delay(3_500); if (app.toast?.id == t.id) app.toast = null } }
    Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(t != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.background(Vyrk.colors.raised, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(t?.text ?: "", style = Vyrk.type.titleM, color = Vyrk.colors.text)
            }
        }
    }
}

@Composable
fun str(id: Int, vararg args: Any): String = stringResource(id, *args)
