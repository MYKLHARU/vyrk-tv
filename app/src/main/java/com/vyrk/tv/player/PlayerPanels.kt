package com.vyrk.tv.player

import com.vyrk.tv.ui.components.focusHalo
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.vyrk.core.lang.Languages
import com.vyrk.core.stream.Ranked
import com.vyrk.tv.R
import com.vyrk.tv.VyrkApp
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.flow.first
import com.vyrk.tv.ui.tryRequestFocus

// The player's side panels: audio and subtitles, other sources, sleep and episodes.

/** Side panel for Other sources and Sleep, matching the Audio & subtitles panel. */
@Composable
private fun SidePanel(title: String, content: @Composable () -> Unit) {
    val c = Vyrk.colors
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.padding(end = 28.dp, top = 24.dp, bottom = 24.dp).width(400.dp).fillMaxHeight()
                .background(c.background.copy(alpha = 0.94f), RoundedCornerShape(22.dp))
                .padding(22.dp).focusGroup(),
        ) {
            Text(title, style = Vyrk.type.headlineL, color = c.text)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** A row in the Audio & subtitles list: name, a plain tag ("Original", "Forced · in the file"), and a tick when chosen. */
@Composable
private fun TrackRow(name: String, tag: String?, selected: Boolean, trace: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Vyrk.colors
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.focusHalo().fillMaxWidth().traceFocus(trace).semantics { contentDescription = listOfNotNull(name, tag).joinToString(", ") },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = c.text, focusedContainerColor = c.gold.copy(alpha = 0.16f), focusedContentColor = c.goldHighlight),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, c.gold))),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Vyrk.type.titleL, color = c.text, maxLines = 1)
            if (!tag.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(tag, style = Vyrk.type.bodyM, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            if (selected) com.vyrk.tv.ui.components.Icon(R.drawable.ic_check, null, Modifier.size(18.dp), tint = c.gold)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), style = Vyrk.type.labelL.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)),
        color = Vyrk.colors.gold, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 4.dp))
}

private fun codecName(mime: String?): String? {
    val c = mime?.lowercase()?.replace("-", "") ?: return null
    return when {
        "truehd" in c -> "TrueHD"
        "dts" in c -> "DTS"
        "eac3" in c || "ec3" in c -> "Dolby Digital+"
        "ac3" in c -> "Dolby Digital"
        "mp4a" in c || "aac" in c -> "AAC"
        "opus" in c -> "Opus"
        "flac" in c -> "FLAC"
        "mpeg" in c -> "MP3"
        else -> null
    }
}

@Composable
private fun channelsName(n: Int): String? = when (n) {
    1 -> stringResource(R.string.mono); 2 -> stringResource(R.string.stereo); 6 -> "5.1"; 8 -> "7.1"; 0 -> null; else -> "${n}ch"
}

