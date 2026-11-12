package com.vyrk.tv.data

import android.content.Context
import com.vyrk.core.account.StremioAccountApi
import com.vyrk.core.addon.AddonClient
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.profile.PinHasher
import com.vyrk.core.profile.PinLockout
import com.vyrk.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/** App-wide singletons. Created once in [com.vyrk.tv.VyrkApp]. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        Diagnostics.log("app", "background task failed: ${e.javaClass.simpleName}")
    })
    val http: OkHttpClient = OkHttpTransport.client("VYRK/${BuildConfig.VERSION_NAME} (Android TV)")
    val transport = OkHttpTransport(http)
    val addonClient = AddonClient(
        transport, maxConcurrent = 8,
        onDecoded = { resource, ms, size, thread -> com.vyrk.tv.ui.Perf.log("decode $resource ${ms}ms ${size / 1024}KB on $thread") },
        // Reason is already redacted (no URLs); the add-on id is its public manifest id.
        onFailed = { resource, addonId, reason, ms -> com.vyrk.tv.ui.Perf.log("addon $resource failed after ${ms}ms: $reason [$addonId]") },
    )
    val accountApi = StremioAccountApi(transport)
    val vault = SecretVault()
    val device = DeviceCapabilities(context)
    val metaCache = com.vyrk.tv.ui.browse.MetaCache(addonClient, { session.value?.effectiveAddons().orEmpty() })
    // Failures are logged with the API path and reason only; the key never appears.
    val tmdb = com.vyrk.core.tmdb.TmdbClient(transport, BuildConfig.TMDB_API_KEY) { path, reason -> com.vyrk.tv.ui.Perf.log("tmdb $path failed: $reason") }
    val fanart = com.vyrk.core.art.FanartClient(transport, BuildConfig.FANART_API_KEY)
    /** Hand-off from the UI to PlayerActivity (same process). */
    @Volatile var pendingPlayback: com.vyrk.tv.player.PlaybackRequest? = null

    private val root = File(context.filesDir, "vyrk")
    private val profilesFile = File(root, "profiles.json")
    private val deviceFile = File(root, "device.json")

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _deviceSettings = MutableStateFlow(DeviceSettings())
    val deviceSettings: StateFlow<DeviceSettings> = _deviceSettings.asStateFlow()

    private val _session = MutableStateFlow<ProfileSession?>(null)
    val session: StateFlow<ProfileSession?> = _session.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Sessions kept for profiles whose stream add-ons are shared. */
    private val sharedCache = mutableMapOf<String, List<Descriptor>>()

    val watchNext = WatchNext(context)
    private var watchNextJob: kotlinx.coroutines.Job? = null

    /** Watch Next follows the active session's Continue Watching. */
    private fun bindWatchNext(s: ProfileSession) {
        watchNextJob?.cancel()
        watchNextJob = scope.launch {
            watchNext.clear()
            // Republish only when the row's content changes (to the minute), two seconds after the
            // last change: progress is saved every second during playback.
            s.library
                .distinctUntilChangedBy { lib -> com.vyrk.core.library.LibraryRules.watchNextDigest(lib.values) }
                .collectLatest { lib -> kotlinx.coroutines.delay(2_000); watchNext.publish(lib.values) }
        }
    }

    suspend fun load() {
        AtomicFiles.flush()
        _profiles.value = AtomicFiles.readText(profilesFile)?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull()
        } ?: emptyList()
        _deviceSettings.value = AtomicFiles.readText(deviceFile)?.let {
            runCatching { AppJson.decodeFromString(DeviceSettings.serializer(), it) }.getOrNull()
        } ?: DeviceSettings()
        loadSharedAddons()
        _ready.value = true
    }

    @Synchronized
    fun updateDevice(f: (DeviceSettings) -> DeviceSettings) {
        _deviceSettings.update(f)
        val s = _deviceSettings.value
        AtomicFiles.writeTextLater(deviceFile) { AppJson.encodeToString(DeviceSettings.serializer(), s) }
    }

    @Synchronized
    private fun saveProfiles() {
        val list = _profiles.value
        AtomicFiles.writeTextLater(profilesFile) { AppJson.encodeToString(ListSerializer(Profile.serializer()), list) }
    }

    fun profileDir(id: String) = File(root, "p/$id")

    @Synchronized
    fun createProfile(name: String, colorIndex: Int): Profile? {
        if (_profiles.value.size >= MAX_PROFILES) return null
        val p = Profile(UUID.randomUUID().toString(), name.trim().ifEmpty { "Viewer" }, colorIndex, createdMs = System.currentTimeMillis())
        _profiles.update { it + p }
        saveProfiles()
        return p
    }

    @Synchronized
    fun updateProfile(id: String, f: (Profile) -> Profile) {
        _profiles.update { list -> list.map { if (it.id == id) f(it) else it } }
        saveProfiles()
    }

    @Synchronized
    fun deleteProfile(id: String) {
        val closing = _session.value?.takeIf { it.profile.id == id }
        if (closing != null) { closing.close(); _session.value = null }
        _profiles.update { it.filterNot { p -> p.id == id } }
        saveProfiles()
        scope.launch(Dispatchers.IO) {
            closing?.scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.join()
            AtomicFiles.flush()
            profileDir(id).deleteRecursively()
        }
    }

    fun setPin(id: String, pin: String?) = updateProfile(id) { it.copy(pinHash = pin?.let { p -> PinHasher.hash(p) }, pinFailures = 0, pinLockedUntilMs = 0) }

    sealed interface PinResult { data object Ok : PinResult; data object Wrong : PinResult; data class Locked(val ms: Long) : PinResult }

    fun checkPin(id: String, pin: String): PinResult {
        val p = _profiles.value.firstOrNull { it.id == id } ?: return PinResult.Wrong
        val hash = p.pinHash ?: return PinResult.Ok
        val now = System.currentTimeMillis()
        val lock = PinLockout(p.pinFailures, p.pinLockedUntilMs)
        if (lock.isLocked(now)) return PinResult.Locked(lock.remainingMs(now))
        return if (PinHasher.verify(pin, hash)) {
            updateProfile(id) { it.copy(pinFailures = 0, pinLockedUntilMs = 0) }; PinResult.Ok
        } else {
            val next = lock.onFailure(now)
            updateProfile(id) { it.copy(pinFailures = next.failures, pinLockedUntilMs = next.lockedUntilMs) }
            if (next.isLocked(now)) PinResult.Locked(next.remainingMs(now)) else PinResult.Wrong
        }
    }

    /** Opens a profile (after any PIN check) and makes it the active session. */
    suspend fun openProfile(p: Profile): ProfileSession {
        _session.value?.let { if (it.profile.id == p.id) return it; it.close() }
        val s = ProfileSession(p, profileDir(p.id), vault, addonClient, accountApi, ::sharedAddonsFor)
        s.load()
        _session.value = s
        bindWatchNext(s)
        updateDevice { it.copy(lastProfileId = p.id) }
        return s
    }

    fun closeSession() {
        _session.value?.close(); _session.value = null
        watchNextJob?.cancel(); scope.launch { watchNext.clear() }
    }

    fun refreshShared(profileId: String, addons: List<Descriptor>, sharing: Boolean) {
        if (sharing) sharedCache[profileId] = addons.filter { it.manifest.hasResource("stream") } else sharedCache.remove(profileId)
    }

    private fun sharedAddonsFor(): List<Descriptor> {
        val me = _session.value?.profile?.id
        return sharedCache.filterKeys { it != me }.values.flatten()
    }

    private suspend fun loadSharedAddons() {
        for (p in _profiles.value) {
            val settings = AtomicFiles.readText(File(profileDir(p.id), "settings.json"))?.let {
                runCatching { AppJson.decodeFromString(ProfileSettings.serializer(), it) }.getOrNull()
            } ?: continue
            if (!settings.shareStreamAddons) continue
            val addons = vault.readSecret(File(profileDir(p.id), "addons.enc"))?.let {
                runCatching { com.vyrk.core.addon.ProtocolJson.decodeFromString(ListSerializer(Descriptor.serializer()), it) }.getOrNull()
            } ?: continue
            refreshShared(p.id, addons, true)
        }
    }

    companion object {
        const val MAX_PROFILES = 5
    }
}
