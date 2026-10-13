package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.rules.LoginStatus
import indi.renakoni.nextvol.data.web.rules.VerificationPrompt
import hnovel.network.BrowserChallengeKind

private const val MAX_DISPLAY_ACCOUNT_NAME = 16

private fun displayAccountName(value: String): String =
    if (value.length <= MAX_DISPLAY_ACCOUNT_NAME) value
    else value.take(MAX_DISPLAY_ACCOUNT_NAME - 1) + "…"

/** Presents the saved status; neither cookies nor account labels establish authentication. */
@Composable
internal fun SourceAccountSection(status: LoginStatus?, accountName: String?, available: Boolean, loginAvailable: Boolean, busy: Boolean,
    verification: VerificationPrompt?, onLogin: () -> Unit, onRelogin: () -> Unit, onLogout: () -> Unit, onRetry: () -> Unit,
    onVerify: () -> Unit) {
    val hasSession = status == LoginStatus.LoginSubmitted || status == LoginStatus.SessionSaved
    val working = busy || verification?.opening == true
    val title = when {
        verification?.certificate != null -> R.string.source_certificate_title
        verification?.kind == BrowserChallengeKind.Login -> R.string.sources_login_required
        verification != null -> R.string.sources_verification_required
        else -> when (status) {
            LoginStatus.LoginSubmitted -> R.string.sources_login_submitted
            LoginStatus.SessionSaved -> R.string.sources_session_saved
            LoginStatus.Required -> R.string.sources_account_required
            LoginStatus.LoggedOut -> R.string.sources_logged_out
            null -> if (busy) R.string.sources_account_loading else R.string.sources_account_unknown
        }
    }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).widthIn(min = 128.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (hasSession && accountName != null) Text(stringResource(R.string.sources_account_name, displayAccountName(accountName)),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (verification == null && status != null && !available) {
                Text(stringResource(R.string.sources_account_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (verification != null) {
                FilledTonalButton(onClick = onVerify, enabled = !working) {
                    Text(stringResource(when {
                        verification.certificate != null -> R.string.source_certificate_review
                        verification.kind == BrowserChallengeKind.Login -> R.string.sources_login_continue
                        else -> R.string.source_verification_background_open
                    }))
                }
            } else if (status == null) {
                FilledTonalButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.discovery_retry)) }
            } else if (available && loginAvailable) {
                val relogin = hasSession || status == LoginStatus.Required
                FilledTonalButton(onClick = if (relogin) onRelogin else onLogin, enabled = !busy) {
                    Text(stringResource(if (relogin) R.string.sources_login_again else R.string.sources_login))
                }
            }
            if (hasSession && available) {
                TextButton(onClick = onLogout, enabled = !working,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(stringResource(R.string.sources_logout))
                }
            }
        }
    }
}
