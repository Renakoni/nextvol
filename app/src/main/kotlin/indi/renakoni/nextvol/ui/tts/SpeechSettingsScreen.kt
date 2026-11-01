package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.labelResource
import indi.renakoni.nextvol.tts.messageResource
import java.util.Locale

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
) {
    var showPlayback by remember { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    Scaffold(
        topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_settings, onBack) },
        bottomBar = {
            if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-settings-list"),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                SettingsCategory {
                    SettingsClickableEntry(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-preferences-entry"),
                        painter = painterResource(R.drawable.outline_settings_24px),
                        title = stringResource(R.string.tts_preferences),
                        trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                        onClick = onPreferences,
                    )
                    onPlaybackPreferences?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                            painter = painterResource(R.drawable.outline_settings_24px),
                            title = stringResource(R.string.tts_playback_preferences),
                            trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                            onClick = open,
                        )
                    }
                    val selectedSource = state.httpSources.find { it.definition.id == state.settings.httpSource }
                    val engine = if (state.settings.engine.isEmpty()) stringResource(R.string.tts_system_default)
                        else state.engines.find { it.packageName == state.settings.engine }?.name ?: state.settings.engine
                    val current = if (state.settings.offlineVoice != null) state.offlineName ?: stringResource(R.string.tts_voice_unavailable)
                        else if (state.settings.httpSource == null)
                        listOf(stringResource(R.string.tts_system_speech), engine, state.settings.voice).filter(String::isNotEmpty).joinToString(" · ")
                        else selectedSource?.definition?.name ?: stringResource(R.string.tts_voice_unavailable)
                    SettingsClickableEntry(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-sources-entry"),
                        painter = painterResource(R.drawable.headphones_24px),
                        title = stringResource(R.string.tts_sources), option = current,
                        trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                        onClick = onSources,
                    )
                    onEngines?.let { open ->
                        SettingsClickableEntry(
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag("speech-engines-entry"),
                            painter = painterResource(R.drawable.outline_settings_24px),
                            title = stringResource(R.string.tts_engines),
                            trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
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
    state: SpeechSettingsUiState, playback: ReadAloudState,
    onBack: () -> Unit,
    onRate: (Float?) -> Unit, onPitch: (Float?) -> Unit,
    onCommand: (SpeechAction) -> Unit,
    onPlaybackSpeed: (Float) -> Unit,
    onSleepTimer: ((Int?) -> Unit)? = null,
    onChapterSleepTimer: ((Int) -> Unit)? = null,
) {
    var showPlayback by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    Scaffold(
        topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_preferences, onBack) },
        bottomBar = {
            if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
        },
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
            item(key = "playback-speed") {
                SettingsCategory(title = stringResource(R.string.tts_playback_settings)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        SpeechParameter(stringResource(R.string.tts_playback_speed), state.settings.playbackSpeed,
                            { onPlaybackSpeed(it ?: 1f) }, resetValue = 1f)
                    }
                }
            }
            item(key = "advanced") {
                SettingsCategory(title = stringResource(R.string.tts_synthesis_settings)) {
                    SettingsClickableEntry(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                        title = stringResource(if (showAdvanced) R.string.tts_hide_advanced else R.string.tts_advanced),
                        trailingContent = {
                            Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null,
                                Modifier.rotate(if (showAdvanced) 0f else 180f))
                        },
                        onClick = { showAdvanced = !showAdvanced },
                    )
                    if (showAdvanced) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SpeechParameter(stringResource(R.string.tts_rate), state.settings.rate, onRate,
                                    defaultLabel = stringResource(if (state.settings.httpSource == null)
                                        R.string.tts_engine_default else R.string.tts_source_default))
                                Text(stringResource(R.string.tts_rate_description), Modifier.padding(horizontal = 20.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.settings.httpSource == null && state.settings.offlineVoice == null) {
                                    SpeechParameter(stringResource(R.string.tts_pitch), state.settings.pitch, onPitch)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showPlayback && hasPlayback) ReadAloudSheet(
        playback,
        onCommand = onCommand,
        onSettings = null,
        onDismiss = { showPlayback = false },
        onSleepTimer = onSleepTimer,
        onChapterSleepTimer = onChapterSleepTimer,
    )
}

@Composable
internal fun SystemSpeechSettings(
    state: SpeechSettingsUiState,
    onEngine: (String) -> Unit, onVoice: (String) -> Unit, onSystemSettings: () -> Unit,
    onPreviewVoice: ((String) -> Unit)? = null,
) {
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val name = if (state.settings.engine.isEmpty()) stringResource(R.string.tts_system_default)
        else state.engines.find { it.packageName == state.settings.engine }?.name ?: state.settings.engine
    SpeechSetting(stringResource(R.string.tts_engine), name) { choice = "engine" }
    val voice = if (state.settings.voice.isEmpty()) stringResource(R.string.tts_engine_default)
        else state.voices.find { it.id == state.settings.voice }?.let {
            Locale.forLanguageTag(it.locale).getDisplayName(locale) + " · " + it.id
        } ?: stringResource(R.string.tts_voice_unavailable)
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
            onChoose = { if (engine) onEngine(it) else onVoice(it); choice = null },
            onDismiss = { choice = null },
            onPreview = if (engine) null else onPreviewVoice,
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
                    state.bookTitle.ifEmpty {
                        stringResource(if (state.request?.isPreview == true) R.string.tts_preview else R.string.tts_title)
                    },
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).selectableGroup()) {
                items(options, key = { it.id }) { option ->
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
                        if (onPreview != null) SpeechPreviewButton(option.id.ifEmpty { option.title }, option.enabled) { onPreview(option.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun SpeechPreviewButton(name: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(R.drawable.play_arrow_24px), stringResource(R.string.tts_preview_voice, name))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechParameter(title: String, value: Float?, onChange: (Float?) -> Unit,
    defaultLabel: String = stringResource(R.string.tts_engine_default), resetValue: Float? = null) {
    var slider by remember(value) { mutableFloatStateOf(value ?: 1f) }
    var edited by remember(value) { mutableStateOf(false) }
    val description = if (value == null && !edited) defaultLabel
        else stringResource(R.string.tts_factor, slider)
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.onSurface,
        activeTrackColor = MaterialTheme.colorScheme.onSurface,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    )
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (value != resetValue || edited) TextButton(onClick = { onChange(resetValue); slider = 1f; edited = false }) {
                Text(stringResource(R.string.tts_reset))
            }
        }
        Slider(
            value = slider, onValueChange = { slider = it; edited = true },
            onValueChangeFinished = { onChange(slider) }, valueRange = 0.5f..2f, steps = 14,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title; stateDescription = description },
            colors = colors,
            thumb = { Surface(Modifier.size(20.dp), shape = CircleShape, color = MaterialTheme.colorScheme.onSurface) {} },
            track = {
                SliderDefaults.Track(
                    it, Modifier.height(4.dp), colors = colors, thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 2.dp, drawStopIndicator = null,
                )
            },
        )
    }
}
