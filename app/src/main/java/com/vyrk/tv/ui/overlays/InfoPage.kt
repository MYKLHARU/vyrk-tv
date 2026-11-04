package com.vyrk.tv.ui.overlays

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.Video
import com.vyrk.core.lang.Languages
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.stream.Candidate
import com.vyrk.core.stream.Ranked
import com.vyrk.core.stream.StreamRanker
import com.vyrk.tv.R
import com.vyrk.tv.player.Playback
import com.vyrk.tv.player.SourceLine
import com.vyrk.tv.player.exclusionText
import com.vyrk.tv.ui.LocalActivity
import com.vyrk.tv.ui.LocalApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.LocalSession
import com.vyrk.tv.ui.Overlay
import com.vyrk.tv.ui.Section
import com.vyrk.tv.ui.browse.MetaFacts
import com.vyrk.tv.ui.browse.TitleLogo
import com.vyrk.tv.ui.components.ButtonStyle
import com.vyrk.tv.ui.components.Tag
import com.vyrk.tv.ui.components.VyrkButton
import com.vyrk.tv.ui.components.VyrkChip
import com.vyrk.tv.ui.components.VyrkIconButton
import com.vyrk.tv.ui.components.WrapRow
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import kotlinx.coroutines.launch

private sealed interface SourcesState {
    data object Idle : SourcesState
    data object Loading : SourcesState
    data class Ready(val ranked: List<Ranked>, val failedAddons: Int, val addonCount: Int) : SourcesState
}

