package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import indi.renakoni.nextvol.R

@Composable
internal fun SourceNetworkSection(state: SourceNetworkState, busy: Boolean, onBypassVpn: (Boolean) -> Unit,
    onRevokeCertificate: (String) -> Unit = {}) {
    val label = stringResource(R.string.sources_bypass_vpn)
    ListItem(headlineContent = { Text(label) },
        supportingContent = state.limitation?.let { limitation -> ({ Text(stringResource(limitation)) }) },
        trailingContent = {
            Switch(state.bypassVpn, onBypassVpn, enabled = !busy && (state.limitation == null || state.bypassVpn),
                modifier = Modifier.semantics { contentDescription = label })
        })
    if (state.certificates.isNotEmpty()) {
        Text(stringResource(R.string.source_certificate_exceptions))
        Text(stringResource(R.string.source_certificate_exceptions_help))
        state.certificates.forEach { site ->
            ListItem(headlineContent = { Text(site.origin) }, trailingContent = {
                TextButton(onClick = { onRevokeCertificate(site.origin) }, enabled = !busy) {
                    Text(stringResource(R.string.source_certificate_revoke))
                }
            })
        }
    }
}
