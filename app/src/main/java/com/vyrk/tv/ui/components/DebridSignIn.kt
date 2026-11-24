package com.vyrk.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.vyrk.tv.R
import com.vyrk.tv.data.DebridAccount
import com.vyrk.tv.data.DebridAccount.SignIn
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.traceFocus
import com.vyrk.tv.ui.tryRequestFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A debrid sign-in tied to a screen: stops waiting when the screen goes. [typing] is the TorBox key being entered, or null. */
@Stable
class DebridSignInUi(private val account: DebridAccount, private val scope: CoroutineScope) {
    var step by mutableStateOf<SignIn?>(null)
        private set
    var typing by mutableStateOf<String?>(null)
    private var job: Job? = null
    val busy get() = step is SignIn.Code || step == SignIn.Checking || typing != null

    fun startRealDebrid() {
        cancel()
        job = scope.launch { account.signInRealDebrid { step = it } }
    }

    fun startTorBox() { cancel(); typing = "" }

    fun connectTorBox(key: String) {
        job?.cancel()
        job = scope.launch { account.connectTorBox(key) { s -> step = s; if (s == SignIn.Done) typing = null } }
    }

    fun cancel() { job?.cancel(); job = null; step = null; typing = null }
}

@Composable
fun rememberDebridSignIn(account: DebridAccount): DebridSignInUi {
    val scope = rememberCoroutineScope()
    val ui = remember(account) { DebridSignInUi(account, scope) }
    DisposableEffect(ui) { onDispose { ui.cancel() } }
    return ui
}

/** "Connected to Real-Debrid as name · Premium until 2027-01-31". */
@Composable
fun debridStatus(s: DebridAccount.State.Connected): String = listOfNotNull(
    s.name?.let { str(R.string.debrid_connected_as, s.service.label, it) } ?: str(R.string.debrid_connected, s.service.label),
    when (s.premium) {
        true -> s.until?.let { str(R.string.debrid_premium_until, it) }
        false -> str(R.string.debrid_no_premium)
        null -> null
    },
).joinToString(" · ")

/** The Real-Debrid code to enter at real-debrid.com/device (with a QR code that opens it on a phone), or how the sign-in ended. */
@Composable
fun DebridCodePanel(step: SignIn?, qr: Boolean, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    when (step) {
        is SignIn.Code -> Row(
            modifier.fillMaxWidth().background(c.shade.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .border(1.dp, c.gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qr) {
                androidx.compose.foundation.layout.Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
                    QrCode(step.code.qrUrl, 112.dp)
                }
                Spacer(Modifier.width(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(str(R.string.trakt_activate, step.code.verificationUrl.removePrefix("https://")), style = Vyrk.type.bodyM, color = c.textSecondary)
                Text(step.code.userCode, color = c.gold, fontSize = 34.sp, letterSpacing = 6.sp, style = Vyrk.type.titleL)
                Text(str(R.string.debrid_waiting), style = Vyrk.type.bodyS, color = c.textMuted)
            }
        }
        SignIn.Checking -> Text(str(R.string.debrid_key_checking), style = Vyrk.type.bodyM, color = c.textSecondary, modifier = modifier)
        SignIn.Expired -> Text(str(R.string.trakt_expired), style = Vyrk.type.bodyM, color = c.warning, modifier = modifier)
        SignIn.Failed -> Text(str(R.string.debrid_sign_in_failed), style = Vyrk.type.bodyM, color = c.warning, modifier = modifier)
        SignIn.BadKey -> Text(str(R.string.debrid_key_bad), style = Vyrk.type.bodyM, color = c.warning, modifier = modifier)
        else -> Unit
    }
}

/**
 * Entering a TorBox API key with the remote: TorBox keys are hexadecimal with dashes, so the
 * keypad has just those (a hardware keyboard types too). Focus starts on the first key.
 */
@Composable
fun TorBoxKeyEntry(ui: DebridSignInUi) {
    val c = Vyrk.colors
    val typing = ui.typing ?: return
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Column(
        Modifier.fillMaxWidth().background(c.panel.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
            .border(1.dp, c.gold.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(str(R.string.debrid_key), style = Vyrk.type.labelL, color = c.textMuted)
        Text(typing.ifEmpty { "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" }, style = Vyrk.type.titleM, color = if (typing.isEmpty()) c.textMuted else c.text, maxLines = 2)
        Text(str(R.string.debrid_key_hint), style = Vyrk.type.bodyS, color = c.textSecondary)
        "0123456789abcdef-".toList().chunked(9).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEachIndexed { i, ch ->
                    VyrkButton(ch.toString(), onClick = { ui.typing = typing + ch }, horizontalPadding = 0.dp,
                        modifier = Modifier.width(40.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier).traceFocus("debridkey:$ch"))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VyrkButton(str(R.string.key_backspace), onClick = { ui.typing = typing.dropLast(1) }, modifier = Modifier.traceFocus("debridkey:back"))
            VyrkButton(str(R.string.key_clear), onClick = { ui.typing = "" }, modifier = Modifier.traceFocus("debridkey:clear"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VyrkButton(str(R.string.debrid_connect), style = ButtonStyle.BRAND, onClick = { if (typing.isNotBlank()) ui.connectTorBox(typing) }, modifier = Modifier.traceFocus("debridkey:connect"))
            VyrkButton(str(R.string.cancel), onClick = { ui.cancel() }, modifier = Modifier.traceFocus("debridkey:cancel"))
        }
        DebridCodePanel(ui.step, qr = false)
    }
}

/** "⚡ TB" on a torrent source the debrid service has ready, "RD" when it can't say; null for other sources. */
@Composable
fun debridTag(r: com.vyrk.core.stream.Ranked): String? {
    val short = com.vyrk.tv.ui.LocalGraph.current.debrid.short ?: return null
    if (r.info.delivery != com.vyrk.core.stream.Delivery.TORRENT || r.candidate.stream.url != null) return null
    return if (r.info.cached == true) "⚡ $short" else short
}
