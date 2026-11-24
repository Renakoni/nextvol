package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StorageCleanupSettings(
    snapshot: StorageUsageSnapshot,
    clearReadingCache: suspend () -> Boolean,
    clearDownloads: suspend () -> Unit,
    onOpenBooks: () -> Unit,
    enabled: Boolean,
) {
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var deleteDownloads by remember { mutableStateOf<Boolean?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val tokens = NextVolDesign.tokens

    if (deleteDownloads != null) {
        val isDeleteAll = deleteDownloads == true
        AlertDialog(
            onDismissRequest = { if (!clearing) deleteDownloads = null },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            icon = {
                EditorialIconBadge(
                    painter = painterResource(
                        if (isDeleteAll) R.drawable.delete_forever_24px else R.drawable.database_24px,
                    ),
                    containerColor = if (isDeleteAll) colors.errorContainer else tokens.iconBadgeContainer,
                    contentColor = if (isDeleteAll) colors.error else tokens.iconBadgeContent,
                )
            },
            title = {
                Text(
                    stringResource(
                        if (isDeleteAll) R.string.storage_delete_all
                        else R.string.settings_clear_reading_cache,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            if (isDeleteAll) R.string.storage_delete_all_confirm
                            else R.string.storage_clear_cache_confirm,
                        ),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.storage_content_estimate,
                            formatSize(if (isDeleteAll) snapshot.downloadBytes else snapshot.readingCacheBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.W600,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !clearing,
                    shape = CircleShape,
                    colors = if (isDeleteAll) {
                        ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    onClick = {
                        val remove = isDeleteAll
                        clearing = true
                        scope.launch {
                            val message = try {
                                if (remove) {
                                    clearDownloads()
                                    R.string.storage_downloads_deleted
                                } else if (clearReadingCache()) {
                                    R.string.settings_cache_cleared
                                } else {
                                    R.string.storage_cleanup_busy
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                R.string.settings_cache_clear_failed
                            } finally {
                                clearing = false
                                deleteDownloads = null
                            }
                            snackbar.showSnackbar(resources.getString(message), withDismissAction = true)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (clearing) R.string.processing
                            else if (isDeleteAll) R.string.storage_delete_all
                            else R.string.storage_clear_cache,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !clearing, onClick = { deleteDownloads = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContentSizeHeader(R.drawable.database_24px, R.string.storage_reading_cache, snapshot.readingCacheBytes)
            Text(
                text = stringResource(
                    if (snapshot.readingCacheBytes == 0L) R.string.storage_cache_empty
                    else R.string.storage_reading_cache_desc,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { deleteDownloads = false },
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("clear_reading_cache"),
                shape = CircleShape,
                enabled = enabled && !clearing && snapshot.readingCacheBytes > 0L,
            ) {
                Text(stringResource(R.string.storage_clear_cache))
            }
        }
    }

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContentSizeHeader(R.drawable.cloud_download_24px, R.string.storage_downloads, snapshot.downloadBytes)
            Text(
                text = if (snapshot.downloadedBookCount == 0) {
                    stringResource(R.string.storage_downloads_empty)
                } else {
                    stringResource(
                        R.string.storage_download_counts,
                        snapshot.downloadedBookCount,
                        snapshot.downloadedChapterCount,
                    )
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant,
            )
            if (snapshot.preparingChapterCount > 0) {
                Text(
                    text = stringResource(R.string.storage_preparing_chapters, snapshot.preparingChapterCount),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant,
                )
            }
            if (snapshot.unfinishedDownloadCount > 0) {
                Text(
                    text = stringResource(R.string.storage_unfinished_downloads, snapshot.unfinishedDownloadCount),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = colors.primary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.check_24px),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primary,
                )
                Text(
                    text = stringResource(R.string.storage_downloads_protected),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
            }
            val canDeleteAll = (snapshot.downloadBytes > 0L || snapshot.unfinishedDownloadCount > 0) &&
                enabled &&
                !clearing
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onOpenBooks,
                    modifier = Modifier.testTag("manage_stored_books"),
                    enabled = !clearing,
                ) {
                    Text(stringResource(R.string.storage_manage_books))
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.arrow_forward_24px), null, Modifier.size(16.dp))
                }
                TextButton(
                    onClick = { deleteDownloads = true },
                    modifier = Modifier.testTag("delete_all_downloads"),
                    enabled = canDeleteAll,
                ) {
                    Text(
                        text = stringResource(R.string.storage_delete_all),
                        color = if (canDeleteAll) colors.error else colors.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentSizeHeader(icon: Int, title: Int, size: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EditorialIconBadge(
            painter = painterResource(icon),
            size = 40.dp,
            iconSize = 20.dp,
            cornerRadius = 12.dp,
        )
        if (LocalDensity.current.fontScale >= 1.3f) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                )
                Text(
                    text = formatSize(size),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Text(
                text = stringResource(title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W600,
            )
            Text(
                text = formatSize(size),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
