package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.SovitsImportDraft
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

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
    SovitsImportForm(state, onEdit = viewModel::edit, onAvatar = pickAvatar,
        onPick = { field ->
            pickingField = field
            picker.launch(arrayOf(field.mimeType))
        }, onImport = viewModel::install, onCancel = viewModel::cancel, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SovitsImportForm(state: SovitsImportState, onEdit: (SovitsImportDraft) -> Unit,
    onAvatar: () -> Unit, onPick: (SovitsImportField) -> Unit, onImport: () -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val draft = state.draft
    val enabled = !state.busy && !state.picking
    val focus = LocalFocusManager.current
    Scaffold(topBar = {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.sovits_import_weights, onBack, titleText = null) {
            if (state.busy) TextButton(onClick = onCancel, enabled = !state.cancelling) {
                Text(stringResource(if (state.cancelling) R.string.sovits_cancelling else R.string.cancel))
            } else TextButton(onClick = { focus.clearFocus(); onImport() }, enabled = enabled && draft.ready,
                modifier = Modifier.testTag("sovits-import-confirm")) {
                Text(stringResource(R.string.tts_package_import))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val message = state.error ?: state.progress
            if (message != null) Text(stringResource(message.resource, *message.arguments),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(80.dp).clickable(enabled = enabled, role = Role.Button, onClick = onAvatar)) {
                    SpeechAvatar(draft.avatar, 0, Modifier.fillMaxSize(), contentDescription = stringResource(R.string.tts_voice_avatar))
                    Surface(Modifier.align(Alignment.BottomEnd).size(24.dp), shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(painterResource(R.drawable.edit_square_24px), null, Modifier.padding(5.dp))
                    }
                }
                val nameLabel = stringResource(R.string.sovits_voice_name)
                var nameFocused by remember { mutableStateOf(false) }
                val nameStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                BasicTextField(draft.name, { onEdit(draft.copy(name = it.take(80))) },
                    Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(min = 48.dp)
                        .onFocusChanged { nameFocused = it.isFocused }.semantics { contentDescription = nameLabel },
                    enabled = enabled, singleLine = true, textStyle = nameStyle, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)) { input ->
                    Column(verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.fillMaxWidth().padding(bottom = 3.dp), contentAlignment = Alignment.Center) {
                            if (draft.name.isEmpty()) Text(nameLabel, style = nameStyle, color = MaterialTheme.colorScheme.outline)
                            input()
                        }
                        HorizontalDivider(color = if (nameFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
                SettingsCategory {
                    SovitsFileRow(stringResource(R.string.sovits_gpt_weight), draft.gpt, enabled) { onPick(SovitsImportField.Gpt) }
                    SovitsFileRow(stringResource(R.string.sovits_sovits_weight), draft.sovits, enabled) { onPick(SovitsImportField.Sovits) }
                }
                SettingsCategory {
                    SovitsFileRow(stringResource(R.string.sovits_reference_audio), draft.audio, enabled) { onPick(SovitsImportField.Audio) }
                }
                val transcriptLabel = stringResource(R.string.sovits_reference_text)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(transcriptLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(draft.text, { onEdit(draft.copy(text = it)) },
                        Modifier.fillMaxWidth().semantics { contentDescription = transcriptLabel }, enabled = enabled,
                        minLines = 3, maxLines = 6, textStyle = MaterialTheme.typography.bodyLarge, shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
    }
}

@Composable
private fun SovitsFileRow(label: String, file: SovitsImportFile?, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (file != null) Text(file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (file == null) Text(stringResource(R.string.sovits_choose_file), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
