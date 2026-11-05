package com.vyrk.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.account.ApiError
import com.vyrk.core.account.LinkCode
import com.vyrk.tv.R
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.Profile
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.components.AmbientBackground
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.QrCode
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.Wordmark
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** First run: straight to the Stremio sign-in QR code, with a small way to start without an account. */
@Composable
fun WelcomeScreen() = PairingScreen(profileId = null, firstRun = true)

/**
 * Stremio sign-in by link code: the code and its QR, polled until approved. On first run it also
 * offers "Use without an account", and Back leaves the app (there is nothing behind it); from
 * Settings it has Cancel, and Back returns there.
 */
@Composable
fun PairingScreen(profileId: String?, firstRun: Boolean = false) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<LinkCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val action = remember { FocusRequester() }
    val errNetwork = str(R.string.pairing_error)

    BackHandler(enabled = !firstRun) { app.screen = Screen.Main }
    LaunchedEffect(Unit) { runCatching { action.requestFocus() } }
    LaunchedEffect(attempt) {
        error = null; link = null
        val code = runCatching { graph.accountApi.createLink() }.getOrElse { error = errNetwork; return@LaunchedEffect }
        link = code
        while (isActive) {
            delay(3_000)
            val key = try { graph.accountApi.readLink(code.code) } catch (e: ApiError) { error = errNetwork; return@LaunchedEffect } catch (e: Exception) { null }
            if (key != null) {
                // VYRK keeps one profile: sign in to it, creating it on first run.
                val profile: Profile = graph.profiles.value.let { ps -> profileId?.let { id -> ps.firstOrNull { it.id == id } } ?: ps.firstOrNull() }
                    ?: graph.createProfile(graph.context.getString(R.string.default_profile_name), 0)
                    ?: graph.profiles.value.first()
                val s = graph.openProfile(profile)
                try {
                    s.connectAccount(key) { email -> graph.updateProfile(profile.id) { it.copy(accountEmail = email, name = if (profileId == null) email.substringBefore('@').take(16) else it.name) } }
                } catch (e: Exception) {
                    Diagnostics.log("account", "connect failed: ${e.message}")
                    error = errNetwork; return@LaunchedEffect
                }
                graph.updateDevice { it.copy(firstRunDone = true) }
                app.screen = Screen.Main
                return@LaunchedEffect
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Row(Modifier.fillMaxSize().padding(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f)) {
                Wordmark(24.dp)
                if (firstRun) {
                    Box(Modifier.height(6.dp))
                    Text(str(R.string.tagline), style = Vyrk.type.titleM, color = Vyrk.colors.accent)
                }
                Box(Modifier.height(28.dp))
                Text(str(R.string.pairing_title), style = Vyrk.type.displayS, color = Vyrk.colors.text)
                Box(Modifier.height(12.dp))
                Text(str(R.string.pairing_step1), style = Vyrk.type.bodyL, color = Vyrk.colors.textSecondary)
                Text(link?.link ?: "…", style = Vyrk.type.headlineM, color = Vyrk.colors.accent)
                Box(Modifier.height(8.dp))
                Text(str(R.string.pairing_step2), style = Vyrk.type.bodyL, color = Vyrk.colors.textSecondary)
                Text(link?.code ?: "…", style = Vyrk.type.displayM, color = Vyrk.colors.text, modifier = Modifier.semantics { contentDescription = link?.code ?: "" })
                Box(Modifier.height(10.dp))
                Text(str(R.string.pairing_waiting), style = Vyrk.type.bodyM, color = Vyrk.colors.textMuted)
                if (error != null) {
                    Box(Modifier.height(10.dp))
                    Text(error!!, style = Vyrk.type.bodyM, color = Vyrk.colors.danger)
                }
                Box(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (error != null) VyrkButton(str(R.string.try_again), onClick = { attempt++ }, style = ButtonStyle.PRIMARY)
                    if (firstRun) VyrkButton(
                        str(R.string.use_without_account), enabled = !busy, style = ButtonStyle.OUTLINE, height = 34.dp, horizontalPadding = 16.dp,
                        onClick = {
                            busy = true
                            scope.launch {
                                val p = graph.profiles.value.firstOrNull() ?: graph.createProfile(graph.context.getString(R.string.default_profile_name), 0)!!
                                graph.updateDevice { it.copy(firstRunDone = true) }
                                graph.openProfile(p)
                                app.screen = Screen.Main
                            }
                        },
                        modifier = Modifier.focusRequester(action).traceFocus("welcome:guest"),
                    )
                    else VyrkButton(str(R.string.cancel), onClick = { app.screen = Screen.Main }, modifier = Modifier.focusRequester(action).traceFocus("pairing:cancel"))
                }
                if (firstRun) {
                    Box(Modifier.height(18.dp))
                    Text(str(R.string.not_affiliated), style = Vyrk.type.labelM, color = Vyrk.colors.textMuted)
                }
            }
            Box(Modifier.size(260.dp).clip(RoundedCornerShape(Dimens.radiusL)).background(Color.White), contentAlignment = Alignment.Center) {
                val l = link
                if (l != null) QrCode(l.link, 240.dp) else Text("…", color = Vyrk.colors.background)
            }
        }
    }
}
