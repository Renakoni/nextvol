package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.labelResource
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import indi.renakoni.nextvol.tts.SpeechVoiceSelection
import indi.renakoni.nextvol.tts.voiceSelection
import indi.renakoni.nextvol.tts.selectedNarrators
import indi.renakoni.nextvol.tts.voiceReference
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsScreen(
    state: SpeechSettingsUiState, playback: ReadAloudState,
    onBack: () -> Unit, onSources: () -> Unit, onPreferences: () -> Unit,
    onCommand: (SpeechAction) -> Unit,
    onEngines: (() -> Unit)? = null,
    onPlaybackPreferences: (() -> Unit)? = null,
    onSleepTimer: ((Int?) -> Unit)? = null,
    onChapterSleepTimer: ((Int) -> Unit)? = null,
    onReplacements: (() -> Unit)? = null,
    onRoles: (() -> Unit)? = null,
    onVoicePool: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
    schemeTitle: String? = null,
    schemeUsage: String? = null,
) {
    var showPlayback by remember { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    Scaffold(
        topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_settings, onBack, titleText = schemeTitle) },
        bottomBar = {
            if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-settings-list"),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            schemeUsage?.let { usage -> item {
                Text(usage, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item {
                SettingsCategory {
                    SettingsClickableEntry(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-preferences-entry"),
                        painter = painterResource(R.drawable.outline_settings_24px),
                        title = stringResource(R.string.tts_preferences),
                        description = stringResource(R.string.tts_preferences_description),
                        onClick = onPreferences,
                    )
                    val selectedSource = state.httpSources.find { it.definition.id == state.settings.httpSource }
                    val engine = if (state.settings.engine.isEmpty()) stringResource(R.string.tts_system_default)
                        else state.engines.find { it.packageName == state.settings.engine }?.name ?: state.settings.engine
                    val legacyVoice = if (state.settings.offlineVoice != null) state.offlineName ?: stringResource(R.string.tts_voice_unavailable)
                        else if (state.settings.httpSource == null)
                        listOf(stringResource(R.string.tts_system_speech), engine, state.settings.voice).filter(String::isNotEmpty).joinToString(" · ")
                        else selectedSource?.definition?.name ?: stringResource(R.string.tts_voice_unavailable)
                    val current = state.settings.selectedNarrators.joinToString("、") { voice -> voice.label.ifBlank {
                        if (voice.key == state.settings.voiceReference().key) legacyVoice else voice.voice.ifBlank { engine }
                    } }.ifEmpty { stringResource(R.string.tts_voice_none) }
                    SettingsClickableEntry(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-sources-entry"),
                        painter = painterResource(R.drawable.headphones_24px),
                        title = stringResource(R.string.tts_sources), option = current,
                        onClick = onSources,
                    )
                    onPlaybackPreferences?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                            painter = painterResource(R.drawable.play_arrow_24px),
                            title = stringResource(R.string.tts_playback_preferences),
                            description = stringResource(R.string.tts_playback_preferences_description),
                            onClick = open,
                        )
                    }
                }
            }
            item {
                SettingsCategory {
                    onReplacements?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-replacements-entry"),
                            painter = painterResource(R.drawable.find_replace_24px),
                            title = stringResource(R.string.tts_replacements),
                            description = stringResource(R.string.tts_replacements_description),
                            onClick = open,
                        )
                    }
                    onRoles?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-roles-entry"),
                            painter = painterResource(R.drawable.person_edit_24px),
                            title = stringResource(R.string.tts_roles),
                            description = stringResource(R.string.tts_roles_description),
                            onClick = open,
                        )
                    }
                    onVoicePool?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-voice-pool-entry"),
                            painter = painterResource(R.drawable.tts_text_multiple_voices),
                            title = stringResource(R.string.tts_pool_title),
                            description = stringResource(R.string.tts_pool_description),
                            onClick = open,
                        )
                    }
                }
            }
            if (onEngines != null || onDiagnostics != null) item {
                SettingsCategory {
                    onEngines?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-engines-entry"),
                            painter = painterResource(R.drawable.settings_applications_24px),
                            title = stringResource(R.string.tts_engines),
                            description = stringResource(R.string.tts_engines_description),
                            onClick = open,
                        )
                    }
                    onDiagnostics?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-diagnostics-entry"),
                            painter = painterResource(R.drawable.article_24px),
                            title = stringResource(R.string.tts_diagnostics),
                            description = stringResource(R.string.tts_diagnostics_description),
                            onClick = open,
                        )
                    }
                }
            }
        }
    }
    if (showPlayback && hasPlayback) ReadAloudSheet(playback, onCommand, onSettings = null,
        onDismiss = { showPlayback = false }, onSleepTimer = onSleepTimer, onChapterSleepTimer = onChapterSleepTimer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPreferencesScreen(
    state: SpeechSettingsUiState,
    onBack: () -> Unit,
    onRate: (Float) -> Unit, onVolume: (Float) -> Unit, onPitch: (Float) -> Unit,
    onSchemes: (() -> Unit)? = null,
    schemeSummary: String? = null,
) {
    Scaffold(
        topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_preferences, onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("speech-preferences-list"),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            state.error?.takeIf { it == SpeechError.Storage }?.let { error ->
                item(key = "error") {
                    Text(stringResource(error.messageResource), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "speed") {
                SpeechTextSlider(R.drawable.tts_speed, R.string.tts_voice_speed,
                    (state.settings.rate * 50f).roundToInt(), 0f..100f) { onRate(it / 50f) }
            }
            item(key = "volume") {
                SpeechTextSlider(R.drawable.tts_volume, R.string.tts_voice_volume,
                    (state.settings.volume * 50f).roundToInt(), 0f..100f) { onVolume(it / 50f) }
            }
            item(key = "pitch") {
                SpeechTextSlider(R.drawable.tts_pitch, R.string.tts_pitch,
                    ((state.settings.pitch * 100f - 50f) / 2f).roundToInt(), 0f..100f) { onPitch((it * 2f + 50f) / 100f) }
            }
            onSchemes?.let { open ->
                item(key = "schemes") {
                    SettingsCategory {
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-schemes-entry"),
                            painter = painterResource(R.drawable.headphones_24px),
                            title = stringResource(R.string.tts_schemes),
                            option = schemeSummary,
                            onClick = open,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SystemSpeechSettings(
    state: SpeechSettingsUiState,
    onEngine: (String) -> Unit, onVoice: (String) -> Unit, onSystemSettings: () -> Unit,
    onPreviewVoice: ((String) -> Unit)? = null,
    onDialogue: ((String) -> Unit)? = null,
    playback: ReadAloudState = ReadAloudState(),
) {
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val name = if (state.settings.engine.isEmpty()) stringResource(R.string.tts_system_default)
        else state.engines.find { it.packageName == state.settings.engine }?.name ?: state.settings.engine
    SpeechSetting(stringResource(R.string.tts_engine), name) { choice = "engine" }
    val voice = (state.settings.selectedNarrators + state.settings.dialogueVoices)
            .filter { it.httpSource == null && it.offlineVoice == null && it.engine == state.settings.engine }
            .joinToString("、") { it.voice.ifBlank { name } }.ifEmpty { stringResource(R.string.tts_voice_none) }
    SpeechSetting(stringResource(R.string.tts_voice), voice, enabled = !state.loading) { choice = "voice" }
    SpeechSetting(stringResource(R.string.tts_system_settings), onClick = onSystemSettings)
    state.error?.let { error ->
        Text(stringResource(error.messageResource), Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    if (choice != null) {
        val engine = choice == "engine"
        val current = if (engine) state.settings.engine else state.settings.voice
        val options = if (engine) {
            listOf(Choice("", stringResource(R.string.tts_system_default))) + state.engines.map { Choice(it.packageName, it.name) }
        } else {
            listOf(Choice("", stringResource(R.string.tts_engine_default))) + state.voices.sortedBy {
                if (Locale.forLanguageTag(it.locale).language == locale.language) 0 else 1
            }.map {
                Choice(
                    it.id, Locale.forLanguageTag(it.locale).getDisplayName(locale),
                    it.id + " · " + stringResource(when {
                        !it.installed -> R.string.tts_voice_not_installed
                        it.needsNetwork -> R.string.tts_network_voice
                        else -> R.string.tts_local_voice
                    }),
                    it.installed,
                )
            }
        }
        SpeechChoiceDialog(
            stringResource(if (engine) R.string.tts_engine else R.string.tts_voice), current, options,
            onChoose = { if (engine) { onEngine(it); choice = null } else onVoice(it) },
            onDismiss = { choice = null },
            onPreview = if (engine) null else onPreviewVoice,
            previewPlaying = { id -> playback.isActive && playback.previews(SpeechVoiceRef(engine = state.settings.engine, voice = id)) },
            selection = if (engine) null else { id -> state.settings.voiceSelection(SpeechVoiceRef(engine = state.settings.engine, voice = id)) },
            onDialogue = if (engine) null else onDialogue,
        )
    }
}

@Composable
internal fun SpeechPlaybackBar(state: ReadAloudState, onCommand: (SpeechAction) -> Unit, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    state.playbackTitle(),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.phase == SpeechPhase.Playing && state.chapterTitle.isNotEmpty()) state.chapterTitle
                    else stringResource(state.phase.labelResource),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            SpeechPlayPauseButton(state, onCommand, Modifier.size(48.dp))
        }
    }
}

private data class Choice(val id: String, val title: String, val description: String = "", val enabled: Boolean = true)

@Composable
private fun SpeechChoiceDialog(
    title: String, current: String, options: List<Choice>, onChoose: (String) -> Unit, onDismiss: () -> Unit,
    onPreview: ((String) -> Unit)? = null,
    selection: ((String) -> SpeechVoiceSelection?)? = null,
    onDialogue: ((String) -> Unit)? = null,
    previewPlaying: (String) -> Boolean = { false },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).selectableGroup()) {
                items(options, key = { it.id }) { option ->
                    if (selection != null) {
                        val selected = selection(option.id)
                        SpeechSourceRow(option.title, selected != null, option.enabled || selected != null,
                            onClick = { onChoose(option.id) }, onLongClick = onDialogue?.let { { it(option.id) } }, selection = selected,
                            avatar = { Icon(painterResource(R.drawable.headphones_24px), null, Modifier.size(40.dp)) },
                            onPreview = null, description = option.description, actions = {
                                if (onPreview != null) SpeechPreviewButton(option.id.ifEmpty { option.title }, option.enabled,
                                    previewPlaying(option.id)) { onPreview(option.id) }
                            })
                    } else {
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = option.id == current, enabled = option.enabled, role = Role.RadioButton,
                            onClick = { onChoose(option.id) },
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option.id == current, onClick = null, enabled = option.enabled)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(option.title, style = MaterialTheme.typography.bodyLarge)
                            if (option.description.isNotEmpty()) Text(
                                option.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (onPreview != null) SpeechPreviewButton(option.id.ifEmpty { option.title }, option.enabled,
                            previewPlaying(option.id)) { onPreview(option.id) }
                    }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun SpeechPreviewButton(name: String, enabled: Boolean, playing: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(if (playing) R.drawable.pause_24px else R.drawable.play_arrow_24px),
            stringResource(if (playing) R.string.tts_pause_preview_voice else R.string.tts_preview_voice, name))
    }
}

@Composable
private fun SpeechSetting(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (!value.isNullOrEmpty()) Text(
                value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
