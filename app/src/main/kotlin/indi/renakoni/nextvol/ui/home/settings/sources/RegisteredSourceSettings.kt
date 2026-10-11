package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceListing
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.ui.components.SectionHeader

/** Registered sources expose metadata and supported actions without loading their runtime. */
@Composable
internal fun RegisteredSourceSettings(entry: SourceListing?, network: SourceNetworkState?, busy: Boolean,
    onSearch: () -> Unit, onBypassVpn: (Boolean) -> Unit) {
    var advanced by rememberSaveable(entry?.metadata?.id?.toString()) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(text = stringResource(R.string.sources_basic_section))
        if (entry == null || entry.status == SourceStatus.Failed) Text(stringResource(R.string.sources_unavailable))
        entry?.let {
            Text(it.metadata.item.provider)
            if (SourceCapability.Search in it.actionCapabilities()) Button(onClick = onSearch, enabled = !busy) {
                Text(stringResource(R.string.explore_search))
            }
        }
        network?.let {
            SectionHeader(text = stringResource(R.string.sources_network_section))
            SourceNetworkSection(it, busy, onBypassVpn)
        }
        if (entry != null) {
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.sources_advanced)) }
            if (advanced) {
                Text(entry.metadata.id.toString())
            }
        }
    }
}