@Composable
fun InfoPage(o: Overlay.Info) {
    val app = LocalApp.current
    val graph = LocalGraph.current
    val activity = LocalActivity.current
    val session = LocalSession.current ?: return
    val scope = rememberCoroutineScope()
    val settings by session.settings.collectAsState()
    val library by session.library.collectAsState()
    val sstate by session.state.collectAsState()
    val meta = o.meta
    var full by remember { mutableStateOf(graph.metaCache.peek(meta.type, meta.id)) }
    LaunchedEffect(meta.id) { if (full == null) full = graph.metaCache.get(session.effectiveAddons(), meta.type, meta.id) }
    // Cast and crew with photos from TMDB, when the build has a TMDB key and the title has an IMDb ID.
    var tmdbCredits by remember { mutableStateOf<com.vyrk.core.tmdb.Credits?>(null) }
    LaunchedEffect(meta.id) {
        if (!graph.tmdb.enabled || !meta.id.startsWith("tt")) return@LaunchedEffect
        val found = graph.tmdb.findByImdb(meta.id.substringBefore(':'), preferTv = meta.type == "series") ?: return@LaunchedEffect
        tmdbCredits = graph.tmdb.credits(found.first, found.second)
    }
    val item: LibraryItem? = library[meta.id]
    val isSeries = meta.type == "series" && (full?.videos?.isNotEmpty() ?: true)
    var sources by remember { mutableStateOf<SourcesState>(SourcesState.Idle) }
    val playReq = remember { FocusRequester() }
    val sourcesReq = remember { FocusRequester() }
    var showSourcesPanel by remember { mutableStateOf(settings.sourcesOnInfoPage || o.showSources) }

    fun loadSources() {
        val vid = meta.behaviorHints.defaultVideoId ?: meta.id
        sources = SourcesState.Loading
        scope.launch {
            val addons = session.effectiveAddons()
            val results = graph.addonClient.allStreams(addons, meta.type, vid)
            val cands = results.flatMapIndexed { order, (a, r) ->
                (r as? AddonResult.Ok)?.value?.streams.orEmpty().mapIndexed { i, s -> Candidate(s, a.manifest.id, a.manifest.name, order, i) }
            }
            val mem = session.titleMemory(meta.id)
            val prefs = com.vyrk.core.stream.RankPrefs(
                preset = settings.preset, maxResolution = settings.maxResolution, maxSizeBytes = settings.maxSizeGb?.let { it * 1_000_000_000L },
                format = settings.format, hdr = settings.hdr, audioLanguages = settings.audioLanguages,
                rememberedKey = mem.pickedKey, lastBingeGroup = mem.bingeGroup, lastReleaseGroup = mem.releaseGroup,
                runtimeMinutes = full?.runtime?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
            )
            sources = SourcesState.Ready(StreamRanker.rank(cands, graph.device.caps(graph.deviceSettings.value.measuredBps), prefs), results.count { it.second is AddonResult.Failed }, results.size)
        }
    }
    LaunchedEffect(meta.id) {
        if (!isSeries && settings.loadSourcesOnOpen && showSourcesPanel) loadSources()
        runCatching { playReq.requestFocus() }
    }

    val c = Vyrk.colors
    val art = full?.background ?: meta.background
    Box(Modifier.fillMaxSize()) {
        // Blurred artwork (decoded tiny, blurred 24 dp on Android 12+; plain dimmed artwork before) under a 55% tint.
        if (art != null) {
            val ctx = LocalContext.current
            val req = remember(art) { ImageRequest.Builder(ctx).data(art).size(Size(96, 54)).build() }
            AsyncImage(req, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val narrow = maxWidth < 760.dp
            val split = (showSourcesPanel || isSeries) && !narrow
            val panelW = if (split) maxWidth * 0.92f else maxWidth * 0.62f
            var tab by remember { mutableStateOf(0) }
            Row(Modifier.width(panelW).fillMaxHeight(0.9f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF1B2B38).copy(alpha = 0.60f))) {
                // ---- Info pane ----
                Column(Modifier.weight(if (split) 0.54f else 1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().height(230.dp)) {
                        if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    // Alpha mask: fades the artwork into the panel with no hard edge.
                                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.8f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                                })
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp)) {
                            Text(typeLabel(meta.type), style = Vyrk.type.labelM, color = c.accent)
                            Spacer(Modifier.height(6.dp))
                            TitleLogo(meta.copy(logo = full?.logo ?: meta.logo), 320.dp, 86.dp)
                        }
                    }
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
                        MetaFacts(meta, full)
                        full?.certification?.let { Spacer(Modifier.height(6.dp)); Tag(it) }
                        Spacer(Modifier.height(10.dp))
                        val synopsis = full?.description ?: meta.description ?: ""
                        var expanded by remember { mutableStateOf(false) }
                        Text(synopsis, style = Vyrk.type.bodyM, color = c.textSecondary, maxLines = if (expanded) 40 else 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(14.dp))
                        val inCw = item?.isInContinueWatching == true
                        WrapRow {
                            VyrkButton(str(if (inCw) R.string.resume else R.string.play), icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY,
                                modifier = Modifier.focusRequester(playReq).traceFocus("info:play"), onClick = {
                                    val ranked = (sources as? SourcesState.Ready)?.ranked
                                    app.overlays.remove(o); o.origin?.let { app.restore = it }
                                    Playback.start(activity, graph, session, meta, ranked = if (isSeries) null else ranked, origin = o.origin)
                                })
                            VyrkButton(str(R.string.trailer), icon = R.drawable.ic_trailer, modifier = Modifier.traceFocus("info:trailer"), onClick = { closeOverlay(app, o); app.preview = meta })
                            val inLib = item?.inLibrary == true
                            VyrkButton(str(if (inLib) R.string.in_library else R.string.add_to_library), icon = if (inLib) R.drawable.ic_check else R.drawable.ic_plus, modifier = Modifier.traceFocus("info:library"),
                                onClick = { if (inLib) session.removeFromLibrary(meta.id) else session.addToLibrary(meta) })
                            if (!isSeries) VyrkButton(str(R.string.sources), icon = R.drawable.ic_sources, modifier = Modifier.traceFocus("info:sources"), onClick = {
                                showSourcesPanel = true; if (sources is SourcesState.Idle) loadSources(); tab = 1
                                scope.launch { kotlinx.coroutines.delay(60); runCatching { sourcesReq.requestFocus() } }
                            })
                            VyrkButton(str(if (item?.watched == true) R.string.mark_unwatched else R.string.mark_watched), icon = R.drawable.ic_check, modifier = Modifier.traceFocus("info:watched"),
                                onClick = { session.markWatched(meta, item?.watched != true) })
                            if (synopsis.length > 260 || (full?.castNames?.size ?: 0) > 3) VyrkButton(str(if (expanded) R.string.less else R.string.more), modifier = Modifier.traceFocus("info:more"), onClick = { expanded = !expanded })
                        }
                        Spacer(Modifier.height(16.dp))
                        full?.directorNames?.takeIf { it.isNotEmpty() }?.let { Credit(str(R.string.director), it.joinToString(", ")) }
                        full?.writerNames?.takeIf { it.isNotEmpty() }?.let { Credit(str(R.string.writers), it.take(4).joinToString(", ")) }
                        full?.country?.takeIf { it.isNotBlank() }?.let { Credit(str(R.string.country), it) }
                        val cast = full?.castNames.orEmpty()
                        val tc = tmdbCredits
                        if (tc != null && (tc.cast.isNotEmpty() || tc.keyCrew.isNotEmpty())) {
                            Spacer(Modifier.height(6.dp))
                            Text(str(R.string.cast_and_crew), style = Vyrk.type.labelL, color = c.textMuted)
                            CastCrewRow(tc.cast, tc.keyCrew)
                        } else if (cast.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(str(R.string.starring_label), style = Vyrk.type.labelL, color = c.textMuted)
                            Spacer(Modifier.height(6.dp))
                            WrapRow(spacing = 6.dp) {
                                cast.take(if (expanded) 20 else 6).forEach { person ->
                                    VyrkChip(person, false, onClick = {
                                        app.overlays.clear(); app.searchQuery = person; app.section = Section.SEARCH
                                    }, modifier = Modifier.traceFocus("info:cast"))
                                }
                            }
                        }
                        full?.imdbRating?.let { Spacer(Modifier.height(10.dp)); Text(str(R.string.rating_attribution), style = Vyrk.type.labelS, color = c.textMuted) }
                    }
                }
                // ---- Right pane: season guide for series, sources for movies ----
                if (split) Column(Modifier.weight(0.46f).fillMaxHeight().background(Color.Black.copy(alpha = 0.18f)).padding(22.dp).focusGroup()) {
                    if (isSeries) SeasonGuide(full, item, sstate.newEpisodes[meta.id].orEmpty(), settings.blurUnwatched, onPlay = { v ->
                        app.overlays.remove(o); o.origin?.let { app.restore = it }
                        Playback.start(activity, graph, session, meta, videoId = v.id, origin = o.origin)
                    }, onMark = { videos, watched -> full?.let { session.markVideos(it, videos, watched) } })
                    else SourcesPane(sources, sourcesReq, onRefresh = { loadSources() }, onPick = { r ->
                        app.overlays.remove(o); o.origin?.let { app.restore = it }
                        Playback.start(activity, graph, session, meta, picked = r, ranked = (sources as? SourcesState.Ready)?.ranked, origin = o.origin)
                    })
                }
            }
        }
    }
}

