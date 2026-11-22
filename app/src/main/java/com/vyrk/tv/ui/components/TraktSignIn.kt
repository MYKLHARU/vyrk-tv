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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.vyrk.tv.R
import com.vyrk.tv.data.TraktAccount
import com.vyrk.tv.ui.str
import com.vyrk.tv.ui.theme.Vyrk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A Trakt device sign-in tied to a screen: stops waiting when the screen goes. */
@Stable
class TraktSignInUi(private val account: TraktAccount, private val scope: CoroutineScope) {
    var step by mutableStateOf<TraktAccount.SignIn?>(null)
        private set
    private var job: Job? = null
    val waiting get() = step is TraktAccount.SignIn.Code

    fun start() {
        job?.cancel()
        step = null
        job = scope.launch { account.signIn { step = it } }
    }

    fun cancel() { job?.cancel(); job = null; step = null }
}

@Composable
fun rememberTraktSignIn(account: TraktAccount): TraktSignInUi {
    val scope = rememberCoroutineScope()
    val ui = remember(account) { TraktSignInUi(account, scope) }
    DisposableEffect(ui) { onDispose { ui.cancel() } }
    return ui
}

/** The code to enter at trakt.tv/activate, with a QR code that opens the page on a phone. */
@Composable
fun TraktCodePanel(step: TraktAccount.SignIn?, qr: Boolean, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    when (step) {
        is TraktAccount.SignIn.Code -> Row(
            modifier.fillMaxWidth().background(c.shade.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .border(1.dp, c.gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qr) {
                androidx.compose.foundation.layout.Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
                    QrCode(step.code.verificationUrl, 112.dp)
                }
                Spacer(Modifier.width(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(str(R.string.trakt_activate, step.code.verificationUrl.removePrefix("https://")), style = Vyrk.type.bodyM, color = c.textSecondary)
                Text(step.code.userCode, color = c.gold, fontSize = 34.sp, letterSpacing = 6.sp, style = Vyrk.type.titleL)
                Text(str(R.string.trakt_waiting), style = Vyrk.type.bodyS, color = c.textMuted)
            }
        }
        TraktAccount.SignIn.Expired -> Text(str(R.string.trakt_expired), style = Vyrk.type.bodyM, color = c.warning, modifier = modifier)
        TraktAccount.SignIn.Failed -> Text(str(R.string.trakt_failed), style = Vyrk.type.bodyM, color = c.warning, modifier = modifier)
        TraktAccount.SignIn.NotAllowed -> Text(str(R.string.trakt_not_allowed), style = Vyrk.type.bodyM, color = c.warning, modifier = modifier)
        else -> Unit
    }
}
