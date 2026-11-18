package com.vyrk.tv.ui.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.Redact
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.lang.Languages
import com.vyrk.core.playback.SubtitleMode
import com.vyrk.core.stream.FormatPref
import com.vyrk.core.stream.HdrPref
import com.vyrk.core.stream.Preset
import com.vyrk.tv.BuildConfig
import com.vyrk.tv.R
import com.vyrk.tv.data.AccountState
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.data.SkipIntroMode
import com.vyrk.tv.data.Tab
import com.vyrk.tv.data.Updater
import com.vyrk.tv.player.subtitleStyleOf
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Screen
import com.vyrk.tv.ui.components.Icon
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkMark
import com.vyrk.tv.ui.screens.tabLabel
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.NorseDisplay
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.launch

private enum class PhoneGroup(val title: Int, val icon: Int, val subtitle: Int?) {
    ADDONS(R.string.settings_addons, R.drawable.ic_puzzle, R.string.settings_addons_sub),
    STREAMS(R.string.settings_streams, R.drawable.ic_live, R.string.settings_streams_sub),
    SUBTITLES(R.string.settings_subtitles, R.drawable.ic_subtitles, R.string.settings_subtitles_sub),
    LANGUAGES(R.string.settings_languages, R.drawable.ic_globe, R.string.settings_languages_sub),
    APPEARANCE(R.string.settings_profile, R.drawable.ic_profile, R.string.settings_profile_sub),
    ROWS(R.string.settings_rows, R.drawable.ic_rows, R.string.settings_rows_sub),
    // Phones have none of the TV picture options (interface size, frame rate): sound only.
    SOUND(R.string.settings_picture, R.drawable.ic_audio, null),
    ABOUT(R.string.settings_about, R.drawable.ic_info, R.string.settings_about_sub),
}

private val LANG_CHOICES = listOf("eng", "spa", "por", "fre", "ger", "ita", "hin", "jpn", "kor")

/** Settings: the account at the top, then each group as a row that opens its own page. */
@Composable
fun PhoneSettings(session: ProfileSession) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val group = open?.let { name -> PhoneGroup.entries.firstOrNull { it.name == name } }
    val c = Vyrk.colors
    // The add-on URL being typed (null when the sheet is closed); the sheet sits over the page.
    var addonUrl by remember { mutableStateOf<String?>(null) }
    if (group != null) {
        BackHandler { open = null }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PhoneTopBar(str(group.title), { open = null })
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = PhoneMargin).padding(bottomContentPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { GroupPage(session, group, onInstallUrl = { addonUrl = "" }) }
            }
            AddonUrlSheet(session, addonUrl, { addonUrl = it })
        }
        return
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())
            .padding(horizontal = PhoneMargin).padding(bottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(str(R.string.settings), style = Vyrk.type.displayS, color = c.text)
        AccountCard(session)
        TraktCard()
        PhoneCard {
            PhoneGroup.entries.forEach { g ->
                PhoneListRow(str(g.title), { open = g.name }, icon = g.icon, hint = g.subtitle?.let { str(it) }, tag = "settings:${g.name.lowercase()}")
            }
        }
        Crest()
    }
}

@Composable
private fun AccountCard(session: ProfileSession) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val account by session.account.collectAsState()
    PhoneCard(title = str(R.string.stremio_account)) {
        when (val a = account) {
            is AccountState.Connected -> {
                Text(a.email, style = Vyrk.type.titleM, color = Vyrk.colors.text)
                Spacer(Modifier.height(10.dp))
                PhoneButton(str(R.string.sign_out), { scope.launch { session.logout() } }, style = PhoneButtonStyle.OUTLINE, tag = "about:logout")
            }
            is AccountState.Expired -> {
                Text(str(R.string.session_expired), style = Vyrk.type.bodyM, color = Vyrk.colors.warning)
                Spacer(Modifier.height(10.dp))
                PhoneButton(str(R.string.reconnect), { app.screen = Screen.Pairing(session.profile.id) }, style = PhoneButtonStyle.PRIMARY)
            }
            AccountState.None -> {
                Text(str(R.string.saved_on_device), style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary)
                Spacer(Modifier.height(10.dp))
                PhoneButton(str(R.string.sign_in_stremio), { app.screen = Screen.Pairing(session.profile.id) }, style = PhoneButtonStyle.PRIMARY, tag = "about:sign_in")
            }
        }
    }
}

