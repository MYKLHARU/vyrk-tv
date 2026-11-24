package com.vyrk.tv.data

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.addon.Stream
import com.vyrk.core.debrid.DebridDeviceCode
import com.vyrk.core.debrid.DebridLogin
import com.vyrk.core.debrid.DebridResult
import com.vyrk.core.debrid.DebridService
import com.vyrk.core.debrid.DevicePoll
import com.vyrk.core.debrid.FileHint
import com.vyrk.core.debrid.Magnet
import com.vyrk.core.debrid.RealDebridClient
import com.vyrk.core.debrid.TorBoxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The device's debrid service: Real-Debrid (device sign-in) or TorBox (API key). One at a time.
 * The sign-in lives encrypted in [file]; VYRK talks to the service only when a torrent source is
 * played or listed, and never sends it anything else.
 */
class DebridAccount(
    private val rd: RealDebridClient,
    private val tb: TorBoxClient,
    private val vault: SecretVault,
    private val file: File,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object SignedOut : State
        data class Connected(val service: DebridService, val name: String?, val premium: Boolean? = null, val until: String? = null) : State
    }

    sealed interface SignIn {
        data class Code(val code: DebridDeviceCode) : SignIn
        data object Checking : SignIn
        data object Done : SignIn
        data object Expired : SignIn
        data object Failed : SignIn
        /** TorBox didn't accept the API key. */
        data object BadKey : SignIn
    }

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var login: DebridLogin? = null
    private val lock = Mutex()

    val connected: Boolean get() = login != null
    /** "RD" or "TB" while connected. */
    val short: String? get() = login?.service?.short
    val service: DebridService? get() = login?.service

    init {
        scope.launch {
            val l = runCatching { vault.readSecret(file)?.let { ProtocolJson.decodeFromString(DebridLogin.serializer(), it) } }.getOrNull()
            if (l != null) { login = l; _state.value = State.Connected(l.service, l.username); refreshAccount() }
        }
    }

    private suspend fun save(l: DebridLogin?) {
        login = l
        if (l == null) { file.delete(); _state.value = State.SignedOut }
        else {
            vault.writeSecret(file, ProtocolJson.encodeToString(DebridLogin.serializer(), l))
            val prev = _state.value as? State.Connected
            _state.value = State.Connected(l.service, l.username, prev?.premium, prev?.until)
        }
    }

    /** Premium status for Settings; signs out when the service rejects the sign-in. */
    fun refreshAccount() {
        scope.launch {
            val l = login ?: return@launch
            val token = token() ?: return@launch
            val (code, user) = if (l.service == DebridService.REAL_DEBRID) rd.user(token) else tb.user(token)
            if (code == 401) { Diagnostics.log("debrid", "${l.service.short} rejected the sign-in"); lock.withLock { save(null) }; return@launch }
            if (user != null) {
                lock.withLock { login?.let { if (it.username != user.name) save(it.copy(username = user.name)) } }
                _state.value = State.Connected(l.service, user.name, user.premium, user.premiumUntil)
            }
        }
    }

    /** The access token, refreshed when a Real-Debrid one ends within five minutes. */
    private suspend fun token(): String? = lock.withLock {
        val l = login ?: return null
        if (l.service != DebridService.REAL_DEBRID) return l.token
        val now = System.currentTimeMillis()
        if (l.expiresAtMs - now > 5 * 60_000L) return l.token
        val fresh = rd.refresh(l, now)
        if (fresh != null) { save(fresh); fresh.token } else l.token.takeIf { l.expiresAtMs > now }
    }

    /** Real-Debrid's device sign-in: reports the code to show, then the outcome. Cancel to stop waiting. */
    suspend fun signInRealDebrid(report: (SignIn) -> Unit) {
        val code = rd.deviceCode() ?: return report(SignIn.Failed)
        report(SignIn.Code(code))
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1_000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.intervalSec * 1_000L)
            when (val r = rd.poll(code, System.currentTimeMillis())) {
                is DevicePoll.Approved -> {
                    val name = rd.user(r.login.token).second?.name
                    lock.withLock { save(r.login.copy(username = name)) }
                    refreshAccount()
                    return report(SignIn.Done)
                }
                DevicePoll.Pending -> Unit
                DevicePoll.Failed -> return report(SignIn.Failed)
            }
        }
        report(SignIn.Expired)
    }

    /** Checks a TorBox API key and keeps it when TorBox accepts it. */
    suspend fun connectTorBox(key: String, report: (SignIn) -> Unit) {
        val k = key.trim()
        report(SignIn.Checking)
        val (code, user) = tb.user(k)
        if (user == null) return report(if (code == 401) SignIn.BadKey else SignIn.Failed)
        lock.withLock { save(DebridLogin(DebridService.TORBOX, k, username = user.name)) }
        _state.value = State.Connected(DebridService.TORBOX, user.name, user.premium, user.premiumUntil)
        report(SignIn.Done)
    }

    fun disconnect() { scope.launch { lock.withLock { save(null) } } }

    /** Which of these torrents the service has ready; null when it can't say (Real-Debrid) or the check fails. */
    suspend fun cached(hashes: Collection<String>): Set<String>? {
        val l = login ?: return null
        if (l.service != DebridService.TORBOX || hashes.isEmpty()) return null
        return tb.cached(l.token, hashes)
    }

    /** A direct link for a torrent [stream], for the video [videoId]. */
    suspend fun resolve(stream: Stream, videoId: String?): DebridResult {
        val l = login ?: return DebridResult.SignedOut
        val hash = stream.infoHash ?: return DebridResult.NoFile
        val token = token() ?: return DebridResult.SignedOut
        val magnet = Magnet.of(hash, stream.sources, stream.behaviorHints.filename)
        val hint = FileHint.of(stream, videoId)
        val result = runCatching {
            if (l.service == DebridService.REAL_DEBRID) rd.resolve(token, hash, magnet, hint) else tb.resolve(token, hash, magnet, hint)
        }.getOrElse { DebridResult.Failed(it.javaClass.simpleName) }
        // Logged without hashes, links or tokens.
        Diagnostics.log("debrid", "${l.service.short} -> ${result.javaClass.simpleName}${(result as? DebridResult.Failed)?.let { " ${it.reason}" }.orEmpty()}")
        if (result == DebridResult.SignedOut) lock.withLock { save(null) }
        return result
    }
}
