package com.vyrk.tv.ui.screens

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
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.Dimens
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

private enum class Group(val title: Int) {
    PROFILE(R.string.settings_profile), STREAMS(R.string.settings_streams), PICTURE(R.string.settings_picture),
    LANGUAGES(R.string.settings_languages), SUBTITLES(R.string.settings_subtitles), PREVIEWS(R.string.settings_previews),
    ADDONS(R.string.settings_addons), ABOUT(R.string.settings_about),
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
    // Not clipped: a focused chip's zoom and glow may reach past the panel edge.
    val glass = Modifier.background(c.glass, PANEL_SHAPE).border(1.dp, c.glassEdge, PANEL_SHAPE)
    Row(Modifier.fillMaxSize().padding(horizontal = Dimens.screenMargin, vertical = 12.dp)) {
        Column(Modifier.width(230.dp).then(glass).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Group.entries.forEach { g ->
                VyrkChip(str(g.title), group == g, { group = g },
                    Modifier.fillMaxWidth()
                        .focusRequester(groupReqs.getValue(g))
                        .onFocusChanged { if (it.isFocused) group = g }
                        // Right enters the page at its top, not at whichever setting is level with this entry.
                        .focusProperties { right = contentReq }
                        .traceFocus("settings:${g.name.lowercase()}"))
            }
        }
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f).fillMaxHeight().then(glass).padding(22.dp)
            // Focus search would pick the sidebar entry level with the focused setting, and the
            // page follows sidebar focus, so Left from a lower setting used to open another group.
            .focusProperties {
                onExit = { if (requestedFocusDirection == FocusDirection.Left) runCatching { groupReqs.getValue(group).requestFocus() } }
            }
            .focusGroup()
        ) {
            LazyColumn(Modifier.focusRequester(contentReq).focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                if (group == Group.ADDONS) {
                    item(key = "addon-actions") { AddonActions(session, typing) { typing = it } }
                    itemsIndexed(addons, key = { _, d -> "addon:" + d.manifest.id }) { i, d -> AddonCard(session, d, i, addons.lastIndex) }
                    item(key = "addon-sharing") { Column(Modifier.padding(top = 8.dp)) { GroupContent(session, Group.ADDONS) } }
                } else {
                    item(key = group) { GroupContent(session, group) }
                }
            }
        }
    }
}

private val PANEL_SHAPE = RoundedCornerShape(22.dp)

/**
 * Behind Settings: the artwork last featured on a catalogue page, decoded tiny and stretched so it
 * is soft on every TV, blurred further on Android 12+, under a dark fade that keeps text readable.
 * Before any artwork has loaded, a faint accent glow stands in for it.
 */
@Composable
fun SettingsBackdrop(url: String?) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        if (url != null) {
            val ctx = LocalContext.current
            val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(Size(64, 36)).build() }
            AsyncImage(req, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(0f to c.accent.copy(alpha = 0.16f), 1f to Color.Transparent)))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.shade.copy(alpha = 0.78f), 0.45f to c.shade.copy(alpha = 0.58f), 1f to c.shade.copy(alpha = 0.42f))))
    }
}