@Composable
private fun TraktCard() {
    val graph = com.vyrk.tv.ui.LocalGraph.current
    val state by graph.trakt.state.collectAsState()
    val signIn = com.vyrk.tv.ui.components.rememberTraktSignIn(graph.trakt)
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val s = state
    if (s == com.vyrk.tv.data.TraktAccount.State.Off) return
    PhoneCard(title = str(R.string.trakt)) {
        if (s is com.vyrk.tv.data.TraktAccount.State.Connected) {
            Text(s.username?.let { str(R.string.trakt_connected_as, it) } ?: str(R.string.trakt_desc), style = Vyrk.type.bodyM, color = Vyrk.colors.text)
            Spacer(Modifier.height(10.dp))
            PhoneButton(str(R.string.trakt_disconnect), { graph.trakt.disconnect() }, style = PhoneButtonStyle.OUTLINE, tag = "about:trakt_disconnect")
        } else {
            Text(str(R.string.trakt_desc), style = Vyrk.type.bodyM, color = Vyrk.colors.textSecondary)
            Spacer(Modifier.height(10.dp))
            com.vyrk.tv.ui.components.TraktCodePanel(signIn.step, qr = false)
            val step = signIn.step
            if (step is com.vyrk.tv.data.TraktAccount.SignIn.Code) {
                Spacer(Modifier.height(10.dp))
                PhoneButton(str(R.string.trakt_open_site), { runCatching { uri.openUri(step.code.verificationUrl) } }, style = PhoneButtonStyle.PRIMARY, tag = "about:trakt_open")
                Spacer(Modifier.height(8.dp))
                PhoneButton(str(R.string.cancel), { signIn.cancel() }, style = PhoneButtonStyle.OUTLINE, tag = "about:trakt_cancel")
            } else {
                Spacer(Modifier.height(10.dp))
                PhoneButton(str(R.string.trakt_connect), { signIn.start() }, style = PhoneButtonStyle.PRIMARY, tag = "about:trakt_connect")
            }
        }
    }
}

