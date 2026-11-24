package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard

@Composable
fun StorageOverviewContent(
    modifier: Modifier,
    uiState: StorageManagerUiState,
    clearReadingCache: suspend () -> Boolean,
    clearDownloads: suspend () -> Unit,
    onOpenBooks: () -> Unit,
) {
    val snapshot = uiState.snapshot
    LazyColumn(
        modifier = modifier.testTag("storage_overview"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (uiState.failed) {
            item(key = "storage_error") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.error_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.storage_refresh_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(
                            onClick = uiState.load,
                            enabled = !uiState.isLoading,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.action_refresh))
                        }
                    }
                }
            }
        }
        if (snapshot == null) {
            if (uiState.isLoading) {
                item(key = "storage_loading") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 48.dp, horizontal = 20.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.storage_measuring),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            item(key = "storage_chart") {
                StorageUsageChart(snapshot, uiState.isLoading)
            }
            item(key = "storage_cleanup") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(
                        text = stringResource(R.string.storage_your_content),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    StorageCleanupSettings(
                        snapshot = snapshot,
                        clearReadingCache = clearReadingCache,
                        clearDownloads = clearDownloads,
                        onOpenBooks = onOpenBooks,
                        enabled = !uiState.isLoading,
                    )
                }
            }
        }
        item(key = "storage_bottom_spacer") {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
