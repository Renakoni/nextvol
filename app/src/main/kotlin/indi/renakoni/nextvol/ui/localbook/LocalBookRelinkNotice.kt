package indi.renakoni.nextvol.ui.localbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.localbook.LocalBookRelinkMatch

@Composable
fun LocalBookMissingFile(onRelink: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.local_file_missing_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.local_file_missing_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRelink) { Text(stringResource(R.string.local_file_relink_action)) }
        }
    }
}

@Composable
internal fun LocalBookRelinkNotice(state: LocalBookRelinkState, modifier: Modifier = Modifier) {
    val match = state.preview?.match
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.local_file_relink_preserves), style = MaterialTheme.typography.bodyMedium)
            if (!state.busy && match != null) {
                Text(stringResource(when (match) {
                    LocalBookRelinkMatch.Exact -> R.string.local_file_match_exact
                    LocalBookRelinkMatch.DifferentFile -> R.string.local_file_match_different_file
                    LocalBookRelinkMatch.DifferentMapping -> R.string.local_file_match_different_mapping
                    LocalBookRelinkMatch.MissingManifest -> R.string.local_file_match_missing_manifest
                }), style = MaterialTheme.typography.bodyMedium,
                    color = if (state.preview.canRelink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}