/** The gold V, the name in spaced serif capitals, the tagline and this build. */
@Composable
private fun Crest() {
    val c = Vyrk.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VyrkMark(40.dp, accent = c.gold, highlight = c.goldHighlight)
        Spacer(Modifier.height(8.dp))
        Text("VYRK", color = c.text, fontFamily = NorseDisplay, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 7.sp)
        Text(str(R.string.tagline).uppercase(), style = Vyrk.type.eyebrow, color = c.gold)
        Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Vyrk.type.labelM, color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ColumnScope.GroupPage(session: ProfileSession, group: PhoneGroup, onInstallUrl: () -> Unit) {
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val s by session.settings.collectAsState()
    val d by graph.deviceSettings.collectAsState()
    fun upd(f: (com.vyrk.tv.data.ProfileSettings) -> com.vyrk.tv.data.ProfileSettings) = session.updateSettings(f)
    val any = str(R.string.any)
    when (group) {
        PhoneGroup.ADDONS -> AddonsPage(session, onInstallUrl)
        PhoneGroup.STREAMS -> PhoneCard {
            PhoneChoice(str(R.string.source_preset), listOf(Preset.BALANCED to str(R.string.preset_balanced), Preset.BEST_PICTURE to str(R.string.preset_best),
                Preset.FAST_START to str(R.string.preset_fast), Preset.ADDON_ORDER to str(R.string.preset_addon_order)), s.preset, { v -> upd { it.copy(preset = v) } },
                hint = str(R.string.preset_hint), tag = "set:preset")
            PhoneChoice(str(R.string.max_resolution), listOf(null to any, 2160 to "4K", 1080 to "1080p", 720 to "720p"), s.maxResolution, { v -> upd { it.copy(maxResolution = v) } }, tag = "set:max_res")
            PhoneChoice(str(R.string.max_file_size), listOf(null to any, 50 to "50 GB", 20 to "20 GB", 10 to "10 GB", 4 to "4 GB"), s.maxSizeGb, { v -> upd { it.copy(maxSizeGb = v) } })
            PhoneChoice(str(R.string.preferred_format), listOf(FormatPref.ANY to any, FormatPref.REMUX to "Remux", FormatPref.ENCODE to str(R.string.encode), FormatPref.WEB to "WEB"), s.format, { v -> upd { it.copy(format = v) } })
            PhoneChoice(str(R.string.preferred_hdr), listOf(HdrPref.ANY to any, HdrPref.DOLBY_VISION to "Dolby Vision", HdrPref.HDR10 to "HDR10", HdrPref.SDR to "SDR"), s.hdr, { v -> upd { it.copy(hdr = v) } })
            PhoneToggle(str(R.string.sources_on_info), s.sourcesOnInfoPage, { v -> upd { it.copy(sourcesOnInfoPage = v) } })
            PhoneToggle(str(R.string.load_sources_on_open), s.loadSourcesOnOpen, { v -> upd { it.copy(loadSourcesOnOpen = v) } })
            PhoneToggle(str(R.string.always_show_sources), s.alwaysShowSources, { v -> upd { it.copy(alwaysShowSources = v) } })
            PhoneToggle(str(R.string.autoplay_next), s.autoplayNext, { v -> upd { it.copy(autoplayNext = v) } }, tag = "set:autoplay")
            PhoneToggle(str(R.string.ask_still_watching), s.askStillWatching, { v -> upd { it.copy(askStillWatching = v) } })
            PhoneChoice(str(R.string.setting_skip_intro), listOf(SkipIntroMode.BUTTON to str(R.string.skip_show_button), SkipIntroMode.AUTOMATIC to str(R.string.skip_automatic),
                SkipIntroMode.OFF to str(R.string.off)), s.skipIntro, { v -> upd { it.copy(skipIntro = v) } }, hint = str(R.string.skip_intro_hint))
            PhoneToggle(str(R.string.setting_chapter_markers), s.chapterMarkers, { v -> upd { it.copy(chapterMarkers = v) } }, hint = str(R.string.chapter_markers_hint))
        }
        PhoneGroup.SUBTITLES -> {
            SubtitleSample(s.subtitleColor, s.subtitleBackground, s.subtitleSize)
            PhoneCard {
                PhoneChoice(str(R.string.subtitle_mode), listOf(SubtitleMode.ALWAYS to str(R.string.sub_always), SubtitleMode.FOREIGN_ONLY to str(R.string.sub_foreign), SubtitleMode.OFF to str(R.string.off)),
                    s.subtitleMode, { v -> upd { it.copy(subtitleMode = v) } })
                LanguageChecks(str(R.string.subtitle_languages), s.subtitleLanguages) { l -> upd { it.copy(subtitleLanguages = if (l in it.subtitleLanguages) it.subtitleLanguages - l else it.subtitleLanguages + l) } }
                PhoneChoice(str(R.string.subtitle_size), listOf(80 to "80%", 100 to "100%", 125 to "125%", 150 to "150%"), s.subtitleSize, { v -> upd { it.copy(subtitleSize = v) } })
                PhoneChoice(str(R.string.subtitle_colour), listOf("WHITE" to str(R.string.colour_white), "YELLOW" to str(R.string.colour_yellow), "CYAN" to str(R.string.colour_frost)), s.subtitleColor, { v -> upd { it.copy(subtitleColor = v) } })
                PhoneChoice(str(R.string.subtitle_background), listOf("SHADOW" to str(R.string.bg_shadow), "BOX" to str(R.string.bg_box), "NONE" to str(R.string.none)), s.subtitleBackground, { v -> upd { it.copy(subtitleBackground = v) } })
            }
        }
        PhoneGroup.LANGUAGES -> PhoneCard {
            PhoneChoice(str(R.string.app_language), listOf("" to str(R.string.system_default), "en" to "English", "es" to "Español", "pt-BR" to "Português (Brasil)",
                "fr" to "Français", "de" to "Deutsch", "hi" to "हिन्दी", "it" to "Italiano"), d.appLanguage, { v ->
                graph.updateDevice { it.copy(appLanguage = v) }; activity.setAppLanguage(v)
            })
            LanguageChecks(str(R.string.audio_languages), s.audioLanguages, hint = str(R.string.languages_order_hint)) { l ->
                upd { it.copy(audioLanguages = if (l in it.audioLanguages) it.audioLanguages - l else it.audioLanguages + l) }
            }
            PhoneToggle(str(R.string.original_language), s.originalLanguage, { v -> upd { it.copy(originalLanguage = v) } })
            PhoneToggle(str(R.string.avoid_commentary), s.avoidCommentary, { v -> upd { it.copy(avoidCommentary = v) } })
        }
        PhoneGroup.APPEARANCE -> PhoneCard {
            val tabs = listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES, Tab.DISCOVER, Tab.ANIME, Tab.LIBRARY)
            PhoneChoice(str(R.string.default_tab), tabs.map { it.key to tabLabel(it) }, s.defaultTab, { v -> upd { it.copy(defaultTab = v) } }, hint = str(R.string.default_tab_desc))
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(str(R.string.hidden_tabs), style = Vyrk.type.titleM, color = Vyrk.colors.text)
                Text(str(R.string.hidden_tabs_desc), style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    listOf(Tab.MOVIES, Tab.SERIES, Tab.DISCOVER, Tab.ANIME).forEach { t ->
                        PhoneChip(tabLabel(t), t.key in s.hiddenTabs, { upd { it.copy(hiddenTabs = if (t.key in it.hiddenTabs) it.hiddenTabs - t.key else it.hiddenTabs + t.key) } },
                            icon = if (t.key in s.hiddenTabs) R.drawable.ic_eye_off else null)
                    }
                }
            }
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(str(R.string.accent_colour), style = Vyrk.type.titleM, color = Vyrk.colors.text)
                Text(str(R.string.accent_desc), style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
                Spacer(Modifier.height(10.dp))
                ChipFlow(spacing = 12.dp) {
                    AccentChoice.entries.forEach { a ->
                        val on = s.accent == a.name
                        Box(
                            Modifier.size(40.dp).press({ upd { it.copy(accent = a.name) } }, tag = "set:accent:${a.name.lowercase()}")
                                .background(a.color, CircleShape).border(if (on) 3.dp else 1.dp, if (on) Vyrk.colors.text else Color.White.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(R.drawable.ic_check, null, Modifier.size(18.dp), tint = a.onColor) }
                    }
                }
            }
            PhoneToggle(str(R.string.featured_hero), s.heroEnabled, { v -> upd { it.copy(heroEnabled = v) } }, hint = str(R.string.featured_hero_desc))
            PhoneToggle(str(R.string.hero_rotation), s.heroRotation, { v -> upd { it.copy(heroRotation = v) } }, hint = str(R.string.hero_rotation_desc))
            PhoneToggle(str(R.string.reduce_motion), s.reduceMotion, { v -> upd { it.copy(reduceMotion = v) } }, hint = str(R.string.reduce_motion_desc))
            PhoneToggle(str(R.string.blur_unwatched), s.blurUnwatched, { v -> upd { it.copy(blurUnwatched = v) } }, hint = str(R.string.blur_unwatched_desc))
            if (s.hiddenRows.isNotEmpty()) PhoneListRow(str(R.string.hidden_rows), { upd { it.copy(hiddenRows = emptySet()) } }, icon = R.drawable.ic_rows,
                value = str(R.string.restore_rows, s.hiddenRows.size))
            if (s.top10CardDismissed) PhoneListRow(str(R.string.top10_suggestion), { upd { it.copy(top10CardDismissed = false) } }, icon = R.drawable.ic_trending,
                value = str(R.string.show_again))
        }
        PhoneGroup.SOUND -> PhoneCard {
            PhoneToggle(str(R.string.clear_dialogue), d.clearDialogue, { v -> graph.updateDevice { it.copy(clearDialogue = v) } }, hint = str(R.string.clear_dialogue_hint))
            PhoneToggle(str(R.string.software_audio), d.softwareAudio, { v -> graph.updateDevice { it.copy(softwareAudio = v) } })
        }
        PhoneGroup.ROWS -> PhoneCustomRows(session)
        PhoneGroup.ABOUT -> AboutPage(session)
    }
}