@Composable
private fun Setting(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(label, style = Vyrk.type.titleM, color = Vyrk.colors.text)
        if (hint != null) Text(hint, style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
        Spacer(Modifier.height(8.dp))
        WrapRow(spacing = 6.dp) { content() }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, trace: String, hint: String? = null, onChange: (Boolean) -> Unit) =
    Setting(label, hint) {
        VyrkChip(str(R.string.on), value, { onChange(true) }, Modifier.traceFocus("$trace:on"))
        VyrkChip(str(R.string.off), !value, { onChange(false) }, Modifier.traceFocus("$trace:off"))
    }

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, trace: String, hint: String? = null, onChange: (T) -> Unit) =
    Setting(label, hint) { options.forEachIndexed { i, (v, l) -> VyrkChip(l, v == value, { onChange(v) }, Modifier.traceFocus("$trace:$i")) } }

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
    Column {
        when (group) {
            Group.PROFILE -> {
                Choice(str(R.string.default_tab), listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES, Tab.LIBRARY).map { it.key to tabLabel(it) }, s.defaultTab, "set:default_tab") { v -> upd { it.copy(defaultTab = v) } }
                Setting(str(R.string.hidden_tabs)) {
                    listOf(Tab.MOVIES, Tab.SERIES, Tab.ANIME, Tab.LIVE, Tab.LIBRARY).forEach { t ->
                        VyrkChip(tabLabel(t), t.key in s.hiddenTabs, { upd { it.copy(hiddenTabs = if (t.key in it.hiddenTabs) it.hiddenTabs - t.key else it.hiddenTabs + t.key) } }, Modifier.traceFocus("set:hide_tab:${t.key}"))
                    }
                }
                Toggle(str(R.string.featured_hero), s.heroEnabled, "set:hero") { v -> upd { it.copy(heroEnabled = v) } }
                Toggle(str(R.string.hero_rotation), s.heroRotation, "set:hero_rotation") { v -> upd { it.copy(heroRotation = v) } }
                Choice(str(R.string.poster_size), listOf(104 to str(R.string.size_small), 118 to str(R.string.size_medium), 136 to str(R.string.size_large)), s.posterSize, "set:poster") { v -> upd { it.copy(posterSize = v) } }
                Choice(str(R.string.accent_colour), AccentChoice.entries.map { it.name to accentName(it) }, s.accent, "set:accent") { v -> upd { it.copy(accent = v) } }
                Toggle(str(R.string.reduce_motion), s.reduceMotion, "set:reduce_motion") { v -> upd { it.copy(reduceMotion = v) } }
                Toggle(str(R.string.blur_unwatched), s.blurUnwatched, "set:blur") { v -> upd { it.copy(blurUnwatched = v) } }
                Setting(str(R.string.hidden_rows), if (s.hiddenRows.isEmpty()) str(R.string.none) else null) {
                    if (s.hiddenRows.isNotEmpty()) VyrkButton(str(R.string.restore_rows, s.hiddenRows.size), onClick = { upd { it.copy(hiddenRows = emptySet()) } }, modifier = Modifier.traceFocus("set:restore_rows"))
                }
                if (s.top10CardDismissed) VyrkButton(str(R.string.show_top10_card), onClick = { upd { it.copy(top10CardDismissed = false) } })
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
                Text(str(R.string.this_tv), style = Vyrk.type.titleM, color = c.text)
                Spacer(Modifier.height(6.dp))
                val unknown = str(R.string.unknown)
                fun yn(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> unknown }
                listOf(
                    "H.264" to (r.decoders[com.vyrk.core.stream.VideoCodec.H264]?.let { "${it}p" } ?: unknown),
                    "HEVC" to (r.decoders[com.vyrk.core.stream.VideoCodec.HEVC]?.let { "${it}p" } ?: unknown),
                    "AV1" to (r.decoders[com.vyrk.core.stream.VideoCodec.AV1]?.let { "${it}p" } ?: unknown),
                    str(R.string.display) to if (r.displayWidth > 0) "${r.displayWidth}×${r.displayHeight}" else unknown,
                    "HDR10" to yn(r.hdr10), "HDR10+" to yn(r.hdr10Plus), "Dolby Vision" to yn(r.dolbyVision), "HLG" to yn(r.hlg),
                    str(R.string.audio_formats) to r.audioEncodings.joinToString(", ").ifEmpty { "PCM" },
                    str(R.string.max_channels) to r.maxChannels.toString(),
                ).forEach { (k, v) -> Row { Text("$k  ", style = Vyrk.type.labelL, color = c.textMuted); Text(v, style = Vyrk.type.bodyS, color = c.textSecondary) } }
            }
            Group.LANGUAGES -> {
                Choice(str(R.string.app_language), listOf("" to str(R.string.system_default), "en" to "English", "es" to "Español", "pt-BR" to "Português (Brasil)", "fr" to "Français", "de" to "Deutsch", "hi" to "हिन्दी", "it" to "Italiano"), d.appLanguage, "set:app_lang") { v ->
                    graph.updateDevice { it.copy(appLanguage = v) }; activity.setAppLanguage(v)
                }
                Setting(str(R.string.audio_languages), str(R.string.languages_order_hint)) {
                    LANG_CHOICES.forEach { l -> VyrkChip(Languages.label(l), l in s.audioLanguages, { upd { it.copy(audioLanguages = if (l in it.audioLanguages) it.audioLanguages - l else it.audioLanguages + l) } }, Modifier.traceFocus("set:audio_lang:$l")) }
                }
                Toggle(str(R.string.original_language), s.originalLanguage, "set:original_lang") { v -> upd { it.copy(originalLanguage = v) } }
                Toggle(str(R.string.avoid_commentary), s.avoidCommentary, "set:avoid_commentary") { v -> upd { it.copy(avoidCommentary = v) } }
            }
            Group.SUBTITLES -> {
                Choice(str(R.string.subtitle_mode), listOf(SubtitleMode.ALWAYS to str(R.string.sub_always), SubtitleMode.FOREIGN_ONLY to str(R.string.sub_foreign), SubtitleMode.OFF to str(R.string.off)), s.subtitleMode, "set:sub_mode") { v -> upd { it.copy(subtitleMode = v) } }
                Setting(str(R.string.subtitle_languages)) {
                    LANG_CHOICES.forEach { l -> VyrkChip(Languages.label(l), l in s.subtitleLanguages, { upd { it.copy(subtitleLanguages = if (l in it.subtitleLanguages) it.subtitleLanguages - l else it.subtitleLanguages + l) } }, Modifier.traceFocus("set:sub_lang:$l")) }
                }
                Choice(str(R.string.subtitle_size), listOf(80 to "80%", 100 to "100%", 125 to "125%", 150 to "150%"), s.subtitleSize, "set:sub_size") { v -> upd { it.copy(subtitleSize = v) } }
                Choice(str(R.string.subtitle_colour), listOf("WHITE" to str(R.string.colour_white), "YELLOW" to str(R.string.colour_yellow), "CYAN" to str(R.string.colour_frost)), s.subtitleColor, "set:sub_colour") { v -> upd { it.copy(subtitleColor = v) } }
                Choice(str(R.string.subtitle_background), listOf("SHADOW" to str(R.string.bg_shadow), "BOX" to str(R.string.bg_box), "NONE" to str(R.string.none)), s.subtitleBackground, "set:sub_bg") { v -> upd { it.copy(subtitleBackground = v) } }
            }
            Group.PREVIEWS -> {
                Choice(str(R.string.previews), listOf(PreviewMode.OFF to str(R.string.off), PreviewMode.MANUAL to str(R.string.preview_manual), PreviewMode.AUTOMATIC to str(R.string.preview_automatic)), s.previews, "set:previews") { v -> upd { it.copy(previews = v) } }
                Toggle(str(R.string.preview_sound), s.previewSound, "set:preview_sound") { v -> upd { it.copy(previewSound = v) } }
            }
            Group.ADDONS -> {
                Toggle(str(R.string.share_stream_addons), s.shareStreamAddons, "set:share", str(R.string.share_hint)) { v ->
                    upd { it.copy(shareStreamAddons = v) }; graph.refreshShared(session.profile.id, session.addons.value, v)
                }
                Toggle(str(R.string.use_shared_addons), s.useSharedAddons, "set:use_shared") { v -> upd { it.copy(useSharedAddons = v) } }
            }
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
    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Vyrk.type.headlineS, color = c.text)
    Text(str(R.string.about_body), style = Vyrk.type.bodyS, color = c.textSecondary)
    Spacer(Modifier.height(12.dp))
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
            VyrkChip(str(R.string.automatic_checks), d.autoUpdateChecks, { graph.updateDevice { it.copy(autoUpdateChecks = !it.autoUpdateChecks) } }, Modifier.traceFocus("about:auto_checks"))
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