@Composable
internal fun AudioSubsPanel(m: PlayerModel) {
    val c = Vyrk.colors
    val selectedReq = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val title = m.meta?.name ?: m.request.meta.name
    val sub = m.video?.let { v -> if (v.season != null && v.episode != null) "S${v.season}E${v.episode} · ${v.displayTitle}" else null }
        ?: (m.meta?.releaseInfo ?: m.request.meta.releaseInfo)?.take(4)
    val audio = m.engine.audioTracks()
    val subs = com.vyrk.core.playback.TrackSelector.sortForDisplay(m.allTextTracks(), m.sessionSettingsLanguages())
    val inFile = stringResource(R.string.tag_in_file)
    val forced = stringResource(R.string.tag_forced)
    val commentary = stringResource(R.string.tag_commentary)
    val subsSelectedIndex = subs.indexOfFirst { it.id == m.selectedSub }
    // The selected subtitle can be below the viewport (especially with many audio tracks).
    // Materialize its row before requesting focus instead of dropping a one-shot request.
    val focusRow = audio.size + 2 + if (subsSelectedIndex >= 0) subsSelectedIndex + 1 else 0
    LaunchedEffect(focusRow, m.selectedSub, subs.map { it.id }) {
        listState.scrollToItem(focusRow)
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == focusRow } }.first { it }
        androidx.compose.runtime.withFrameNanos { }
        selectedReq.requestFocus()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.padding(start = 28.dp, top = 24.dp, bottom = 24.dp).width(400.dp).fillMaxHeight()
                .background(c.background.copy(alpha = 0.94f), RoundedCornerShape(22.dp))
                .padding(horizontal = 22.dp, vertical = 22.dp).focusGroup(),
        ) {
            Text(stringResource(R.string.audio_subtitles), style = Vyrk.type.headlineL, color = c.text)
            Text(listOfNotNull(title, sub).joinToString("  ·  "), style = Vyrk.type.bodyM, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyColumn(Modifier.padding(top = 4.dp), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { SectionHeader(stringResource(R.string.audio)) }
                itemsIndexed(audio, key = { _, t -> "a" + t.id }) { i, t ->
                    // "und" (undetermined) and friends aren't languages: number the track instead.
                    val lang = t.language?.takeUnless { it.lowercase() in setOf("und", "mul", "zxx", "mis") }?.let { Languages.name(it) }
                    val extra = t.label?.takeIf { l -> lang == null || !l.equals(lang, true) && !l.equals(t.language, true) }
                    val tag = listOfNotNull(
                        extra,
                        if (com.vyrk.core.playback.TrackSelector.isCommentary(t) && extra?.contains(commentary, true) != true) commentary else null,
                        codecName(t.codec), channelsName(t.channels),
                    ).joinToString(" · ")
                    val sel = m.selectedAudio == t.id || (m.selectedAudio == null && t.isDefault)
                    TrackRow(lang ?: t.label ?: stringResource(R.string.audio_track_n, i + 1), tag, sel, "panel:audio:${t.id}") { m.selectAudio(t.id) }
                }
                item { SectionHeader(stringResource(R.string.subtitles)) }
                item {
                    TrackRow(stringResource(R.string.off), null, m.selectedSub == null, "panel:sub_off",
                        if (subsSelectedIndex < 0) Modifier.focusRequester(selectedReq) else Modifier) { m.selectSubtitle(null) }
                }
                val counts = subs.groupingBy { (it.language ?: "") + "|" + it.source }.eachCount()
                val seen = mutableMapOf<String, Int>()
                val numbered = subs.map { t -> val k = (t.language ?: "") + "|" + t.source; val n = (seen[k] ?: 0) + 1; seen[k] = n; Triple(t, n, counts[k] ?: 1) }
                items(numbered, key = { "s" + it.first.id }) { (t, n, total) ->
                    val name = t.language?.let { Languages.name(it) } ?: t.label ?: "—"
                    val where = if (t.source == "Embedded") inFile else t.source
                    val tag = listOfNotNull(if (t.forced) forced else null, where, if (total > 1) "#$n" else null).joinToString(" · ")
                    TrackRow(name, tag, m.selectedSub == t.id, "panel:sub:${t.id}",
                        if (t.id == m.selectedSub) Modifier.focusRequester(selectedReq) else Modifier) { m.selectSubtitle(t.id) }
                }
                item { SectionHeader(stringResource(R.string.sync_style)) }
                item {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.subtitle_delay, "%.1f".format(m.subDelayMs / 1000f)), style = Vyrk.type.titleM, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VyrkButton("−0.5 s", onClick = { m.subDelayMs = (m.subDelayMs - 500).coerceAtLeast(-10_000) }, modifier = Modifier.traceFocus("panel:delay_minus"))
                            VyrkButton("+0.5 s", onClick = { m.subDelayMs = (m.subDelayMs + 500).coerceAtMost(10_000) }, modifier = Modifier.traceFocus("panel:delay_plus"))
                            VyrkButton(stringResource(R.string.reset), onClick = { m.subDelayMs = 0 }, modifier = Modifier.traceFocus("panel:delay_reset"))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.delay_addon_only), style = Vyrk.type.bodyS, color = c.textMuted)
                    }
                }
            }
        }
    }
}
fun PlayerModel.sessionSettingsLanguages(): List<String> = VyrkApp.graphOrNull()?.session?.value?.settings?.value?.subtitleLanguages.orEmpty()

