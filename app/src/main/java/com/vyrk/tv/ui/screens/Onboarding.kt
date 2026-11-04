package com.vyrk.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vyrk.core.account.ApiError
import com.vyrk.core.account.LinkCode
import com.vyrk.tv.R
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.Profile
import com.vyrk.tv.data.ProfileColors
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.QrCode
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkMark
import com.vyrk.tv.ui.components.Wordmark
import com.vyrk.tv.ui.components.focusGlow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen() {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Vyrk.colors.accent.copy(alpha = 0.12f), Color.Transparent), radius = 900f),
        ),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            VyrkMark(84.dp)
            Box(Modifier.height(18.dp))
            Wordmark(34.dp)
            Box(Modifier.height(10.dp))
            Text(str(R.string.tagline), style = Vyrk.type.headlineS, color = Vyrk.colors.accent)
            Box(Modifier.height(6.dp))
            Text(str(R.string.welcome_pitch), style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(460.dp))
            Box(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                VyrkButton(
                    str(R.string.sign_in_stremio), onClick = { app.screen = Screen.Pairing(null) },
                    style = ButtonStyle.PRIMARY, icon = R.drawable.ic_qr,
                    modifier = Modifier.focusRequester(first).traceFocus("welcome:sign_in"),
                )
                VyrkButton(
                    str(R.string.use_without_account), enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val name = graph.context.getString(R.string.default_profile_name)
                            val p = graph.profiles.value.firstOrNull() ?: graph.createProfile(name, 0)!!
                            graph.updateDevice { it.copy(firstRunDone = true) }
                            graph.openProfile(p)
                            app.screen = Screen.Main
                        }
                    },
                    modifier = Modifier.traceFocus("welcome:guest"),
                )
            }
            Box(Modifier.height(22.dp))
            Text(str(R.string.not_affiliated), style = Vyrk.type.labelM, color = Vyrk.colors.textMuted)
        }
    }
}

@Composable
fun PairingScreen(profileId: String?) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    var link by remember { mutableStateOf<LinkCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val cancel = remember { FocusRequester() }
    val errNetwork = str(R.string.pairing_error)

    BackHandler { app.screen = if (graph.profiles.value.isEmpty()) Screen.Welcome else if (profileId != null) Screen.Main else Screen.Profiles }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    LaunchedEffect(attempt) {
        error = null; link = null
        val code = runCatching { graph.accountApi.createLink() }.getOrElse { error = errNetwork; return@LaunchedEffect }
        link = code
        while (isActive) {
            delay(3_000)
            val key = try { graph.accountApi.readLink(code.code) } catch (e: ApiError) { error = errNetwork; return@LaunchedEffect } catch (e: Exception) { null }
            if (key != null) {
                val profile: Profile = profileId?.let { id -> graph.profiles.value.firstOrNull { it.id == id } }
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

    Row(Modifier.fillMaxSize().padding(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f)) {
            Wordmark(24.dp)
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
                VyrkButton(str(R.string.cancel), onClick = { app.screen = if (graph.profiles.value.isEmpty()) Screen.Welcome else Screen.Profiles }, modifier = Modifier.focusRequester(cancel).traceFocus("pairing:cancel"))
            }
        }
        Box(Modifier.size(260.dp).clip(RoundedCornerShape(18.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            val l = link
            if (l != null) QrCode(l.link, 240.dp) else Text("…", color = Vyrk.colors.background)
        }
    }
}

@Composable
fun ProfileAvatar(p: Profile, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(ProfileColors[p.colorIndex % ProfileColors.size])),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.name.take(1).uppercase(), style = Vyrk.type.displayS.copy(fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.42f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Vyrk.colors.background)
    }
}

@Composable
fun ProfilePicker() {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val profiles by graph.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(profiles.isNotEmpty()) { runCatching { first.requestFocus() } }

    fun open(p: Profile) {
        val go = { scope.launch { graph.openProfile(p); app.section = com.vyrk.tv.ui.Section.TABS; app.screen = Screen.Main } }
        if (p.pinHash != null) app.push(Overlay.Pin(p) { go() }) else go()
    }

    Column(Modifier.fillMaxSize().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Wordmark(22.dp)
        Box(Modifier.height(36.dp))
        Text(str(R.string.whos_watching), style = Vyrk.type.displayS, color = Vyrk.colors.text)
        Box(Modifier.height(36.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { open(p) },
                        onLongClick = { app.push(Overlay.EditProfile(p)) },
                        modifier = Modifier.size(104.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .traceFocus("profile:$i").semantics { contentDescription = p.name },
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
                        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
                    ) {
                        ProfileAvatar(p, 104.dp)
                        if (p.pinHash != null) Icon(R.drawable.ic_lock, null, Modifier.align(Alignment.BottomEnd).size(22.dp), tint = Vyrk.colors.background)
                    }
                    Box(Modifier.height(10.dp))
                    Text(p.name, style = Vyrk.type.titleM, color = Vyrk.colors.text, maxLines = 1)
                }
            }
            if (profiles.size < 5) item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { app.push(Overlay.EditProfile(null)) },
                        modifier = Modifier.size(104.dp).then(if (profiles.isEmpty()) Modifier.focusRequester(first) else Modifier)
                            .traceFocus("profile:add").semantics { contentDescription = "add" },
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Vyrk.colors.raised, focusedContainerColor = Vyrk.colors.text),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
                        glow = ClickableSurfaceDefaults.glow(focusedGlow = focusGlow()),
                    ) { Icon(R.drawable.ic_plus, null, Modifier.size(36.dp).align(Alignment.Center), tint = androidx.tv.material3.LocalContentColor.current) }
                    Box(Modifier.height(10.dp))
                    Text(str(R.string.add_profile), style = Vyrk.type.titleM, color = Vyrk.colors.textSecondary)
                }
            }
        }
        Box(Modifier.height(30.dp))
        Text(str(R.string.hold_ok_to_edit), style = Vyrk.type.labelM, color = Vyrk.colors.textMuted)
    }
}