@Composable
fun ProfilesScreen(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val profiles by graph.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val c = Vyrk.colors
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screenMargin, vertical = 20.dp)) {
        Text(str(R.string.whos_watching), style = Vyrk.type.displayS, color = c.text)
        Spacer(Modifier.height(20.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.tv.material3.Surface(
                        onClick = {
                            if (p.id == session.profile.id) return@Surface
                            val go = { scope.launch { graph.openProfile(p); app.section = com.vyrk.tv.ui.Section.TABS } }
                            if (p.pinHash != null) app.push(Overlay.Pin(p) { go() }) else go()
                        },
                        onLongClick = { app.push(Overlay.EditProfile(p)) },
                        modifier = Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier).traceFocus("profiles:$i"),
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent),
                        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                        border = androidx.tv.material3.ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None),
                        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = com.vyrk.tv.ui.components.focusGlow()),
                    ) { ProfileAvatar(p, 88.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text(p.name + if (p.id == session.profile.id) " ✓" else "", style = Vyrk.type.titleM, color = c.text)
                    p.accountEmail?.let { Text(str(R.string.stremio_connected), style = Vyrk.type.labelS, color = c.textMuted) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        WrapRow {
            if (profiles.size < 5) VyrkButton(str(R.string.add_profile), icon = R.drawable.ic_plus, onClick = { app.push(Overlay.EditProfile(null)) }, modifier = Modifier.traceFocus("profiles:add"))
            VyrkButton(str(R.string.switch_profile), onClick = { graph.closeSession(); app.screen = Screen.Profiles }, modifier = Modifier.traceFocus("profiles:switch"))
        }
        Spacer(Modifier.height(10.dp))
        Text(str(R.string.hold_ok_to_edit), style = Vyrk.type.labelM, color = c.textMuted)
    }
}
