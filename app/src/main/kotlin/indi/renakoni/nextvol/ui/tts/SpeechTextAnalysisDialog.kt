package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechTextAnalysisCandidate
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.names.NewWordAlgorithm
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
import indi.renakoni.nextvol.ui.components.DownloadTaskProgress
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton

@Composable
internal fun SpeechTextAnalysisDialog(onBack: () -> Unit, onOpenStorage: () -> Unit) {
    val model = hiltViewModel<SpeechTextAnalysisViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val dismiss = {
        model.cancel()
        onBack()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) dismiss() else model.openFile(uri)
    }
    LaunchedEffect(Unit) {
        if (model.bookId == null && !model.filePickerOpened) {
            model.filePickerOpened = true
            picker.launch(arrayOf("text/plain"))
        }
    }
    SpeechTextAnalysisContent(
        state = state,
        onCancel = dismiss,
        onStart = model::start,
        onRetry = model::retry,
        onToggle = model::toggle,
        onGender = model::gender,
        onEdit = model::edit,
        onSave = { model.save(onBack) },
        onOpenStorage = onOpenStorage,
    )
}

@Composable
internal fun SpeechTextAnalysisContent(
    state: SpeechTextAnalysisState,
    onCancel: () -> Unit,
    onStart: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onGender: (String) -> Unit,
    onEdit: (String, String, List<String>) -> Unit,
    onSave: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var editing by remember { mutableStateOf<SpeechTextAnalysisCandidate?>(null) }
    val result = state.result
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(tokens.sheetRadius),
        color = tokens.cardElevatedSurface,
        border = BorderStroke(0.75.dp, tokens.cardBorder),
        shadowElevation = 12.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.analytics_24px),
                    size = 38.dp,
                    iconSize = 19.dp,
                    cornerRadius = 11.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (result != null) state.sourceName else stringResource(R.string.tts_analysis_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.sourceName.isNotBlank() && result == null) {
                        Text(
                            text = state.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                EditorialIconButton(
                    onClick = onCancel,
                    enabled = !state.saving,
                    painter = painterResource(R.drawable.close_24px),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            EditorialHairlineDivider()

            when {
                result != null -> {
                    state.book?.let { book ->
                        Text(
                            text = stringResource(R.string.tts_analysis_coverage, book.cachedChapters, book.totalChapters),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val statistic = stringResource(
                        if (result.algorithm == NewWordAlgorithm.Native) R.string.tts_new_word_score
                        else R.string.tts_analysis_frequency,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(44.dp))
                            Text(stringResource(R.string.tts_analysis_name), Modifier.weight(4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(statistic, Modifier.weight(3f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.tts_voice_gender), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (result.candidates.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.tts_new_word_empty),
                                        modifier = Modifier.padding(14.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(result.candidates, key = { it.id }) { candidate ->
                                AnalysisCandidateRow(
                                    candidate = candidate,
                                    saving = state.saving,
                                    onToggle = { onToggle(candidate.id) },
                                    onGender = { onGender(candidate.id) },
                                    onEdit = { editing = candidate },
                                )
                            }
                        }
                    }
                }
                state.download != null -> Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    DownloadTaskProgress(state.download, false, onRetry, null, onOpenStorage)
                }
                state.running || state.loading -> {
                    Text(
                        text = stringResource(if (state.loading) R.string.tts_analysis_preparing else R.string.tts_analysis_running),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.book != null -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AnalysisSourceChoice(
                            icon = R.drawable.text_snippet_24px,
                            title = stringResource(R.string.tts_analysis_cached),
                            description = stringResource(R.string.tts_analysis_coverage, state.book.cachedChapters, state.book.totalChapters),
                            enabled = state.book.cachedChapters > 0,
                        ) { onStart(false) }
                        AnalysisSourceChoice(
                            icon = R.drawable.cloud_download_24px,
                            title = stringResource(R.string.tts_analysis_download),
                            enabled = !state.book.local,
                        ) { onStart(true) }
                    }
                }
            }
            val error = state.error?.let { stringResource(R.string.tts_new_word_error, it) }
                ?: state.speechError?.let { stringResource(it.messageResource) }
            if (error != null) {
                Text(
                    text = error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            EditorialHairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onCancel, enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                if (result != null) {
                    Button(onClick = onSave, enabled = !state.saving, shape = CircleShape) {
                        Text(stringResource(R.string.tts_engine_save))
                    }
                } else if (error != null && state.download == null) {
                    Button(onClick = onRetry, shape = CircleShape) {
                        Text(stringResource(R.string.book_download_retry))
                    }
                }
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
private fun AnalysisSourceChoice(
    icon: Int,
    title: String,
    description: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    EditorialCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
        cornerRadius = 16.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditorialIconBadge(
                painter = painterResource(icon),
                size = 38.dp,
                iconSize = 19.dp,
                cornerRadius = 11.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
                description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AnalysisTableRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun AnalysisCandidateRow(
    candidate: SpeechTextAnalysisCandidate,
    saving: Boolean,
    onToggle: () -> Unit,
    onGender: () -> Unit,
    onEdit: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val editLabel = stringResource(R.string.tts_analysis_edit)
    val gender = stringResource(if (candidate.gender == SpeechNameHint.Female) R.string.tts_voice_female else R.string.tts_voice_male)
    AnalysisTableRow(
        modifier = Modifier.combinedClickable(
            enabled = !saving,
            role = Role.Checkbox,
            onClick = onToggle,
            onLongClick = onEdit,
            onLongClickLabel = editLabel,
        ),
    ) {
        Checkbox(candidate.selected, onCheckedChange = { onToggle() }, enabled = !saving)
        Text(
            text = (if (candidate.matchesNamePattern) "● " else "○ ") + candidate.name,
            modifier = Modifier
                .weight(4f)
                .padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W500,
        )
        Text(
            text = candidate.value.toString(),
            modifier = Modifier.weight(3f),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val genderDescription = stringResource(R.string.tts_analysis_gender_action, candidate.name, gender)
        Box(
            modifier = Modifier
                .weight(2f)
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .clickable(enabled = !saving, onClick = onGender)
                .semantics { contentDescription = genderDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tokens.activePillFill)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = gender,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AnalysisCandidateEditor(
    candidate: SpeechTextAnalysisCandidate,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var name by rememberSaveable(candidate.id) { mutableStateOf(candidate.name) }
    var aliases by rememberSaveable(candidate.id) { mutableStateOf(candidate.aliases.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = { Text(stringResource(R.string.tts_analysis_edit), fontWeight = FontWeight.W600) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.tts_analysis_name)) },
                )
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.tts_analysis_aliases)) },
                )
                Text(
                    text = stringResource(R.string.tts_analysis_original_word, candidate.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hint = stringResource(
                    when (candidate.classification.hint) {
                        SpeechNameHint.Male -> R.string.tts_name_hint_male
                        SpeechNameHint.Female -> R.string.tts_name_hint_female
                        SpeechNameHint.Unknown -> R.string.tts_name_hint_unknown
                    },
                )
                Text(
                    text = hint + " · " + stringResource(
                        when (candidate.classification.basis) {
                            SpeechNameHintBasis.Model -> R.string.tts_name_basis_model
                            SpeechNameHintBasis.MaleRule -> R.string.tts_name_basis_male
                            SpeechNameHintBasis.FemaleRule -> R.string.tts_name_basis_female
                            SpeechNameHintBasis.None -> R.string.tts_name_basis_none
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, aliases.lines()) },
                enabled = name.isNotBlank(),
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