/** The rows you made, with up, down and remove, then the builder for a new one. */
@Composable
private fun PhoneCustomRows(session: ProfileSession) {
    val s by session.settings.collectAsState()
    val addons by session.addons.collectAsState()
    val list = remember(addons) { session.effectiveAddons() }
    val catalogs = remember(list) { com.vyrk.core.catalog.CustomRows.catalogs(list) }
    val c = Vyrk.colors
    fun upd(f: (com.vyrk.tv.data.ProfileSettings) -> com.vyrk.tv.data.ProfileSettings) = session.updateSettings(f)
    val tabNames = Tab.entries.associate { it.key to tabLabel(it) }
    fun tabName(key: String) = tabNames[key] ?: key
    val rows = s.customRows
    PhoneCard(title = str(R.string.settings_rows)) {
        if (rows.isEmpty()) Text(str(R.string.custom_rows_empty), style = Vyrk.type.bodyM, color = c.textSecondary)
        rows.forEachIndexed { i, r ->
            val ref = com.vyrk.core.catalog.CustomRows.resolve(r, list)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ref?.let { com.vyrk.core.catalog.CustomRows.title(r, it) } ?: r.catalogId, style = Vyrk.type.titleM, color = c.text, maxLines = 2)
                    Text(if (ref == null) str(R.string.custom_row_missing) else str(R.string.custom_row_on, tabName(r.tab), ref.addon.manifest.name), style = Vyrk.type.bodyS, color = c.textMuted)
                }
                if (i > 0) PhoneIconButton(R.drawable.ic_up, str(R.string.move_up), { upd { it.copy(customRows = com.vyrk.core.catalog.CustomRows.move(it.customRows, r.id, -1)) } })
                if (i < rows.lastIndex) PhoneIconButton(R.drawable.ic_down, str(R.string.move_down), { upd { it.copy(customRows = com.vyrk.core.catalog.CustomRows.move(it.customRows, r.id, 1)) } })
                PhoneIconButton(R.drawable.ic_trash, str(R.string.remove), { upd { it.copy(customRows = it.customRows.filterNot { x -> x.id == r.id }) } })
            }
        }
    }
    if (catalogs.isEmpty()) return
    var refKey by remember { mutableStateOf<String?>(null) }
    val ref = catalogs.firstOrNull { it.key == refKey } ?: catalogs.first()
    val genres = remember(ref.key) { com.vyrk.core.catalog.CustomRows.genres(ref) }
    val allowsAll = com.vyrk.core.catalog.CustomRows.allowsAll(ref)
    val tabs = com.vyrk.core.catalog.CustomRows.tabs(ref)
    var tab by remember(ref.key) { mutableStateOf(tabs.first()) }
    val ordered = remember(catalogs) { catalogs.sortedByDescending { com.vyrk.core.catalog.CatalogRules.isAnimeCatalog(it) } }
    fun tick(g: String?) = upd { it.copy(customRows = com.vyrk.core.catalog.CustomRows.toggle(it.customRows, ref, g, tab, java.util.UUID.randomUUID().toString())) }
    PhoneCard(title = str(R.string.add_a_row)) {
        PhoneChoice(str(R.string.row_catalogue), ordered.map { it.key to "${it.name} · ${com.vyrk.core.catalog.CatalogRules.typeLabel(it.catalog.type)}" }, ref.key, { refKey = it },
            hint = ref.addon.manifest.name, tag = "rows:catalogue")
        if (tabs.size > 1) PhoneChoice(str(R.string.row_tab), tabs.map { it to tabName(it) }, tab, { tab = it }, tag = "rows:tab")
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(str(R.string.row_genres), style = Vyrk.type.titleM, color = c.text)
            Text(str(R.string.row_genres_hint), style = Vyrk.type.bodyS, color = c.textMuted)
            Spacer(Modifier.height(8.dp))
            ChipFlow {
                if (allowsAll) {
                    val on = com.vyrk.core.catalog.CustomRows.has(rows, ref, null, tab)
                    PhoneChip(str(R.string.row_whole_catalogue), on, { tick(null) }, icon = if (on) R.drawable.ic_check else null, tag = "rows:genre:all")
                }
                genres.forEach { g ->
                    val on = com.vyrk.core.catalog.CustomRows.has(rows, ref, g, tab)
                    PhoneChip(com.vyrk.core.catalog.CustomRows.label(g), on, { tick(g) }, icon = if (on) R.drawable.ic_check else null, tag = "rows:genre:${g.lowercase()}")
                }
            }
        }
    }
}

