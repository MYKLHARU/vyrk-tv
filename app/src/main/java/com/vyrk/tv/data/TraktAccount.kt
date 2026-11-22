package com.vyrk.tv.data

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.trakt.DeviceCode
import com.vyrk.core.trakt.TokenPoll
import com.vyrk.core.trakt.TraktClient
import com.vyrk.core.trakt.TraktItem
import com.vyrk.core.trakt.TraktTokens
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
 * The device's Trakt connection. Tokens live encrypted in [file]; nothing is sent to Trakt until
 * the user connects. Without the build's Trakt keys the state stays [State.Off] and nothing shows.
 */
class TraktAccount(private val client: TraktClient, private val vault: SecretVault, private val file: File, private val scope: CoroutineScope) {
    sealed interface State {
        data object Off : State
        data object SignedOut : State
        data class Connected(val username: String?) : State
    }

    sealed interface SignIn {
        data class Code(val code: DeviceCode) : SignIn
        data object Done : SignIn
        data object Expired : SignIn
        data object Failed : SignIn
        /** Approved with a Trakt account this build's Trakt app doesn't allow. */
        data object NotAllowed : SignIn
    }

    private val _state = MutableStateFlow<State>(if (client.enabled) State.SignedOut else State.Off)
    val state: StateFlow<State> = _state.asStateFlow()
    private var tokens: TraktTokens? = null
    private val lock = Mutex()

    init {
        if (client.enabled) scope.launch {
            val t = runCatching { vault.readSecret(file)?.let { ProtocolJson.decodeFromString(TraktTokens.serializer(), it) } }.getOrNull()
            if (t != null) { tokens = t; _state.value = State.Connected(t.username) }
        }
    }

    private suspend fun save(t: TraktTokens?) {
        tokens = t
        if (t == null) { file.delete(); _state.value = State.SignedOut }
        else { vault.writeSecret(file, ProtocolJson.encodeToString(TraktTokens.serializer(), t)); _state.value = State.Connected(t.username) }
    }

    /** A fresh access token, refreshed when it ends within a day; null when signed out. */
    private suspend fun token(): String? = lock.withLock {
        val t = tokens ?: return null
        val now = System.currentTimeMillis()
        if (t.expiresAtMs - now > DAY_MS) return t.accessToken
        val fresh = client.refresh(t, now)
        if (fresh != null) { save(fresh); fresh.accessToken }
        else if (t.expiresAtMs > now) t.accessToken
        else { Diagnostics.log("trakt", "refresh failed; signed out"); save(null); null }
    }

    /** Runs the device sign-in, reporting the code to show and then the outcome. Cancel to stop waiting. */
    suspend fun signIn(report: (SignIn) -> Unit) {
        val code = client.deviceCode() ?: return report(SignIn.Failed)
        report(SignIn.Code(code))
        var interval = code.intervalSec * 1_000L
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1_000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            when (val r = client.pollToken(code, System.currentTimeMillis())) {
                is TokenPoll.Approved -> {
                    val name = client.username(r.tokens.accessToken)
                    lock.withLock { save(r.tokens.copy(username = name)) }
                    return report(SignIn.Done)
                }
                TokenPoll.Pending -> Unit
                TokenPoll.SlowDown -> interval += 1_000L
                TokenPoll.Expired -> return report(SignIn.Expired)
                TokenPoll.NotAllowed -> return report(SignIn.NotAllowed)
                TokenPoll.Denied, is TokenPoll.Failed -> return report(SignIn.Failed)
            }
        }
        report(SignIn.Expired)
    }

    fun disconnect() {
        scope.launch {
            val t = lock.withLock { tokens.also { save(null) } }
            t?.let { runCatching { client.revoke(it.accessToken) } }
        }
    }

    /** Fire-and-forget scrobble; a 401 means Trakt dropped the sign-in. */
    fun scrobble(action: String, item: TraktItem, progressPercent: Float) {
        if (tokens == null) return
        scope.launch {
            val token = token() ?: return@launch
            val code = client.scrobble(action, item, progressPercent, token)
            if (code == 401) lock.withLock { save(null) }
            else if (code !in 200..299 && code != 409) Diagnostics.log("trakt", "scrobble $action -> $code")
        }
    }

    fun addToHistory(items: List<TraktItem>) {
        if (tokens == null || items.isEmpty()) return
        scope.launch { token()?.let { client.addToHistory(items, it) } }
    }

    private companion object { const val DAY_MS = 86_400_000L }
}

/** Follows one player: start while playing, pause when paused, stop when it moves on or closes (80%+ marks watched on Trakt). */
class TraktScrobbler(private val account: TraktAccount) {
    private var active: TraktItem? = null
    private var lastProgress = 0f

    fun onPlaying(item: TraktItem?, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        if (account.state.value !is TraktAccount.State.Connected) return
        val progress = if (durationMs > 0) positionMs * 100f / durationMs else 0f
        val prev = active
        if (prev != null && prev != item) { account.scrobble("stop", prev, lastProgress); active = null }
        if (item == null || durationMs <= 0) return
        lastProgress = progress
        if (isPlaying) { account.scrobble("start", item, progress); active = item }
        else if (active == item) account.scrobble("pause", item, progress)
    }

    fun close(positionMs: Long, durationMs: Long) {
        val a = active ?: return
        val progress = if (durationMs > 0) positionMs * 100f / durationMs else lastProgress
        account.scrobble("stop", a, maxOf(progress, 0f))
        active = null
    }
}
