package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tts_runtime_packs)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((pack, status) in states) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (pack == SpeechRuntimePack.Vendor) R.string.tts_runtime_vendor else R.string.tts_runtime_sovits))
                            val detail = when (status) {
                                RuntimeDownload.Installed -> stringResource(R.string.tts_runtime_installed)
                                RuntimeDownload.Unsupported -> stringResource(R.string.tts_runtime_unsupported)
                                is RuntimeDownload.Downloading -> status.percent?.let { "$it%" } ?: stringResource(R.string.tts_runtime_downloading)
                                is RuntimeDownload.Failed -> status.detail
                                RuntimeDownload.Available -> null
                            }
                            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        when (status) {
                            RuntimeDownload.Available, is RuntimeDownload.Failed -> TextButton(onClick = { downloads.start(pack) }) {
                                Text(stringResource(if (status == RuntimeDownload.Available) R.string.tts_runtime_download else R.string.tts_retry))
                            }
                            is RuntimeDownload.Downloading -> TextButton(onClick = { downloads.cancel(pack) }) {
                                Text(stringResource(R.string.cancel))
                            }
                            else -> Unit
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
