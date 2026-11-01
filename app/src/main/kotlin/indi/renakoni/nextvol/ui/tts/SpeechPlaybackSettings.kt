package indi.renakoni.nextvol.ui.tts

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.data.setting.AbstractSettingState
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class SpeechPlaybackSettingsState(
    val settings: SpeechTextSettings? = null,
    val error: SpeechError? = null,
)

@HiltViewModel
class SpeechPlaybackSettingsViewModel @Inject constructor(
    private val repository: SpeechSettingsRepository,
    userData: UserDataRepository,
) : ViewModel() {
    val preferences = SpeechPlaybackPreferences(userData, viewModelScope)
    private val mutableState = MutableStateFlow(SpeechPlaybackSettingsState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.changes.collect { settings -> mutableState.update { it.copy(settings = settings.text) } }
        }
    }

    fun update(change: (SpeechTextSettings) -> SpeechTextSettings) {
        viewModelScope.launch {
            try {
                repository.update { it.copy(text = change(it.text)) }
                mutableState.update { it.copy(error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }
}

@Stable
class SpeechPlaybackPreferences(userData: UserDataRepository, scope: CoroutineScope) : AbstractSettingState(scope) {
    val auditionData = userData.stringUserData("tts.audition.text")
    val parametersData = userData.booleanUserData("tts.editor.parameters")
    val extensionsData = userData.booleanUserData("tts.editor.extensions")
    val auditionText by auditionData.asState("")
    val showParameters by parametersData.asState(false)
    val showExtensions by extensionsData.asState(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechPlaybackSettingsScreen(state: SpeechPlaybackSettingsState, preferences: SpeechPlaybackPreferences, onBack: () -> Unit,
    onUpdate: ((SpeechTextSettings) -> SpeechTextSettings) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val defaultText = stringResource(R.string.tts_preview_text)
    Scaffold(topBar = {
        TopAppBar(title = {
            Text(stringResource(R.string.tts_playback_preferences), style = MaterialTheme.typography.displayLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            if (state.settings == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item {
                Text(stringResource(error.messageResource), Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.error)
            } }
            state.settings?.let { settings ->
                item { SpeechTextSwitch(R.drawable.tts_text_dialogue, R.string.tts_text_dialogue, R.string.tts_text_dialogue_description,
                    settings.dialogue) { onUpdate { old -> old.copy(dialogue = it) } } }
                item { SpeechTextSwitch(R.drawable.tts_text_record_voice, R.string.tts_text_record_voice, R.string.tts_text_record_voice_description,
                    settings.recordVoice) { onUpdate { old -> old.copy(recordVoice = it) } } }
                item { SpeechTextSwitch(R.drawable.tts_text_record_text, R.string.tts_text_record_text, R.string.tts_text_record_text_description,
                    settings.recordText) { onUpdate { old -> old.copy(recordText = it) } } }
                item { SpeechTextSwitch(R.drawable.tts_text_continue_dialogue, R.string.tts_text_continue_dialogue, R.string.tts_text_continue_dialogue_description,
                    settings.continueDialogue) { onUpdate { old -> old.copy(continueDialogue = it) } } }
                item { HorizontalDivider(Modifier.padding(start = 60.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) }
                item { SpeechTextSwitch(R.drawable.tts_text_replace_emoji, R.string.tts_text_replace_emoji, R.string.tts_text_replace_emoji_description,
                    settings.replaceEmoji) { onUpdate { old -> old.copy(replaceEmoji = it) } } }
                item { SpeechTextSwitch(R.drawable.tts_text_separate_english, R.string.tts_text_separate_english, R.string.tts_text_separate_english_description,
                    settings.separateEnglish) { onUpdate { old -> old.copy(separateEnglish = it) } } }
                item { SpeechTextSwitch(R.drawable.tts_text_filter_unspoken, R.string.tts_text_filter_unspoken, R.string.tts_text_filter_unspoken_description,
                    settings.filterUnspoken) { onUpdate { old -> old.copy(filterUnspoken = it) } } }
                item {
                    var length by remember(settings.maxSentenceLength) { mutableFloatStateOf(settings.maxSentenceLength.toFloat()) }
                    val title = stringResource(R.string.tts_text_sentence_length)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Icon(painterResource(R.drawable.tts_text_sentence_length), null,
                            Modifier.padding(top = 2.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                                Text(length.roundToInt().toString(), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.tts_text_sentence_length_description),
                                Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(length, { length = it }, valueRange = 32f..256f,
                                onValueChangeFinished = { onUpdate { it.copy(maxSentenceLength = length.roundToInt()) } },
                                modifier = Modifier.semantics { contentDescription = title },
                                thumb = { Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {} },
                                track = { SliderDefaults.Track(it, Modifier.height(4.dp), thumbTrackGapSize = 0.dp,
                                    trackInsideCornerSize = 2.dp, drawStopIndicator = null) })
                        }
                    }
                }
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_audition_text),
                    option = preferences.auditionText.ifBlank { defaultText },
                    onClick = { text = preferences.auditionText.ifBlank { defaultText }; editing = true })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_show_parameters),
                    trailingContent = { Switch(preferences.showParameters, preferences.parametersData::asynchronousSet) },
                    onClick = { preferences.parametersData.asynchronousSet(!preferences.showParameters) })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_show_extensions),
                    trailingContent = { Switch(preferences.showExtensions, preferences.extensionsData::asynchronousSet) },
                    onClick = { preferences.extensionsData.asynchronousSet(!preferences.showExtensions) })
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.tts_voice_audition_text)) },
        text = { TextField(text, { text = it.take(2000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
        confirmButton = { TextButton(onClick = { preferences.auditionData.asynchronousSet(text.trim()); editing = false }) {
            Text(stringResource(R.string.tts_engine_save))
        } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SpeechTextSwitch(@DrawableRes icon: Int, @StringRes title: Int, @StringRes description: Int,
    checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 68.dp).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, modifier = Modifier.scale(0.8f))
    }
}