@Composable
internal fun SourcesPanel(m: PlayerModel) {
    val first = remember { FocusRequester() }
    val choices = m.sourceChoices
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    SidePanel(stringResource(R.string.other_sources)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(choices, key = { it.candidate.key + it.candidate.addonId }) { r ->
                val idx = choices.indexOf(r)
                if (m.suspectSource(r)) Text(stringResource(R.string.stream_may_be_incomplete), style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
                SourceLine(r, current = m.current?.candidate?.key == r.candidate.key, modifier = if (idx == 0) Modifier.focusRequester(first) else Modifier) { m.pickSource(r) }
            }
            if (m.excluded.isNotEmpty()) item { Text(stringResource(R.string.not_playable_here), style = Vyrk.type.labelL, color = Vyrk.colors.textMuted, modifier = Modifier.padding(top = 10.dp)) }
            items(m.excluded, key = { "x" + it.candidate.key + it.candidate.addonId }) { r ->
                Text("${r.candidate.addonName} · ${r.info.resolutionLabel} — ${exclusionText(r)}", style = Vyrk.type.bodyS, color = Vyrk.colors.textMuted)
            }
            // Another player for this source, from where you are.
            item { PlayWithOtherApps(Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
fun PlayWithOtherApps(modifier: Modifier = Modifier) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? PlayerActivity ?: return
    val apps = remember { ExternalPlayers.installed(activity) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.play_with), style = Vyrk.type.labelL, color = Vyrk.colors.textMuted)
        apps.take(4).forEach { app ->
            VyrkChip(stringResource(R.string.play_with_app, app.label), false, { activity.playExternallyNow(app.pkg) },
                Modifier.fillMaxWidth().traceFocus("player:external:${app.pkg}"))
        }
        VyrkChip(stringResource(R.string.play_with_other_app), false, { activity.playExternallyNow(PlayerActivity.ASK) },
            Modifier.fillMaxWidth().traceFocus("player:external:ask"))
    }
}

@Composable
fun exclusionText(r: Ranked): String = when (r.exclusion) {
    com.vyrk.core.stream.Exclusion.TORRENT_NO_SERVER -> stringResource(R.string.excl_torrent)
    com.vyrk.core.stream.Exclusion.EXTERNAL, com.vyrk.core.stream.Exclusion.YOUTUBE -> stringResource(R.string.excl_external)
    com.vyrk.core.stream.Exclusion.UNKNOWN_SOURCE -> stringResource(R.string.excl_unknown)
    com.vyrk.core.stream.Exclusion.OVER_MAX_RESOLUTION -> stringResource(R.string.excl_max_res)
    com.vyrk.core.stream.Exclusion.OVER_MAX_SIZE -> stringResource(R.string.excl_max_size)
    com.vyrk.core.stream.Exclusion.UNCACHED -> stringResource(R.string.excl_uncached)
    com.vyrk.core.stream.Exclusion.CODEC_UNSUPPORTED -> stringResource(R.string.excl_codec)
    com.vyrk.core.stream.Exclusion.OVER_DECODER_LIMIT -> stringResource(R.string.excl_decoder)
    com.vyrk.core.stream.Exclusion.TEN_BIT_UNSUPPORTED -> stringResource(R.string.excl_ten_bit)
    com.vyrk.core.stream.Exclusion.DOLBY_VISION_UNSUPPORTED -> stringResource(R.string.excl_dolby_vision)
    com.vyrk.core.stream.Exclusion.FAILED_ON_THIS_TV -> stringResource(R.string.excl_failed_here)
    com.vyrk.core.stream.Exclusion.FROZE_THIS_TV -> stringResource(R.string.excl_froze_here)
    null -> ""
}

@Composable
fun SourceLine(r: Ranked, current: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val i = r.info
    val parts = listOf(i.resolutionLabel, i.releaseType.label, i.codec.label, i.hdr.joinToString("/") { it.label }, i.audio.joinToString(" ") { it.label }, i.channels.orEmpty(),
        i.sizeBytes?.let { "%.1f GB".format(it / 1e9) }.orEmpty(), com.vyrk.tv.ui.components.debridTag(r).orEmpty()).filter { it.isNotBlank() }
    val langs = i.languages.joinToString(", ") { Languages.label(it) }
    val text = buildString {
        append(r.candidate.addonName); append('\n'); append(parts.joinToString(" · ").ifEmpty { "—" })
        if (langs.isNotEmpty()) { append('\n'); append(langs) }
    }
    VyrkChip(text, current, onClick, modifier.fillMaxWidth().traceFocus("source:${r.candidate.addonId}:${r.candidate.index}"), maxLines = 3)
}

@Composable
internal fun SleepPanel(m: PlayerModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    SidePanel(stringResource(R.string.sleep)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VyrkChip(stringResource(R.string.off), m.sleep == SleepMode.OFF, { m.chooseSleep(SleepMode.OFF) }, Modifier.fillMaxWidth().focusRequester(first).traceFocus("sleep:off"))
            VyrkChip(stringResource(R.string.sleep_after_episode), m.sleep == SleepMode.END_OF_EPISODE, { m.chooseSleep(SleepMode.END_OF_EPISODE) }, Modifier.fillMaxWidth().traceFocus("sleep:episode"))
            VyrkChip(stringResource(R.string.sleep_60), m.sleep == SleepMode.MINUTES_60, { m.chooseSleep(SleepMode.MINUTES_60) }, Modifier.fillMaxWidth().traceFocus("sleep:60"))
        }
    }
}

/** Episodes, in the player: the seasons along the top, then the season's episodes; OK plays one. */
@Composable
internal fun EpisodesPanel(m: PlayerModel) {
    val c = Vyrk.colors
    val videos = m.meta?.videos.orEmpty()
    val currentId = m.video?.id ?: m.request.videoId
    val seasons = videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
    var season by remember { androidx.compose.runtime.mutableStateOf(m.video?.season ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull()) }
    val eps = videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    val listState = rememberLazyListState()
    val currentReq = remember { FocusRequester() }
    val firstReq = remember { FocusRequester() }
    LaunchedEffect(season) {
        val i = eps.indexOfFirst { it.id == currentId }
        if (i > 0) listState.scrollToItem(i)
        kotlinx.coroutines.delay(60)
        (if (i >= 0) currentReq else firstReq).tryRequestFocus()
    }
    SidePanel(stringResource(R.string.episodes)) {
        if (seasons.size > 1) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                items(seasons) { s ->
                    VyrkChip(if (s == 0) stringResource(R.string.specials) else stringResource(R.string.season_n, s), s == season, { season = s },
                        Modifier.traceFocus("episodes:season:$s"))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
            itemsIndexed(eps, key = { _, v -> v.id }) { i, v ->
                val label = listOfNotNull(v.episode?.let { "E$it" }, v.displayTitle).joinToString("  ")
                val mod = when {
                    v.id == currentId -> Modifier.focusRequester(currentReq)
                    i == 0 -> Modifier.focusRequester(firstReq)
                    else -> Modifier
                }
                VyrkChip(label, v.id == currentId, { m.playEpisode(v) }, mod.fillMaxWidth().traceFocus("episodes:${v.id}"), maxLines = 2)
            }
        }
    }
}
