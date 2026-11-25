package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.SovitsImportDraft
import indi.renakoni.nextvol.tts.sovits.SovitsImportField
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile
import indi.renakoni.nextvol.tts.sovits.SovitsReferenceLanguage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar

@Composable
internal fun SovitsImportScreen(onBack: () -> Unit) {
    val viewModel = hiltViewModel<SovitsImportViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickAvatar = rememberSpeechAvatarPicker { viewModel.edit(viewModel.state.value.draft.copy(avatar = it)) }
    var pickingField by rememberSaveable { mutableStateOf<SovitsImportField?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val field = pickingField
        pickingField = null
        if (uri != null && field != null) viewModel.pickFile(field, uri)
    }
    LaunchedEffect(state.completed) { if (state.completed) onBack() }
    SovitsImportForm(
        state = state,
        onEdit = viewModel::edit,
        onAvatar = pickAvatar,
        onPick = { field ->
            pickingField = field
            picker.launch(arrayOf(field.mimeType))
        },
        onImport = viewModel::install,
        onCancel = viewModel::cancel,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SovitsImportForm(
    state: SovitsImportState,
    onEdit: (SovitsImportDraft) -> Unit,
    onAvatar: () -> Unit,
    onPick: (SovitsImportField) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val draft = state.draft
    val enabled = !state.busy && !state.picking
    val focus = LocalFocusManager.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.sovits_import_weights),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
                actions = {
                    if (state.busy) {
                        TextButton(onClick = onCancel, enabled = !state.cancelling) {
                            Text(stringResource(if (state.cancelling) R.string.sovits_cancelling else R.string.cancel))
                        }
                    } else {
                        Button(
                            onClick = {
                                focus.clearFocus()
                                onImport()
                            },
                            enabled = enabled && draft.ready,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(stringResource(R.string.tts_package_import))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            val message = state.error ?: state.progress
            if (message != null) {
                Text(
                    text = stringResource(message.resource, *message.arguments),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(80.dp).clickable(enabled = enabled, role = Role.Button, onClick = onAvatar)) {
                    SpeechAvatar(draft.avatar, 0, Modifier.fillMaxSize(), contentDescription = stringResource(R.string.tts_voice_avatar))
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(painterResource(R.drawable.edit_square_24px), null, Modifier.padding(5.dp))
                    }
                }
                val nameLabel = stringResource(R.string.sovits_voice_name)
                var nameFocused by remember { mutableStateOf(false) }
                val nameStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                BasicTextField(
                    value = draft.name,
                    onValueChange = { onEdit(draft.copy(name = it.take(80))) },
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .onFocusChanged { nameFocused = it.isFocused }
                        .semantics { contentDescription = nameLabel },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = nameStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                ) { input ->
                    Column(verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                            if (draft.name.isEmpty()) Text(nameLabel, style = nameStyle, color = MaterialTheme.colorScheme.outline)
                            input()
                        }
                        HorizontalDivider(color = if (nameFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(4.dp))
                EditorialCard(modifier = Modifier.fillMaxWidth()) {
                    SovitsFileRow(stringResource(R.string.sovits_gpt_weight), SovitsImportField.Gpt, draft.gpt, enabled, onPick)
                    EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    SovitsFileRow(stringResource(R.string.sovits_sovits_weight), SovitsImportField.Sovits, draft.sovits, enabled, onPick)
                }
                EditorialCard(modifier = Modifier.fillMaxWidth()) {
                    SovitsFileRow(stringResource(R.string.sovits_reference_audio), SovitsImportField.Audio, draft.audio, enabled, onPick)
                    EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    ReferenceLanguageRow(draft, enabled) { onEdit(draft.copy(referenceLanguage = it)) }
                }
                val transcriptLabel = stringResource(R.string.sovits_reference_text)
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = transcriptLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.W600,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val filename = draft.transcriptFromFilename
                            TextButton(
                                onClick = { if (filename != null) onEdit(draft.copy(text = filename)) },
                                enabled = enabled && filename != null,
                            ) {
                                Text(stringResource(R.string.sovits_use_filename))
                            }
                        }
                        OutlinedTextField(
                            value = draft.text,
                            onValueChange = { onEdit(draft.copy(text = it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = transcriptLabel },
                            enabled = enabled,
                            minLines = 3,
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SovitsFileRow(
    label: String,
    field: SovitsImportField,
    file: SovitsImportFile?,
    enabled: Boolean,
    onPick: (SovitsImportField) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button) { onPick(field) }
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.W500,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (file != null) {
                if (field.accepts(file)) {
                    Text(
                        text = file.name,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.sovits_choose_extension, ".${field.extension}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (file == null) {
            Text(
                text = stringResource(R.string.sovits_choose_file),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReferenceLanguageRow(
    draft: SovitsImportDraft,
    enabled: Boolean,
    select: (SovitsReferenceLanguage?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun label(language: SovitsReferenceLanguage) = when (language) {
        SovitsReferenceLanguage.Chinese -> R.string.sovits_language_zh
        SovitsReferenceLanguage.Japanese -> R.string.sovits_language_ja
        SovitsReferenceLanguage.English -> R.string.sovits_language_en
    }
    val language = draft.referenceLanguage
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button) { expanded = true }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sovits_reference_language),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.W500,
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (language == null && draft.text.isNotBlank()) {
                        stringResource(R.string.sovits_language_detected, stringResource(label(SovitsReferenceLanguage.detect(draft.text))))
                    } else {
                        stringResource(language?.let(::label) ?: R.string.sovits_language_auto)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.W500,
                )
                Icon(
                    painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(180f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EditorialPopupMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                EditorialPopupMenuHeader(text = stringResource(R.string.sovits_reference_language))
                EditorialPopupMenuItem(
                    text = stringResource(R.string.sovits_language_auto),
                    selected = language == null,
                    onClick = {
                        expanded = false
                        select(null)
                    },
                )
                for (option in SovitsReferenceLanguage.entries) {
                    EditorialPopupMenuItem(
                        text = stringResource(label(option)),
                        selected = language == option,
                        onClick = {
                            expanded = false
                            select(option)
                        },
                    )
                }
            }
        }
    }
}
