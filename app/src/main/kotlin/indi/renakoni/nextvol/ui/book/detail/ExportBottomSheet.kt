package indi.renakoni.nextvol.ui.book.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.export.ExportType
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SwitchChip
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import io.nightfish.lightnovelreader.api.book.BookVolumes
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    sheetState: SheetState,
    bookVolumes: BookVolumes,
    settings: ExportSettings,
    onSettingsChange: (ExportSettings) -> Unit,
    onDismissRequest: () -> Unit,
    onClickExport: (ExportSettings) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val isSplitEnabled = settings.exportType == ExportType.VOLUMES
    val allVolumeIds = bookVolumes.volumes.map { it.volumeId }.toSet()
    val selectedVolumeIds = if (isSplitEnabled) settings.selectedVolumeIds else allVolumeIds
    val isAllSelected = allVolumeIds.isNotEmpty() && selectedVolumeIds.size == allVolumeIds.size

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.export_epub),
                    style = typography.displayMedium.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                )
                Button(
                    enabled = selectedVolumeIds.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    onClick = {
                        if (isSplitEnabled) {
                            onClickExport(
                                settings.copy(
                                    selectedVolumeIds = selectedVolumeIds,
                                    exportType = ExportType.VOLUMES,
                                ),
                            )
                        } else {
                            onClickExport(
                                settings.copy(
                                    selectedVolumeIds = emptySet(),
                                    exportType = ExportType.BOOK,
                                ),
                            )
                        }
                        onDismissRequest()
                    },
                ) {
                    Text(stringResource(R.string.export), fontWeight = FontWeight.W600)
                }
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SwitchChip(
                    label = stringResource(R.string.export_include_images),
                    selected = settings.includeImages,
                    onClick = {
                        onSettingsChange(settings.copy(includeImages = !settings.includeImages))
                    },
                )
                SwitchChip(
                    label = stringResource(R.string.export_split_volumes),
                    selected = isSplitEnabled,
                    onClick = {
                        onSettingsChange(
                            settings.copy(
                                selectedVolumeIds = if (isSplitEnabled) emptySet() else allVolumeIds,
                                exportType = if (isSplitEnabled) ExportType.BOOK else ExportType.VOLUMES,
                            ),
                        )
                    },
                )
            }

            if (isSplitEnabled && allVolumeIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${selectedVolumeIds.size} / ${allVolumeIds.size}",
                        style = typography.labelLarge.copy(
                            fontWeight = FontWeight.W600,
                            fontFeatureSettings = "tnum",
                        ),
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onSettingsChange(
                                settings.copy(
                                    selectedVolumeIds = if (isAllSelected) emptySet() else allVolumeIds,
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(if (isAllSelected) R.string.deselect_all else R.string.select_all))
                    }
                    TextButton(
                        onClick = {
                            onSettingsChange(settings.copy(selectedVolumeIds = allVolumeIds - selectedVolumeIds))
                        },
                    ) {
                        Text(stringResource(R.string.invert_selection))
                    }
                }
            }

            EditorialHairlineDivider(Modifier.padding(top = 4.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(bookVolumes.volumes, key = { it.volumeId }) { volume ->
                    val isChecked = volume.volumeId in selectedVolumeIds
                    val radius = 14.dp
                    val toggleVolume = {
                        if (isSplitEnabled) {
                            val updated = if (isChecked) {
                                selectedVolumeIds - volume.volumeId
                            } else {
                                selectedVolumeIds + volume.volumeId
                            }
                            onSettingsChange(settings.copy(selectedVolumeIds = updated))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleBackground(
                                color = if (isSplitEnabled && isChecked) tokens.activePillFill else tokens.cardSurface,
                                cornerRadius = radius,
                            )
                            .squircleBorder(
                                width = if (isSplitEnabled && isChecked) 1.dp else 0.75.dp,
                                color = if (isSplitEnabled && isChecked) tokens.activePillBorder else tokens.cardBorder,
                                cornerRadius = radius,
                            )
                            .squircleClip(radius)
                            .clickable(enabled = isSplitEnabled, onClick = toggleVolume)
                            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = volume.volumeTitle,
                                style = typography.titleSmall.copy(
                                    fontWeight = if (isSplitEnabled && isChecked) FontWeight.W600 else FontWeight.W500,
                                ),
                                color = if (isSplitEnabled) colors.onSurface else colors.onSurface.copy(alpha = 0.45f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.info_volume_chapters_count, volume.chapters.size),
                                style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = if (isSplitEnabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        }
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { toggleVolume() },
                            enabled = isSplitEnabled,
                        )
                    }
                }
            }
        }
    }
}
