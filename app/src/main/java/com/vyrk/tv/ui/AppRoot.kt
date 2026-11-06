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
import com.vyrk.tv.ui.screens.WelcomeScreen
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.FittedCanvas
import com.vyrk.tv.ui.theme.InterfaceSize
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.theme.VyrkTheme
import androidx.lifecycle.withResumed
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
            // One profile: the last one used (or the first, after an update from a version with
            // several), opened straight away.
            else -> { graph.openProfile(profiles.firstOrNull { it.id == device.lastProfileId } ?: profiles.first()); Screen.Main }
        }
    }

    // Automatic updates: on opening (once per run, at most every few minutes), check GitHub; if a
    // newer release is out, download it, check it (same app, newer, same signing key) and install.
    // Nothing shows until the file has passed those checks. CI and pre-release builds (signed with
    // the public test key, so never updatable to a release) don't check. Never during a film: the
    // install waits until VYRK's own screen is in front again.
    val onMain = app.screen == Screen.Main && session != null
    LaunchedEffect(onMain) {
        if (!onMain || com.vyrk.tv.BuildConfig.STORE_BUILD || com.vyrk.tv.data.Updater.triedThisLaunch) return@LaunchedEffect
        if (!com.vyrk.core.update.Updates.isReleaseVersion(com.vyrk.tv.BuildConfig.VERSION_NAME)) return@LaunchedEffect
        // A side-by-side dev copy (another app id) can't install a release; test builds point it at a test server.
        if (com.vyrk.tv.BuildConfig.APPLICATION_ID != "com.vyrk.tv" && com.vyrk.tv.BuildConfig.UPDATE_CHECK_URL.isBlank()) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val d = graph.deviceSettings.value
        if (!com.vyrk.core.update.Updates.shouldCheckOnOpen(d.lastUpdateCheckMs, now, d.autoUpdateChecks)) return@LaunchedEffect
        com.vyrk.tv.data.Updater.triedThisLaunch = true
        val updater = com.vyrk.tv.data.Updater(graph.context, graph.http)
        val result = updater.check()
        graph.updateDevice { it.copy(lastUpdateCheckMs = now) }
        val available = result as? com.vyrk.tv.data.Updater.Check.Available
        android.util.Log.i("VyrkUpdate", "check: " + (available?.release?.tag_name?.let { "newer $it" } ?: if (result is com.vyrk.tv.data.Updater.Check.Failed) "failed" else "up to date"))
        if (available == null) return@LaunchedEffect
        val version = available.release.tag_name.removePrefix("v")
        val apk = updater.download(available.asset) {}.getOrElse {
            com.vyrk.tv.data.Diagnostics.log("update", "automatic download failed: ${it.message}")
            android.util.Log.w("VyrkUpdate", "download failed", it)
            return@LaunchedEffect
        }
        if (!updater.canInstall()) { app.showToast(activity.getString(com.vyrk.tv.R.string.update_needs_permission, version)); return@LaunchedEffect }
        android.util.Log.i("VyrkUpdate", "downloaded $version; installing when VYRK is in front")
        // Say so first and leave time to read it (VYRK closes while Android replaces it).
        activity.lifecycle.withResumed { app.showToast(activity.getString(com.vyrk.tv.R.string.updating_to, version)) }
        delay(3_000)
        activity.lifecycle.withResumed { updater.install(apk) }
    }
    // A failed install (Android's reason, e.g. not enough space) as a toast.
    val updateEvent by com.vyrk.tv.data.Updater.events.collectAsState()
    LaunchedEffect(updateEvent) {
        val e = updateEvent ?: return@LaunchedEffect
        app.showToast(activity.getString(com.vyrk.tv.R.string.update_install_failed, e))
        com.vyrk.tv.data.Updater.events.value = null
    }

    // Incoming links: confirm before installing; wait until a profile is open.
    val link by activity.incomingLinks.collectAsState()
    LaunchedEffect(link, app.screen, session) {
        val l = link ?: return@LaunchedEffect
        val s = session
        if (app.screen != Screen.Main || s == null) { app.pendingLink = l.uri; app.pendingLinkExternal = l.external; return@LaunchedEffect }
        activity.incomingLinks.value = null
        // Not in this effect's scope: clearing the link above restarts the effect.
        s.scope.launch { handleLink(app, s, l.uri, l.external) }
    }
    LaunchedEffect(app.screen, session) {
        val s = session ?: return@LaunchedEffect
        val pending = app.pendingLink ?: return@LaunchedEffect
        if (app.screen != Screen.Main) return@LaunchedEffect
        app.pendingLink = null
        activity.incomingLinks.value = null
        val external = app.pendingLinkExternal
        s.scope.launch { handleLink(app, s, pending, external) }
    }

    VyrkTheme(accent, reduceMotion = settings?.reduceMotion == true) {
        FittedCanvas(size) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalApp provides app, LocalGraph provides graph, LocalSession provides session, LocalActivity provides activity,
            ) {
                Box(Modifier.fillMaxSize().background(Vyrk.colors.background)) {
                    when (val sc = app.screen) {
                        Screen.Loading -> {}
                        Screen.Welcome -> WelcomeScreen()
                        is Screen.Pairing -> PairingScreen(sc.profileId)
                        Screen.Main -> session?.let { MainScreen(it) }
                    }
                    Overlays()
                    ToastHost(app)
                }
            }
        }
    }
}

private suspend fun handleLink(app: AppState, s: ProfileSession, uri: String, external: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
    if (uri.startsWith("vyrk://open")) {
        // Watch Next and other launchers: open the info page for that title.
        val q = android.net.Uri.parse(uri)
        val id = q.getQueryParameter("id"); val type = q.getQueryParameter("type")
        if (id != null && type != null) app.push(Overlay.Info(com.vyrk.core.addon.MetaPreview(id = id, type = type, name = s.item(id)?.name ?: ""), null))
        return@withContext
    }
    val url = AddonUrls.normaliseInstallUrl(uri)
    if (url == null) { app.push(Overlay.InstallConfirm(null, uri, external, error = "invalid")); return@withContext }
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