@Composable
private fun LanguageChecks(label: String, chosen: List<String>, hint: String? = null, onToggle: (String) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = Vyrk.type.titleM, color = Vyrk.colors.text)
        if (hint != null) Text(hint, style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            LANG_CHOICES.forEach { l ->
                val i = chosen.indexOf(l)
                PhoneChip(Languages.label(l) + if (i >= 0 && chosen.size > 1) "  ${i + 1}" else "", i >= 0, { onToggle(l) }, icon = if (i >= 0) R.drawable.ic_check else null)
            }
        }
    }
}

@Composable
private fun SubtitleSample(color: String, background: String, size: Int) {
    val (fg, bg, scale) = subtitleStyleOf(color, background, size)
    Box(
        Modifier.fillMaxWidth().height(110.dp)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Vyrk.colors.steel, Vyrk.colors.panel)), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(str(R.string.subtitle_preview), color = fg, style = Vyrk.type.titleL.copy(fontSize = Vyrk.type.titleL.fontSize * scale,
            shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
            modifier = Modifier.padding(bottom = 18.dp).background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Add-ons: install by URL, save to the account, and each add-on with its actions. */
@Composable
private fun ColumnScope.AddonsPage(session: ProfileSession, onInstallUrl: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val addons by session.addons.collectAsState()
    val account by session.account.collectAsState()
    val state by session.state.collectAsState()
    val savedMsg = str(R.string.saved_to_account)
    val c = Vyrk.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneButton(str(R.string.install_from_url), onInstallUrl, icon = R.drawable.ic_plus, style = PhoneButtonStyle.PRIMARY, tag = "addons:install_url")
        if (account is AccountState.Connected) PhoneButton(str(R.string.save_to_account), { scope.launch { if (session.saveAddonsToAccount()) app.showToast(savedMsg) } })
    }
    if (account is AccountState.Connected && state.addonsRevision != state.addonsSyncedRevision)
        Text(str(R.string.addons_pending_sync), style = Vyrk.type.bodyS, color = c.textMuted)
    addons.forEachIndexed { i, d -> AddonCard(session, d, i, addons.lastIndex) }
}

/** Typing an add-on's URL; Next fetches it and asks to install. */
@Composable
private fun AddonUrlSheet(session: ProfileSession, typing: String?, onTyping: (String?) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val c = Vyrk.colors
    PhoneSheet(typing != null, { onTyping(null) }, str(R.string.install_addon_title)) {
        Text(str(R.string.addon_url), style = Vyrk.type.labelL, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(12.dp)).border(1.dp, c.gold.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(14.dp)) {
            if (typing.isNullOrEmpty()) Text("https://…/manifest.json", style = Vyrk.type.titleM, color = c.textMuted)
            BasicTextField(typing.orEmpty(), { onTyping(it) }, textStyle = Vyrk.type.titleM.copy(color = c.text), cursorBrush = SolidColor(c.gold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("addons:url_field"))
        }
        Spacer(Modifier.height(16.dp))
        PhoneButton(str(R.string.next), {
            val input = typing.orEmpty().trim(); onTyping(null)
            val url = com.vyrk.core.addon.AddonUrls.normaliseInstallUrl(input) ?: input
            app.push(Overlay.InstallConfirm(null, url, external = false))
            scope.launch {
                val r = session.fetchManifest(input)
                val top = app.overlays.lastOrNull()
                if (top is Overlay.InstallConfirm && top.input == url) app.replaceTop(top.copy(descriptor = r.getOrNull(), error = r.exceptionOrNull()?.let { "fetch" }))
            }
        }, style = PhoneButtonStyle.PRIMARY, enabled = !typing.isNullOrBlank(), modifier = Modifier.fillMaxWidth(), tag = "addons:url_next")
    }
}

@Composable
private fun AddonCard(session: ProfileSession, d: Descriptor, index: Int, lastIndex: Int) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val c = Vyrk.colors
    val m = d.manifest
    val refreshedMsg = str(R.string.addon_refreshed)
    PhoneCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(c.steel, RoundedCornerShape(11.dp)).border(1.dp, c.gold.copy(alpha = 0.4f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                if (m.logo == null) Text(m.name.take(1).uppercase(), style = Vyrk.type.cinematicS, color = c.gold.copy(alpha = 0.7f))
                m.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(5.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.name, style = Vyrk.type.titleM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Tag("v${m.version}", color = c.gold, background = c.gold.copy(alpha = 0.14f))
                }
                Text(Redact.url(d.transportUrl), style = Vyrk.type.labelS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        m.description?.let { Text(it, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(6.dp))
        ChipFlow(spacing = 5.dp) {
            m.resources.forEach { Tag(it.name) }
            m.types.forEach { Tag(it, background = c.gold.copy(alpha = 0.16f), color = c.gold) }
        }
        if (CatalogRules.needsStreamingServer(d)) Text(str(R.string.needs_streaming_server), style = Vyrk.type.bodyS, color = c.warning, modifier = Modifier.padding(top = 6.dp))
        if (com.vyrk.core.addon.AddonUrls.isUnencryptedPublic(d.transportUrl)) Text(str(R.string.addon_not_encrypted), style = Vyrk.type.bodyS, color = c.warning, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) PhoneIconButton(R.drawable.ic_up, str(R.string.move_up), { scope.launch { session.move(m.id, -1) } }, size = 42.dp)
            if (index < lastIndex) PhoneIconButton(R.drawable.ic_down, str(R.string.move_down), { scope.launch { session.move(m.id, 1) } }, size = 42.dp)
            if (m.behaviorHints.configurable) PhoneIconButton(R.drawable.ic_cog, str(R.string.configure), { app.push(Overlay.AddonQr(d)) }, size = 42.dp, tag = "addon:$index:configure")
            PhoneIconButton(R.drawable.ic_refresh, str(R.string.refresh), { scope.launch { if (session.refreshAddon(m.id)) app.showToast(refreshedMsg) } }, size = 42.dp)
            Spacer(Modifier.weight(1f))
            if (!d.flags.protected) PhoneButton(str(R.string.remove), { scope.launch { session.remove(m.id) } }, icon = R.drawable.ic_trash, style = PhoneButtonStyle.OUTLINE,
                height = 42.dp, tag = "addon:$index:remove")
        }
    }
}

@Composable
private fun AboutPage(session: ProfileSession) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val ctx = LocalActivity.current
    val scope = rememberCoroutineScope()
    val d by graph.deviceSettings.collectAsState()
    val updater = remember { Updater(graph.context, graph.http) }
    var status by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Updater.Check.Available?>(null) }
    val c = Vyrk.colors
    val upToDate = str(R.string.up_to_date)
    PhoneCard {
        Text(str(R.string.about_body), style = Vyrk.type.bodyM, color = c.textSecondary)
        Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Vyrk.type.labelL, color = c.text, modifier = Modifier.padding(top = 8.dp))
    }
    if (!BuildConfig.STORE_BUILD) PhoneCard(title = str(R.string.updates)) {
        Text(str(R.string.update_source, BuildConfig.UPDATE_REPO.ifEmpty { "—" }), style = Vyrk.type.bodyS, color = c.textMuted)
        Spacer(Modifier.height(8.dp))
        PhoneButton(str(R.string.check_for_updates), {
            status = null
            scope.launch {
                when (val r = updater.check()) {
                    is Updater.Check.Available -> { available = r; status = r.release.name ?: r.release.tag_name }
                    is Updater.Check.Failed -> status = ctx.getString(R.string.update_failed, r.reason)
                    else -> status = upToDate
                }
                graph.updateDevice { it.copy(lastUpdateCheckMs = System.currentTimeMillis()) }
            }
        }, icon = R.drawable.ic_refresh, tag = "about:check_updates")
        PhoneToggle(str(R.string.automatic_updates), d.autoUpdateChecks, { graph.updateDevice { it.copy(autoUpdateChecks = !it.autoUpdateChecks) } })
        status?.let { Text(it, style = Vyrk.type.bodyM, color = c.textSecondary) }
        available?.let { a ->
            Text(a.release.body.orEmpty().take(600), style = Vyrk.type.bodyS, color = c.textMuted, modifier = Modifier.padding(vertical = 8.dp))
            PhoneButton(str(R.string.download_install), {
                if (!updater.canInstall()) { updater.requestInstallPermission(); return@PhoneButton }
                scope.launch {
                    updater.download(a.asset) { p -> status = "${(p * 100).toInt()}%" }
                        .onSuccess { updater.install(it) }
                        .onFailure { status = ctx.getString(R.string.update_failed, it.message ?: "") }
                }
            }, style = PhoneButtonStyle.PRIMARY)
        }
    }
    PhoneCard {
        PhoneListRow(str(R.string.third_party_notices), {
            val text = graph.context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
            app.push(Overlay.Message(ctx.getString(R.string.third_party_notices), text))
        }, icon = R.drawable.ic_info)
        val copied = str(R.string.diagnostics_copied)
        PhoneListRow(str(R.string.copy_diagnostics), {
            val r = graph.device.report
            val text = buildString {
                appendLine("VYRK ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL} phone")
                appendLine("decoders=${r.decoders} display=${r.displayWidth}x${r.displayHeight} hdr10=${r.hdr10} dv=${r.dolbyVision}")
                appendLine("addons=${session.addons.value.size} library=${session.library.value.size}")
                Diagnostics.dump().forEach { appendLine(it) }
            }
            (graph.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VYRK diagnostics", text))
            app.showToast(copied)
        }, icon = R.drawable.ic_swap)
    }
}