@Composable
private fun typeLabel(t: String) = when (t) { "movie" -> str(R.string.type_movie); "series" -> str(R.string.type_series); "tv" -> str(R.string.type_live); else -> t.uppercase() }

@Composable
private fun Credit(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label  ", style = Vyrk.type.labelL, color = Vyrk.colors.textMuted)
        Text(value, style = Vyrk.type.bodyS, color = Vyrk.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourcesPane(state: SourcesState, firstReq: FocusRequester, onRefresh: () -> Unit, onPick: (Ranked) -> Unit) {
    val c = Vyrk.colors
    var filter by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf<String?>(null) }
    Text(str(R.string.ready_when_you_are), style = Vyrk.type.labelM, color = c.accent)
    val ready = state as? SourcesState.Ready
    val playable = ready?.ranked?.filter { it.playable }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(str(R.string.streams_count, playable.size), style = Vyrk.type.headlineM, color = c.text)
            Text(str(R.string.sources_available, ready?.ranked?.size ?: 0), style = Vyrk.type.bodyS, color = c.textSecondary)
        }
        VyrkIconButton(R.drawable.ic_refresh, str(R.string.refresh), onRefresh, size = 34.dp, modifier = Modifier.traceFocus("sources:refresh"))
    }
    Spacer(Modifier.height(10.dp))
    when {
        state is SourcesState.Idle -> Text(str(R.string.press_sources), style = Vyrk.type.bodyM, color = c.textMuted)
        state is SourcesState.Loading -> Text(str(R.string.finding_sources), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null && ready.addonCount == 0 -> Text(str(R.string.no_stream_addons), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null && ready.ranked.isEmpty() && ready.failedAddons > 0 -> Text(str(R.string.sources_addons_failed), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null && ready.ranked.isEmpty() -> Text(str(R.string.no_sources_found), style = Vyrk.type.bodyM, color = c.textMuted)
        ready != null -> {
            val langs = playable.flatMap { it.info.languages }.distinct()
            val filters = listOf("all", "4K", "1080p", "720p", "480p") + langs
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filters) { f ->
                    VyrkChip(if (f == "all") str(R.string.filter_all) else if (f in langs) Languages.name(f) else f, filter == f, { filter = f }, Modifier.traceFocus("sources:filter:$f"))
                }
            }
            Spacer(Modifier.height(10.dp))
            val shown = ready.ranked.filter { r ->
                when (filter) {
                    "all" -> true
                    "4K" -> (r.info.resolution ?: 0) >= 2160
                    "1080p" -> r.info.resolution == 1080
                    "720p" -> r.info.resolution == 720
                    "480p" -> (r.info.resolution ?: 0) in 1..576
                    else -> filter in r.info.languages
                }
            }
            val topKey = playable.firstOrNull()?.candidate?.key
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.candidate.addonId + it.candidate.index }) { r ->
                    val k = r.candidate.addonId + r.candidate.index
                    Column {
                        if (r.playable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    SourceLine(r, current = false, modifier = if (r.candidate.key == topKey) Modifier.focusRequester(firstReq) else Modifier) { onPick(r) }
                                }
                                Spacer(Modifier.width(6.dp))
                                VyrkIconButton(R.drawable.ic_info, str(R.string.source_details), { expanded = if (expanded == k) null else k }, size = 30.dp, modifier = Modifier.traceFocus("sources:details"))
                            }
                            if (r.candidate.key == topKey) Tag(str(R.string.recommended), color = c.onAccent, background = c.accent, modifier = Modifier.padding(top = 4.dp))

                            if (expanded == k) {
                                Text(r.candidate.stream.text, style = Vyrk.type.bodyS, color = c.textSecondary)
                                Text(r.reasons.joinToString("  ") { "${it.code} ${if (it.points > 0) "+" else ""}${it.points}" } + "  = ${r.score}", style = Vyrk.type.labelS, color = c.textMuted)
                            }
                        } else {
                            Text("${r.candidate.addonName} · ${r.info.resolutionLabel.ifEmpty { "—" }} — ${exclusionText(r)}", style = Vyrk.type.bodyS, color = c.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonGuide(meta: MetaItem?, item: LibraryItem?, newIds: Set<String>, blurUnwatched: Boolean, onPlay: (Video) -> Unit, onMark: (List<Video>, Boolean) -> Unit) {
    val c = Vyrk.colors
    if (meta == null) { Text(str(R.string.loading), style = Vyrk.type.bodyM, color = c.textMuted); return }
    val seasons = meta.videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
    val resumeSeason = item?.state?.videoId?.let { id -> meta.videos.firstOrNull { it.id == id }?.season }
    var season by remember(meta.id) { mutableStateOf(resumeSeason ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1) }
    val wb = item?.watchedBitField(meta.videos)
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(seasons) { s -> VyrkChip(if (s == 0) str(R.string.specials) else str(R.string.season_n, s), s == season, { season = s }, Modifier.traceFocus("season:$s")) }
    }
    Spacer(Modifier.height(8.dp))
    val eps = meta.videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    VyrkButton(str(R.string.mark_season_watched), modifier = Modifier.traceFocus("season:mark"), onClick = { onMark(eps, true) })
    Spacer(Modifier.height(8.dp))
    val now = System.currentTimeMillis()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(eps, key = { it.id }) { v ->
            val watched = wb?.getVideo(v.id) == true
            val released = v.released?.let { LibraryItem.parseTime(kotlinx.serialization.json.JsonPrimitive(it)) }
            val future = released != null && released > now
            val progress = if (item?.state?.videoId == v.id && item.isInContinueWatching) item.progress else 0f
            val title = "S${v.season}E${v.episode} ${v.displayTitle}"
            val date = released?.let { (if (future) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)).format(java.util.Date(it)) }
            Column {
                androidx.tv.material3.Surface(
                    onClick = { if (!future) onPlay(v) }, onLongClick = { menuFor = if (menuFor == v.id) null else v.id },
                    modifier = Modifier.fillMaxWidth().traceFocus("episode:${v.id}")
                        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { menuFor = v.id; true } else false },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = c.raised.copy(alpha = 0.7f), focusedContainerColor = c.raised),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None),
                    glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(focusedGlow = com.vyrk.tv.ui.components.focusGlow()),
                ) {
                    Row(Modifier.padding(8.dp)) {
                        Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(c.panel)) {
                            if (v.thumbnail != null) AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (blurUnwatched && !watched && Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier))
                            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(c.accent))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(title, style = Vyrk.type.titleS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (v.id in newIds) Tag(str(R.string.badge_new), color = c.onAccent, background = c.accent)
                                if (watched) Tag("✓ " + str(R.string.watched), color = c.background, background = c.success)
                            }
                            date?.let { Text(if (future) str(R.string.airs_on, it) else it, style = Vyrk.type.labelS, color = c.textMuted) }
                            val syn = v.synopsis
                            if (!syn.isNullOrBlank()) Text(if (blurUnwatched && !watched) str(R.string.hidden_synopsis) else syn, style = Vyrk.type.bodyS, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (menuFor == v.id) WrapRow(modifier = Modifier.padding(top = 6.dp), spacing = 6.dp) {
                    VyrkButton(str(if (watched) R.string.mark_unwatched else R.string.mark_watched), onClick = { onMark(listOf(v), !watched); menuFor = null })
                    VyrkButton(str(R.string.mark_previous_watched), onClick = { onMark(LibraryRules.videosUpTo(meta, v), true); menuFor = null })
                    VyrkButton(str(R.string.mark_season_unwatched), onClick = { onMark(eps, false); menuFor = null })
                }
            }
        }
    }
}
