package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechVoiceRoutingScreen(state: SpeechVoiceRoutingState, onBack: () -> Unit,
    onCandidates: (Boolean) -> Unit, onPlan: () -> Unit, onSources: () -> Unit) {
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_voice_routing, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                SettingsCategory {
                    RoutingEntry(stringResource(R.string.tts_narration_voices),
                        candidatesLabel(state.settings.narrationVoices, state.settings.voiceRoutingConfigured), { onCandidates(false) })
                    RoutingEntry(stringResource(R.string.tts_dialogue_voices),
                        candidatesLabel(state.settings.dialogueVoices, state.settings.voiceRoutingConfigured), { onCandidates(true) })
                }
            }
            item {
                Text(stringResource(R.string.tts_voice_rotation), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SettingsCategory {
                    RoutingEntry(stringResource(R.string.tts_voice_plan), null, onPlan)
                    RoutingEntry(stringResource(R.string.tts_sources), null, onSources)
                }
            }
            state.error?.let { error -> item { RoutingError(error, onSources) } }
        }
    }
}

@Composable
private fun candidatesLabel(voices: List<SpeechVoiceRef>, configured: Boolean): String =
    if (voices.isEmpty()) stringResource(if (configured) R.string.tts_voice_none else R.string.tts_voice_inherit)
    else voices.joinToString(" · ") { it.label }

@Composable
private fun RoutingEntry(title: String, option: String?, onClick: () -> Unit) {
    SettingsClickableEntry(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        title = title, option = option,
        trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
        onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechVoicePickerScreen(state: SpeechVoiceRoutingState, dialogue: Boolean, person: String?,
    onBack: () -> Unit, onSelect: (SpeechVoiceRef?) -> Unit, onSources: () -> Unit, onPlaybackPreferences: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val assigned = person?.let(state.settings.speakerVoices::get)
        .takeIf { state.settings.speakerBookId == state.plan?.chapter?.bookId }
    val selected = if (person != null) listOfNotNull(assigned)
        else if (dialogue) state.settings.dialogueVoices else state.settings.narrationVoices
    val available = state.choices.map { it.key }.toSet()
    val other = if (dialogue) state.settings.narrationVoices else state.settings.dialogueVoices
    val enabled = person != null || !dialogue || state.settings.text.dialogue
    val voices = (selected + state.choices).distinctBy { it.key }
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    val title = if (person != null) R.string.tts_assign_voice else if (dialogue) R.string.tts_dialogue_voices else R.string.tts_narration_voices
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), title, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-voice-picker"), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (person != null) Text(person, style = MaterialTheme.typography.titleLarge)
                    else Text(stringResource(R.string.tts_voice_rotation), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReplacementField(query, { query = it }, stringResource(R.string.tts_voice_search), singleLine = true)
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) }
            if (person == null && selected.isEmpty() && state.settings.voiceRoutingConfigured) item {
                Text(stringResource(R.string.tts_voice_none), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (!enabled) item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.tts_voice_enable_dialogue), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onPlaybackPreferences) { Text(stringResource(R.string.tts_playback_preferences)) }
                }
            }
            if (person != null) item {
                Row(Modifier.fillMaxWidth().selectable(assigned == null, role = Role.RadioButton, onClick = { onSelect(null) })
                    .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(assigned == null, onClick = null)
                    Text(stringResource(R.string.tts_voice_use_dialogue), Modifier.padding(start = 16.dp))
                }
            }
            items(voices, key = { it.key }) { voice ->
                val index = selected.indexOfFirst { it.key == voice.key }
                val checked = index >= 0
                val missing = voice.key !in available
                val action = if (person == null) Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox) { onSelect(voice) }
                    else Modifier.selectable(checked, enabled = !missing, role = Role.RadioButton) { onSelect(voice) }
                Row(Modifier.fillMaxWidth().then(action).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (person == null) Checkbox(checked, onCheckedChange = null, enabled = enabled)
                    else RadioButton(checked, onClick = null, enabled = !missing)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(voice.label, style = MaterialTheme.typography.bodyLarge)
                        if (missing) Text(stringResource(R.string.tts_voice_unavailable), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        else if (person == null && other.any { it.key == voice.key }) Text(
                            stringResource(if (dialogue) R.string.tts_voice_from_narration else R.string.tts_voice_from_dialogue),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (checked && person == null) Text((index + 1).toString(), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (voices.isEmpty() && !state.loading) item {
                Text(stringResource(R.string.search_no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { TextButton(onSources, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.tts_sources)) } }
            state.error?.let { error -> item { RoutingError(error, onSources) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechVoicePlanScreen(state: SpeechVoiceRoutingState, onBack: () -> Unit, onPerson: (String) -> Unit,
    onSources: () -> Unit) {
    val plan = state.plan
    val people = plan?.segments?.mapNotNull { it.speakerHint }?.distinct().orEmpty()
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_voice_plan, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-voice-plan"), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (state.preparing) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
            state.error?.let { error -> item { RoutingError(error, onSources) } }
            if (plan == null && !state.preparing && state.error == null) item {
                Text(stringResource(R.string.tts_voice_plan_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (plan != null) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(plan.chapter.bookTitle, style = MaterialTheme.typography.titleMedium)
                        Text(plan.chapter.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (people.isNotEmpty()) item {
                    SettingsCategory(title = stringResource(R.string.tts_voice_explicit_people)) {
                        people.forEach { person ->
                            val voice = state.settings.speakerVoices[person].takeIf { state.settings.speakerBookId == plan.chapter.bookId }
                            RoutingEntry(person, voice?.label ?: stringResource(R.string.tts_voice_use_dialogue), { onPerson(person) })
                        }
                    }
                }
                itemsIndexed(plan.segments) { index, segment ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.tts_voice_segment, index + 1, segment.selectedVoice?.label.orEmpty()),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(segment.voiceReason.resource), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(segment.text, style = MaterialTheme.typography.bodyLarge)
                        segment.selectedVoice?.error?.let { error ->
                            Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onSources) { Text(stringResource(R.string.tts_sources)) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private val SpeechVoiceReason.resource: Int get() = when (this) {
    SpeechVoiceReason.Narration -> R.string.tts_voice_reason_narration
    SpeechVoiceReason.Dialogue -> R.string.tts_voice_reason_dialogue
    SpeechVoiceReason.Explicit -> R.string.tts_voice_reason_explicit
    SpeechVoiceReason.Remembered -> R.string.tts_voice_reason_remembered
    SpeechVoiceReason.Continued -> R.string.tts_voice_reason_continued
}

@Composable
private fun RoutingError(error: SpeechError, onSources: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onSources) { Text(stringResource(R.string.tts_sources)) }
    }
}
