package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechTextAnalysisCandidate
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.names.NewWordAlgorithm
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
import indi.renakoni.nextvol.ui.components.DownloadTaskProgress

@Composable
internal fun SpeechTextAnalysisDialog(onBack: () -> Unit, onOpenStorage: () -> Unit) {
    val model = hiltViewModel<SpeechTextAnalysisViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val dismiss = { model.cancel(); onBack() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) dismiss() else model.openFile(uri)
    }
    LaunchedEffect(Unit) {
        if (model.bookId == null && !model.filePickerOpened) {
            model.filePickerOpened = true
            picker.launch(arrayOf("text/plain"))
        }
    }
    SpeechTextAnalysisContent(state, dismiss, model::start, model::retry, model::toggle, model::gender,
        model::edit, { model.save(onBack) }, onOpenStorage)
}

@Composable
internal fun SpeechTextAnalysisContent(state: SpeechTextAnalysisState, onCancel: () -> Unit,
    onStart: (Boolean) -> Unit, onRetry: () -> Unit, onToggle: (String) -> Unit, onGender: (String) -> Unit,
    onEdit: (String, String, List<String>) -> Unit, onSave: () -> Unit, onOpenStorage: () -> Unit) {
    var editing by remember { mutableStateOf<SpeechTextAnalysisCandidate?>(null) }
    val result = state.result
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .85f
    Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
        shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(top = 24.dp)) {
            Text(if (result != null) state.sourceName else stringResource(R.string.tts_analysis_title),
                Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.sourceName.isNotBlank() && result == null)
                Text(state.sourceName, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                result != null -> {
                    state.book?.let { book ->
                        Text(stringResource(R.string.tts_analysis_coverage, book.cachedChapters, book.totalChapters),
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val statistic = stringResource(if (result.algorithm == NewWordAlgorithm.Native)
                        R.string.tts_new_word_score else R.string.tts_analysis_frequency)
                    Column(Modifier.weight(1f, fill = false).padding(horizontal = 10.dp, vertical = 10.dp)) {
                        AnalysisTableRow {
                            Spacer(Modifier.width(48.dp))
                            Text(stringResource(R.string.tts_analysis_name), Modifier.weight(4f), style = MaterialTheme.typography.labelLarge)
                            Text(statistic, Modifier.weight(3f), style = MaterialTheme.typography.labelLarge)
                            Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.tts_voice_gender), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        LazyColumn(Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(top = 5.dp)) {
                            if (result.candidates.isEmpty()) item {
                                Text(stringResource(R.string.tts_new_word_empty), Modifier.padding(14.dp))
                            }
                            items(result.candidates, key = { it.id }) { candidate ->
                                AnalysisCandidateRow(candidate, state.saving, { onToggle(candidate.id) },
                                    { onGender(candidate.id) }, { editing = candidate })
                            }
                        }
                    }
                }
                state.download != null -> Box(Modifier.padding(horizontal = 24.dp)) {
                    DownloadTaskProgress(state.download, false, onRetry, null, onOpenStorage)
                }
                state.running || state.loading -> {
                    Text(stringResource(if (state.loading) R.string.tts_analysis_preparing else R.string.tts_analysis_running),
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                }
                state.book != null -> {
                    AnalysisSourceChoice(R.drawable.text_snippet_24px, stringResource(R.string.tts_analysis_cached),
                        stringResource(R.string.tts_analysis_coverage, state.book.cachedChapters, state.book.totalChapters),
                        enabled = state.book.cachedChapters > 0) { onStart(false) }
                    AnalysisSourceChoice(R.drawable.cloud_download_24px, stringResource(R.string.tts_analysis_download),
                        enabled = !state.book.local) { onStart(true) }
                }
            }
            val error = state.error?.let { stringResource(R.string.tts_new_word_error, it) }
                ?: state.speechError?.let { stringResource(it.messageResource) }
            if (error != null) Text(error, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                if (result != null) TextButton(onSave, enabled = !state.saving) { Text(stringResource(R.string.tts_engine_save)) }
                else if (error != null && state.download == null) TextButton(onRetry) { Text(stringResource(R.string.book_download_retry)) }
            }
        }
    }
    editing?.let { candidate ->
        AnalysisCandidateEditor(candidate, { editing = null }) { name, aliases ->
            onEdit(candidate.id, name, aliases)
            editing = null
        }
    }
}

@Composable
private fun AnalysisSourceChoice(icon: Int, title: String, description: String? = null,
    enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = 68.dp).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            description?.let { Text(it, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AnalysisTableRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 5.dp), shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 1.dp) {
        Row(Modifier.heightIn(min = 60.dp).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}

@Composable
private fun AnalysisCandidateRow(candidate: SpeechTextAnalysisCandidate, saving: Boolean,
    onToggle: () -> Unit, onGender: () -> Unit, onEdit: () -> Unit) {
    val editLabel = stringResource(R.string.tts_analysis_edit)
    val gender = stringResource(if (candidate.gender == SpeechNameHint.Female) R.string.tts_voice_female else R.string.tts_voice_male)
    AnalysisTableRow(Modifier.combinedClickable(enabled = !saving, role = Role.Checkbox,
        onClick = onToggle, onLongClick = onEdit, onLongClickLabel = editLabel)) {
        Checkbox(candidate.selected, onCheckedChange = { onToggle() }, enabled = !saving)
        Text((if (candidate.matchesNamePattern) "● " else "○ ") + candidate.name,
            Modifier.weight(4f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text(candidate.value.toString(), Modifier.weight(3f), style = MaterialTheme.typography.bodyMedium)
        val genderDescription = stringResource(R.string.tts_analysis_gender_action, candidate.name, gender)
        Box(Modifier.weight(2f).heightIn(min = 48.dp).clickable(enabled = !saving, onClick = onGender)
            .semantics { contentDescription = genderDescription }, contentAlignment = Alignment.Center) {
            Text(gender, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AnalysisCandidateEditor(candidate: SpeechTextAnalysisCandidate, onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit) {
    var name by rememberSaveable(candidate.id) { mutableStateOf(candidate.name) }
    var aliases by rememberSaveable(candidate.id) { mutableStateOf(candidate.aliases.joinToString("\n")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tts_analysis_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.tts_analysis_name)) })
                OutlinedTextField(aliases, { aliases = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text(stringResource(R.string.tts_analysis_aliases)) })
                Text(stringResource(R.string.tts_analysis_original_word, candidate.id),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val hint = stringResource(when (candidate.classification.hint) {
                    SpeechNameHint.Male -> R.string.tts_name_hint_male
                    SpeechNameHint.Female -> R.string.tts_name_hint_female
                    SpeechNameHint.Unknown -> R.string.tts_name_hint_unknown
                })
                Text(hint + " · " + stringResource(when (candidate.classification.basis) {
                    SpeechNameHintBasis.Model -> R.string.tts_name_basis_model
                    SpeechNameHintBasis.MaleRule -> R.string.tts_name_basis_male
                    SpeechNameHintBasis.FemaleRule -> R.string.tts_name_basis_female
                    SpeechNameHintBasis.None -> R.string.tts_name_basis_none
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, aliases.lines()) }, enabled = name.isNotBlank()) {
            Text(stringResource(R.string.confirm))
        } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
