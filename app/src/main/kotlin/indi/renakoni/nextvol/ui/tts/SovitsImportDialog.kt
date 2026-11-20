package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.SovitsImportDraft
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile

@Composable
internal fun SovitsImportDialog(onDismiss: () -> Unit) {
    val viewModel = hiltViewModel<SovitsImportViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gpt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { viewModel.pick(SovitsImportField.Gpt, it) } }
    val sovits = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { viewModel.pick(SovitsImportField.Sovits, it) } }
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { viewModel.pick(SovitsImportField.Audio, it) } }
    val dismiss = { viewModel.reset(); onDismiss() }
    LaunchedEffect(state.completed) { if (state.completed) dismiss() }
    SovitsImportForm(state, onEdit = viewModel::edit, onPick = {
        when (it) {
            SovitsImportField.Gpt -> gpt.launch(arrayOf("*/*"))
            SovitsImportField.Sovits -> sovits.launch(arrayOf("*/*"))
            SovitsImportField.Audio -> audio.launch(arrayOf("audio/*"))
        }
    }, onImport = viewModel::install, onCancel = viewModel::cancel, onDismiss = dismiss)
}

@Composable
internal fun SovitsImportForm(state: SovitsImportState, onEdit: (SovitsImportDraft) -> Unit,
    onPick: (SovitsImportField) -> Unit, onImport: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val enabled = !state.busy && !state.picking
    Dialog(onDismissRequest = { if (enabled) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.sovits_import_weights), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sovits_import_description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SovitsFileButton(stringResource(R.string.sovits_gpt_weight), state.draft.gpt, enabled) { onPick(SovitsImportField.Gpt) }
                    SovitsFileButton(stringResource(R.string.sovits_sovits_weight), state.draft.sovits, enabled) { onPick(SovitsImportField.Sovits) }
                    SovitsFileButton(stringResource(R.string.sovits_reference_audio), state.draft.audio, enabled) { onPick(SovitsImportField.Audio) }
                    OutlinedTextField(state.draft.text, { onEdit(state.draft.copy(text = it)) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sovits_reference_text)) }, enabled = enabled, minLines = 3, maxLines = 6)
                    OutlinedTextField(state.draft.name, { onEdit(state.draft.copy(name = it.take(80))) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sovits_voice_name)) }, singleLine = true, enabled = enabled)
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.progress?.let { Text(stringResource(it.resource, *it.arguments), style = MaterialTheme.typography.bodyMedium) }
                    }
                    state.error?.let { Text(stringResource(it.resource, *it.arguments), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sovits-import-error")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.busy) TextButton(onClick = onCancel, enabled = !state.cancelling) {
                        Text(stringResource(if (state.cancelling) R.string.sovits_cancelling else R.string.cancel))
                    } else {
                        TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = onImport, enabled = enabled && state.draft.ready, modifier = Modifier.testTag("sovits-import-confirm")) {
                            Text(stringResource(R.string.sovits_convert_import))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SovitsFileButton(label: String, file: SovitsImportFile?, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(file?.name ?: stringResource(R.string.sovits_choose_file), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
