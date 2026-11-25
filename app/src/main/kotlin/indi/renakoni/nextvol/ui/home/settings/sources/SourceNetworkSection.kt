package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

@Composable
internal fun SourceNetworkSection(
    state: SourceNetworkState,
    busy: Boolean,
    onBypassVpn: (Boolean) -> Unit,
    onRevokeCertificate: (String) -> Unit = {},
) {
    val label = stringResource(R.string.sources_bypass_vpn)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W500,
            )
            state.limitation?.let { limitation ->
                Text(
                    text = stringResource(limitation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MiuixSwitch(
            checked = state.bypassVpn,
            onCheckedChange = onBypassVpn,
            enabled = !busy && (state.limitation == null || state.bypassVpn),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }

    if (state.certificates.isNotEmpty()) {
        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.source_certificate_exceptions),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.W600,
            )
            Text(
                text = stringResource(R.string.source_certificate_exceptions_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.certificates.forEach { site ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = site.origin,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { onRevokeCertificate(site.origin) },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.source_certificate_revoke))
                    }
                }
            }
        }
    }
}
