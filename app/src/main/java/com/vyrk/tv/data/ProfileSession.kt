package com.vyrk.tv.data

import com.vyrk.core.account.ApiError
import com.vyrk.core.account.StremioAccountApi
import com.vyrk.core.addon.AddonClient
import com.vyrk.core.addon.AddonResult
import com.vyrk.core.addon.AddonUrls
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.DescriptorFlags
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.addon.Redact
import com.vyrk.core.addon.Video
import com.vyrk.core.catalog.CatalogRules
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.library.LibrarySync
import com.vyrk.core.library.NewEpisodes
import com.vyrk.core.library.PendingChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import java.io.File

sealed interface AccountState {
    data object None : AccountState
    data class Connected(val email: String) : AccountState
    /** Session expired (API error 1): local data is kept and Reconnect is offered. */
    data class Expired(val email: String?) : AccountState
}

/**
 * One profile's add-ons, library, settings, navigation memory and sync, exposed as StateFlows.
 * Runs on its own scope so actions started from a screen finish after the screen closes.
 */
class ProfileSession(
    val profile: Profile,
    private val dir: File,
    private val vault: SecretVault,
    private val addonClient: AddonClient,
    private val accountApi: StremioAccountApi,
    private val sharedAddons: () -> List<Descriptor>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now get() = System.currentTimeMillis()

    private val _addons = MutableStateFlow<List<Descriptor>>(emptyList())
    val addons: StateFlow<List<Descriptor>> = _addons.asStateFlow()

    private val _library = MutableStateFlow<Map<String, LibraryItem>>(emptyMap())
    val library: StateFlow<Map<String, LibraryItem>> = _library.asStateFlow()

    private val _settings = MutableStateFlow(ProfileSettings())
    val settings: StateFlow<ProfileSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _account = MutableStateFlow<AccountState>(AccountState.None)
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val pending = PendingChanges()
    private val pushMutex = Mutex()
    private var pushJob: Job? = null
    private var authKey: String? = null

    private val addonsFile = File(dir, "addons.enc")
    private val authFile = File(dir, "auth.enc")
    private val libraryFile = File(dir, "library.json")
    private val settingsFile = File(dir, "settings.json")
    private val stateFile = File(dir, "state.json")

    /** Add-ons this profile uses, plus other profiles' shared stream add-ons when enabled. */
    fun effectiveAddons(): List<Descriptor> {
        val own = _addons.value.filterNot { CatalogRules.needsStreamingServer(it) }
        if (!_settings.value.useSharedAddons) return own
        val ids = own.map { it.manifest.id }.toSet()
        return own + sharedAddons().filter { it.manifest.id !in ids && it.manifest.hasResource("stream") }
    }

    suspend fun load() {
        _settings.value = AtomicFiles.readText(settingsFile)?.let { runCatching { AppJson.decodeFromString(ProfileSettings.serializer(), it) }.getOrNull() } ?: ProfileSettings()
        _state.value = AtomicFiles.readText(stateFile)?.let { runCatching { AppJson.decodeFromString(SessionState.serializer(), it) }.getOrNull() } ?: SessionState()
        _library.value = AtomicFiles.readText(libraryFile)?.let { txt ->
            runCatching { (ProtocolJson.parseToJsonElement(txt) as JsonArray).mapNotNull { el -> runCatching { LibraryItem.fromJson(el) }.getOrNull() } }.getOrNull()
        }?.associateBy { it.id } ?: emptyMap()
        pending.restore(_state.value.pendingIds)
        val storedAddons = vault.readSecret(addonsFile)?.let {
            runCatching { ProtocolJson.decodeFromString(ListSerializer(Descriptor.serializer()), it) }.getOrNull()
        }
        authKey = vault.readSecret(authFile)?.takeIf { it.isNotBlank() }
        _account.value = when {
            authKey != null -> AccountState.Connected(profile.accountEmail ?: "")
            profile.accountEmail != null -> AccountState.Expired(profile.accountEmail)
            else -> AccountState.None
        }
        if (storedAddons != null) _addons.value = storedAddons
        else if (authKey == null) installDefaults()
        _loaded.value = true
        if (authKey != null) scope.launch { syncNow() }
        scope.launch { refreshNewEpisodes(force = false) }
    }

    fun close() { scope.cancel() }

    // ---- Settings and session state ----

    fun updateSettings(transform: (ProfileSettings) -> ProfileSettings) {
        _settings.update(transform)
        val snapshot = _settings.value
        scope.launch { AtomicFiles.writeText(settingsFile, AppJson.encodeToString(ProfileSettings.serializer(), snapshot)) }
    }

    fun updateState(transform: (SessionState) -> SessionState) {
        _state.update(transform)
        val snapshot = _state.value
        scope.launch { AtomicFiles.writeText(stateFile, AppJson.encodeToString(SessionState.serializer(), snapshot)) }
    }

    fun rememberFocus(tab: String, memory: FocusMemory) = updateState { it.copy(focus = it.focus + (tab to memory)) }

    fun titleMemory(id: String): TitleMemory = _state.value.titleMemory[id] ?: TitleMemory()
    fun updateTitleMemory(id: String, f: (TitleMemory) -> TitleMemory) =
        updateState { s -> s.copy(titleMemory = s.titleMemory + (id to f(s.titleMemory[id] ?: TitleMemory()))) }

    // ---- Add-ons ----

    private suspend fun installDefaults() {
        val defaults = listOf(DEFAULT_CINEMETA, DEFAULT_OPENSUBTITLES)
        val list = mutableListOf<Descriptor>()
        for (url in defaults) {
            runCatching { addonClient.manifest(url) }.onSuccess { list += Descriptor(it, url, DescriptorFlags(official = true)) }
                .onFailure { Diagnostics.log("addons", "default add-on failed: ${it.message}") }
        }
        if (list.isNotEmpty()) setAddons(list, push = false)
    }

    suspend fun fetchManifest(input: String): Result<Descriptor> = try {
        val url = AddonUrls.normaliseInstallUrl(input) ?: error("not an add-on URL")
        Result.success(Descriptor(addonClient.manifest(url), url))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Diagnostics.log("addons", "manifest fetch failed: ${e.message}")
        Result.failure(e)
    }

    suspend fun install(d: Descriptor) {
        val list = _addons.value.filterNot { it.manifest.id == d.manifest.id } + d
        setAddons(list, push = true)
        Diagnostics.log("addons", "installed ${d.manifest.id} from ${Redact.url(d.transportUrl)}")
    }

    suspend fun remove(id: String) = setAddons(_addons.value.filterNot { it.manifest.id == id }, push = true)

    suspend fun move(id: String, delta: Int) {
        val list = _addons.value.toMutableList()
        val i = list.indexOfFirst { it.manifest.id == id }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        setAddons(list, push = true)
    }

    suspend fun refreshAddon(id: String): Boolean {
        val d = _addons.value.firstOrNull { it.manifest.id == id } ?: return false
        val m = runCatching { addonClient.manifest(d.transportUrl) }.getOrNull() ?: return false
        setAddons(_addons.value.map { if (it.manifest.id == id) it.copy(manifest = m) else it }, push = true)
        return true
    }

    private suspend fun setAddons(list: List<Descriptor>, push: Boolean) {
        _addons.value = list
        vault.writeSecret(addonsFile, ProtocolJson.encodeToString(ListSerializer(Descriptor.serializer()), list))
        if (push) saveAddonsToAccount()
    }

    /** Saves the add-on list back to the account; returns false without an account or on error. */
    suspend fun saveAddonsToAccount(): Boolean {
        val key = authKey ?: return false
        return try {
            accountApi.addonCollectionSet(key, _addons.value); true
        } catch (e: ApiError) {
            if (e.isSessionExpired) onExpired(); false
        } catch (e: Exception) {
            Diagnostics.log("account", "addonCollectionSet failed: ${e.message}"); false
        }
    }

    // ---- Account ----

    suspend fun connectAccount(key: String, onProfileEmail: suspend (String) -> Unit) {
        val user = accountApi.getUser(key)
        authKey = key
        vault.writeSecret(authFile, key)
        onProfileEmail(user.email)
        _account.value = AccountState.Connected(user.email)
        val remote = runCatching { accountApi.addonCollectionGet(key) }.getOrNull()
        if (!remote.isNullOrEmpty()) setAddons(remote, push = false)
        syncNow()
    }

    suspend fun logout() {
        authKey?.let { k -> runCatching { accountApi.logout(k) } }
        authKey = null
        vault.writeSecret(authFile, "")
        _account.value = AccountState.None
    }

    private fun onExpired() {
        authKey = null
        scope.launch { vault.writeSecret(authFile, "") }
        _account.value = AccountState.Expired(profile.accountEmail)
        Diagnostics.log("account", "session expired")
    }

    /** Two-way library sync using datastoreMeta → pull newer remote, push newer local. */
    suspend fun syncNow() {
        val key = authKey ?: return
        if (_syncing.value) return
        _syncing.value = true
        try {
            runCatching { accountApi.addonCollectionGet(key) }.onSuccess { if (it.isNotEmpty()) setAddons(it, push = false) }
            val meta = accountApi.datastoreMeta(key)
            val plan = LibrarySync.plan(_library.value, meta, now)
            if (plan.pull.isNotEmpty()) {
                val items = plan.pull.chunked(200).flatMap { accountApi.datastoreGet(key, it) }
                _library.value = LibraryRules.merge(_library.value, items)
                saveLibrary()
            }
            plan.push.forEach { pending.mark(it) }
            pushPending()
        } catch (e: ApiError) {
            if (e.isSessionExpired) onExpired()
        } catch (e: Exception) {
            Diagnostics.log("sync", "sync failed: ${e.message}")
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun pushPending() = pushMutex.withLock {
        val key = authKey ?: return@withLock
        val sent = pending.snapshot()
        if (sent.isEmpty()) return@withLock
        val items = sent.keys.mapNotNull { _library.value[it] }
        try {
            accountApi.datastorePut(key, items)
            pending.confirm(sent)
        } catch (e: ApiError) {
            if (e.isSessionExpired) onExpired()
        } catch (e: Exception) {
            Diagnostics.log("sync", "push failed: ${e.message}")
        }
        updateState { it.copy(pendingIds = pending.ids()) }
    }

    /** Pushes in the background soon (coalesced). */
    fun schedulePush(delayMs: Long = 1_500) {
        if (authKey == null) return
        pushJob?.cancel()
        pushJob = scope.launch { delay(delayMs); pushPending() }
    }

    fun pushNow() { scope.launch { pushPending() } }

    // ---- Library ----

    private suspend fun saveLibrary() {
        val arr = JsonArray(_library.value.values.map { it.toJson() })
        AtomicFiles.writeText(libraryFile, arr.toString())
    }

    /** Every local change: stamp mtime, update the flow at once, save, mark pending, push. */
    fun putItem(item: LibraryItem, pushSoon: Boolean = true) {
        val stamped = LibraryRules.touch(item, now)
        _library.update { it + (stamped.id to stamped) }
        pending.mark(stamped.id)
        scope.launch {
            saveLibrary()
            updateState { it.copy(pendingIds = pending.ids()) }
            if (pushSoon) schedulePush()
        }
    }

    fun item(id: String): LibraryItem? = _library.value[id]

    fun itemFor(meta: MetaPreview): LibraryItem = _library.value[meta.id] ?: LibraryItem.fromMeta(meta, now)

    fun addToLibrary(meta: MetaPreview) {
        putItem(LibraryRules.addToLibrary(itemFor(meta), now))
        if (meta.type == "series") scope.launch { refreshNewEpisodes(force = true) }
    }

    fun removeFromLibrary(id: String) { item(id)?.let { putItem(LibraryRules.removeFromLibrary(it)) } }

    /** Remove from Continue Watching (RewindLibraryItem): updates the flow synchronously. */
    fun removeFromContinueWatching(id: String) {
        val it = item(id) ?: return
        putItem(LibraryRules.rewind(it))
        updateState { s -> s.copy(hiddenContinueWatching = s.hiddenContinueWatching - id) }
    }

    fun restoreHidden(id: String) = updateState { s -> s.copy(hiddenContinueWatching = s.hiddenContinueWatching - id) }

    fun markWatched(meta: MetaPreview, watched: Boolean) = putItem(LibraryRules.markWatched(itemFor(meta), watched, now))

    fun markVideos(meta: MetaItem, videos: List<Video>, watched: Boolean) {
        val base = itemFor(meta.toPreview())
        putItem(LibraryRules.markVideosWatched(base, meta, videos, watched))
    }

    fun toggleNotifications(id: String) { item(id)?.let { putItem(it.copy(state = it.state.copy(noNotif = !it.state.noNotif))) } }

    // ---- New episodes ----

    suspend fun refreshNewEpisodes(force: Boolean) {
        val st = _state.value
        if (!NewEpisodes.shouldRequest(st.newEpisodesCheckedMs, now, force)) return
        val eligible = NewEpisodes.eligible(_library.value.values)
        if (eligible.isEmpty()) return
        val result = mutableMapOf<String, Set<String>>()
        for (req in NewEpisodes.requests(effectiveAddons(), eligible)) {
            val r = addonClient.catalog(req.addon, req.type, req.catalogId, listOf(NewEpisodes.EXTRA to req.ids))
            if (r !is AddonResult.Ok) continue
            for (preview in r.value.metas) {
                val item = _library.value[preview.id] ?: continue
                val meta = addonClient.firstMeta(effectiveAddons(), preview.type, preview.id) ?: continue
                val ids = NewEpisodes.newVideoIds(item, meta, now)
                if (ids.isNotEmpty()) result[preview.id] = ids
            }
        }
        updateState { it.copy(newEpisodes = result, newEpisodesCheckedMs = now) }
    }

    fun newEpisodeIds(itemId: String): Set<String> = _state.value.newEpisodes[itemId].orEmpty()

    companion object {
        const val DEFAULT_CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
        const val DEFAULT_OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"
    }
}
