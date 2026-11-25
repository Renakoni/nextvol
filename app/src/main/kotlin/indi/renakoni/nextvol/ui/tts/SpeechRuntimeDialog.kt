package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.runtime.RuntimeDownload
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimeDownloads
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import indi.renakoni.nextvol.tts.sovits.DeviceAcceleration
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import androidx.compose.ui.platform.LocalContext
import javax.inject.Inject

@HiltViewModel
internal class SpeechRuntimeViewModel @Inject constructor(val downloads: SpeechRuntimeDownloads) : ViewModel()

@Composable
internal fun SpeechRuntimeDialog(onDismiss: () -> Unit) {
    val tokens = NextVolDesign.tokens
    val downloads = hiltViewModel<SpeechRuntimeViewModel>().downloads
    val states by downloads.changes.collectAsStateWithLifecycle()
    val supportsGpt = DeviceAcceleration.supportsRuntime(LocalContext.current)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(tokens.sheetRadius),
            color = tokens.cardElevatedSurface,
            border = BorderStroke(0.75.dp, tokens.cardBorder),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tts_runtime_packs),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600,
                    )
                    EditorialIconButton(
                        onClick = onDismiss,
                        painter = painterResource(R.drawable.close_24px),
                        contentDescription = stringResource(R.string.close),
                    )
                }
                EditorialHairlineDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (pack in SpeechRuntimePack.entries) {
                        RuntimeRow(
                            pack = pack,
                            state = states.getValue(pack),
                            supported = pack == SpeechRuntimePack.Vendor || supportsGpt,
                            onStart = { downloads.start(pack) },
                            onCancel = { downloads.cancel(pack) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeRow(
    pack: SpeechRuntimePack,
    state: RuntimeDownload,
    supported: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val vendor = pack == SpeechRuntimePack.Vendor
    val colors = MaterialTheme.colorScheme
    val transfer = state as? RuntimeDownload.Downloading
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        containerColor = colors.surfaceContainerLow,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EditorialIconBadge(
                    painter = painterResource(if (vendor) R.drawable.volume_up_24px else R.drawable.tts_audio_sample_rate),
                    size = 38.dp,
                    iconSize = 19.dp,
                    cornerRadius = 11.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(
                            when (pack) {
                                SpeechRuntimePack.Vendor -> R.string.tts_runtime_vendor
                                SpeechRuntimePack.Sovits -> R.string.tts_runtime_sovits
                                SpeechRuntimePack.SovitsModels -> R.string.sovits_shared_models
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W600,
                    )
                    val detail = when {
                        transfer != null -> "${megabytes(transfer.copied)} / ${megabytes(transfer.total)}"
                        state == RuntimeDownload.Installing -> stringResource(R.string.tts_runtime_installing)
                        state == RuntimeDownload.Installed -> megabytes(pack.archiveBytes)
                        else -> megabytes(pack.files.filterNot { it.installed }.sumOf { it.bytes })
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.onSurfaceVariant,
                    )
                }
                if (!supported) {
                    FilledTonalButton(
                        onClick = {},
                        enabled = false,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(stringResource(R.string.sovits_acceleration_unsupported))
                    }
                } else {
                    when (state) {
                        RuntimeDownload.Available, is RuntimeDownload.Failed -> FilledTonalButton(
                            onClick = onStart,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Text(stringResource(if (state == RuntimeDownload.Available) R.string.tts_runtime_download else R.string.tts_retry))
                        }
                        is RuntimeDownload.Downloading, RuntimeDownload.Installing -> TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.cancel))
                        }
                        RuntimeDownload.Installed -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(painterResource(R.drawable.check_24px), null, Modifier.size(18.dp), tint = colors.primary)
                            Text(
                                text = stringResource(R.string.tts_runtime_installed),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.W600,
                                color = colors.primary,
                            )
                        }
                    }
                }
            }
            if (transfer != null) {
                val fraction = (transfer.copied.toFloat() / transfer.total).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape),
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
            } else if (state == RuntimeDownload.Installing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            }
            if (state is RuntimeDownload.Failed) {
                Text(state.detail, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
        }
    }
}

@Composable
private fun megabytes(bytes: Long) = stringResource(R.string.tts_runtime_megabytes, bytes / 1_000_000.0)
