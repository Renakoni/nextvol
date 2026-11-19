package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.runtime.*
import javax.inject.Inject

@HiltViewModel
internal class SpeechRuntimeViewModel @Inject constructor(val downloads: SpeechRuntimeDownloads) : ViewModel()

@Composable
internal fun SpeechRuntimeDialog(onDismiss: () -> Unit) {
    val downloads = hiltViewModel<SpeechRuntimeViewModel>().downloads
    val states by downloads.changes.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tts_runtime_packs), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close))
                    }
                }
                for (pack in SpeechRuntimePack.entries) {
                    if (pack != SpeechRuntimePack.entries.first()) HorizontalDivider()
                    RuntimeRow(pack, states.getValue(pack), onStart = { downloads.start(pack) }, onCancel = { downloads.cancel(pack) })
                }
            }
        }
    }
}

@Composable
private fun RuntimeRow(pack: SpeechRuntimePack, state: RuntimeDownload, onStart: () -> Unit, onCancel: () -> Unit) {
    val vendor = pack == SpeechRuntimePack.Vendor
    val colors = MaterialTheme.colorScheme
    val transfer = state as? RuntimeDownload.Downloading
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = MaterialTheme.shapes.small,
                color = if (vendor) colors.secondaryContainer else colors.primaryContainer) {
                Icon(painterResource(if (vendor) R.drawable.volume_up_24px else R.drawable.tts_audio_sample_rate),
                    contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (vendor) R.string.tts_runtime_vendor else R.string.tts_runtime_sovits),
                    style = MaterialTheme.typography.titleMedium)
                val detail = when {
                    transfer != null -> "${megabytes(transfer.copied)} / ${megabytes(transfer.total)}"
                    state == RuntimeDownload.Installing -> stringResource(R.string.tts_runtime_installing)
                    else -> megabytes(pack.archiveBytes)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            when (state) {
                RuntimeDownload.Available, is RuntimeDownload.Failed -> FilledTonalButton(onClick = onStart,
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(stringResource(if (state == RuntimeDownload.Available) R.string.tts_runtime_download else R.string.tts_retry))
                }
                is RuntimeDownload.Downloading, RuntimeDownload.Installing -> TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                RuntimeDownload.Installed -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.check_24px), null, Modifier.size(18.dp), tint = colors.primary)
                    Text(stringResource(R.string.tts_runtime_installed), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (transfer != null) {
            val fraction = (transfer.copied.toFloat() / transfer.total).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
        } else if (state == RuntimeDownload.Installing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state is RuntimeDownload.Failed) Text(state.detail, style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
}

@Composable
private fun megabytes(bytes: Long) = stringResource(R.string.tts_runtime_megabytes, bytes / 1_000_000.0)
