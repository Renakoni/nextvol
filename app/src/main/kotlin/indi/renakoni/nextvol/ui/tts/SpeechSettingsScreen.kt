package indi.renakoni.nextvol.ui.tts

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import indi.renakoni.nextvol.tts.SpeechVoiceSelection
import indi.renakoni.nextvol.tts.labelResource
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.selectedNarrators
import indi.renakoni.nextvol.tts.voiceReference
import indi.renakoni.nextvol.tts.voiceSelection
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsScreen(
    state: SpeechSettingsUiState,
    playback: ReadAloudState,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onPreferences: () -> Unit,
    onCommand: (SpeechAction) -> Unit,
    onEngines: (() -> Unit)? = null,
    onPlaybackPreferences: (() -> Unit)? = null,
    onSleepTimer: ((Int?) -> Unit)? = null,
    onChapterSleepTimer: ((Int) -> Unit)? = null,
    onReplacements: (() -> Unit)? = null,
    onRoles: (() -> Unit)? = null,
    onVoicePool: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
    onRuntimes: (() -> Unit)? = null,
    schemeTitle: String? = null,
    schemeUsage: String? = null,
    onSchemes: (() -> Unit)? = null,
    schemeSummary: String? = null,
    onBackgroundMusic: (() -> Unit)? = null,
) {
    var showPlayback by remember { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = schemeTitle ?: stringResource(R.string.tts_settings),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(id = R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
        bottomBar = {
            if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("speech-settings-list"),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            schemeUsage?.let { usage ->
                item(key = "scheme_usage") {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = usage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            item(key = "speech_core_group") {
                SettingsCategory(title = stringResource(R.string.tts_preferences)) {
                    val selectedSource = state.httpSources.find { it.definition.id == state.settings.httpSource }
                    val engine = if (state.settings.engine.isEmpty()) stringResource(R.string.tts_system_default)
                    else state.engines.find { it.packageName == state.settings.engine }?.name ?: state.settings.engine
                    val legacyVoice = if (state.settings.offlineVoice != null) {
                        state.offlineName ?: stringResource(R.string.tts_voice_unavailable)
                    } else if (state.settings.httpSource == null) {
                        listOf(stringResource(R.string.tts_system_speech), engine, state.settings.voice)
                            .filter(String::isNotEmpty)
                            .joinToString(" · ")
                    } else {
                        selectedSource?.definition?.name ?: stringResource(R.string.tts_voice_unavailable)
                    }
                    val current = state.settings.selectedNarrators.joinToString("、") { voice ->
                        voice.label.ifBlank {
                            if (voice.key == state.settings.voiceReference().key) legacyVoice else voice.voice.ifBlank { engine }
                        }
                    }.ifEmpty { stringResource(R.string.tts_voice_none) }
                    SettingsClickableEntry(
                        modifier = Modifier.testTag("speech-sources-entry"),
                        painter = painterResource(R.drawable.headphones_24px),
                        title = stringResource(R.string.tts_sources),
                        option = current,
                        onClick = onSources,
                    )
                    SettingsClickableEntry(
                        modifier = Modifier.testTag("speech-preferences-entry"),
                        painter = painterResource(R.drawable.outline_settings_24px),
                        title = stringResource(R.string.tts_preferences),
                        description = stringResource(R.string.tts_preferences_description),
                        onClick = onPreferences,
                    )
                    onSchemes?.let { open ->
                        SettingsClickableEntry(
                            painter = painterResource(R.drawable.view_list_24px),
                            title = stringResource(R.string.tts_schemes),
                            option = schemeSummary,
                            onClick = open,
                        )
                    }
                    onPlaybackPreferences?.let { open ->
                        SettingsClickableEntry(
                            painter = painterResource(R.drawable.play_arrow_24px),
                            title = stringResource(R.string.tts_playback_preferences),
                            description = stringResource(R.string.tts_playback_preferences_description),
                            onClick = open,
                        )
                    }
                    onBackgroundMusic?.let { open ->
                        SettingsClickableEntry(
                            painter = painterResource(R.drawable.music_note_24px),
                            title = stringResource(R.string.tts_background_music),
                            description = stringResource(R.string.tts_music_description),
                            onClick = open,
                        )
                    }
                }
            }
            if (onReplacements != null || onRoles != null || onVoicePool != null) {
                item(key = "speech_rules_group") {
                    SettingsCategory(title = stringResource(R.string.tts_roles)) {
                        onRoles?.let { open ->
                            SettingsClickableEntry(
                                modifier = Modifier.testTag("speech-roles-entry"),
                                painter = painterResource(R.drawable.person_edit_24px),
                                title = stringResource(R.string.tts_roles),
                                description = stringResource(R.string.tts_roles_description),
                                onClick = open,
                            )
                        }
                        onVoicePool?.let { open ->
                            SettingsClickableEntry(
                                modifier = Modifier.testTag("speech-voice-pool-entry"),
                                painter = painterResource(R.drawable.tts_text_multiple_voices),
                                title = stringResource(R.string.tts_pool_title),
                                description = stringResource(R.string.tts_pool_description),
                                onClick = open,
                            )
                        }
                        onReplacements?.let { open ->
                            SettingsClickableEntry(
                                modifier = Modifier.testTag("speech-replacements-entry"),
                                painter = painterResource(R.drawable.find_replace_24px),
                                title = stringResource(R.string.tts_replacements),
                                description = stringResource(R.string.tts_replacements_description),
                                onClick = open,
                            )
                        }
                    }
                }
            }
            if (onEngines != null || onDiagnostics != null || onRuntimes != null) {
                item(key = "speech_engines_group") {
                    SettingsCategory(title = stringResource(R.string.tts_engines)) {
                        onEngines?.let { open ->
                            SettingsClickableEntry(
                                modifier = Modifier.testTag("speech-engines-entry"),
                                painter = painterResource(R.drawable.settings_applications_24px),
                                title = stringResource(R.string.tts_engines),
                                description = stringResource(R.string.tts_engines_description),
                                onClick = open,
                            )
                        }
                        onRuntimes?.let { open ->
                            SettingsClickableEntry(
                                painter = painterResource(R.drawable.cloud_download_24px),
                                title = stringResource(R.string.tts_runtime_packs),
                                description = stringResource(R.string.tts_runtime_packs_summary),
                                onClick = open,
                            )
                        }
                        onDiagnostics?.let { open ->
                            SettingsClickableEntry(
                                modifier = Modifier.testTag("speech-diagnostics-entry"),
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
    }
    if (showPlayback && hasPlayback) {
        ReadAloudSheet(
            state = playback,
            onCommand = onCommand,
            onSettings = null,
            onDismiss = { showPlayback = false },
            onSleepTimer = onSleepTimer,
            onChapterSleepTimer = onChapterSleepTimer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPreferencesScreen(
    state: SpeechSettingsUiState,
    onBack: () -> Unit,
    onRate: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onSchemes: (() -> Unit)? = null,
    schemeSummary: String? = null,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_preferences),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(id = R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("speech-preferences-list"),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.takeIf { it == SpeechError.Storage }?.let { error ->
                item(key = "error") {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(
                            text = stringResource(error.messageResource),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item(key = "voice_tuning_card") {
                EditorialCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    SpeechTextSlider(
                        icon = R.drawable.tts_speed,
                        title = R.string.tts_voice_speed,
                        value = (state.settings.rate * 50f).roundToInt(),
                        valueRange = 0f..100f,
                    ) { onRate(it / 50f) }
                    EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    SpeechTextSlider(
                        icon = R.drawable.tts_volume,
                        title = R.string.tts_voice_volume,
                        value = (state.settings.volume * 50f).roundToInt(),
                        valueRange = 0f..100f,
                    ) { onVolume(it / 50f) }
                    EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    SpeechTextSlider(
                        icon = R.drawable.tts_pitch,
                        title = R.string.tts_pitch,
                        value = ((state.settings.pitch * 100f - 50f) / 2f).roundToInt(),
                        valueRange = 0f..100f,
                    ) { onPitch((it * 2f + 50f) / 100f) }
                }
            }
            onSchemes?.let { open ->
                item(key = "schemes") {
                    SettingsCategory(title = stringResource(R.string.tts_schemes)) {
                        SettingsClickableEntry(
                            modifier = Modifier.testTag("speech-schemes-entry"),
                            painter = painterResource(R.drawable.view_list_24px),
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
    onEngine: (String) -> Unit,
    onVoice: (String) -> Unit,
    onSystemSettings: () -> Unit,
    onPreviewVoice: ((String) -> Unit)? = null,
    onDialogue: ((String) -> Unit)? = null,
    playback: ReadAloudState = ReadAloudState(),
) {
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val name = if (state.settings.engine.isEmpty()) stringResource(R.string.tts_system_default)
    else state.engines.find { it.packageName == state.settings.engine }?.name ?: state.settings.engine
    val voice = (state.settings.selectedNarrators + state.settings.dialogueVoices)
        .filter { it.httpSource == null && it.offlineVoice == null && it.engine == state.settings.engine }
        .joinToString("、") { it.voice.ifBlank { name } }
        .ifEmpty { stringResource(R.string.tts_voice_none) }

    EditorialCard(modifier = Modifier.fillMaxWidth()) {
        SpeechSetting(stringResource(R.string.tts_engine), name) { choice = "engine" }
        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
        SpeechSetting(stringResource(R.string.tts_voice), voice, enabled = !state.loading) { choice = "voice" }
        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
        SpeechSetting(stringResource(R.string.tts_system_settings), onClick = onSystemSettings)
    }

    state.error?.let { error ->
        Text(
            text = stringResource(error.messageResource),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (choice != null) {
        val engine = choice == "engine"
        val current = if (engine) state.settings.engine else state.settings.voice
        val options = if (engine) {
            listOf(Choice("", stringResource(R.string.tts_system_default))) +
                state.engines.map { Choice(it.packageName, it.name) }
        } else {
            listOf(Choice("", stringResource(R.string.tts_engine_default))) + state.voices.sortedBy {
                if (Locale.forLanguageTag(it.locale).language == locale.language) 0 else 1
            }.map {
                Choice(
                    id = it.id,
                    title = Locale.forLanguageTag(it.locale).getDisplayName(locale),
                    description = it.id + " · " + stringResource(
                        when {
                            !it.installed -> R.string.tts_voice_not_installed
                            it.needsNetwork -> R.string.tts_network_voice
                            else -> R.string.tts_local_voice
                        },
                    ),
                    enabled = it.installed,
                )
            }
        }
        SpeechChoiceDialog(
            title = stringResource(if (engine) R.string.tts_engine else R.string.tts_voice),
            current = current,
            options = options,
            onChoose = {
                if (engine) {
                    onEngine(it)
                    choice = null
                } else {
                    onVoice(it)
                }
            },
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
    val tokens = NextVolDesign.tokens
    Surface(
        color = tokens.cardElevatedSurface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            EditorialHairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.headphones_24px),
                    size = 38.dp,
                    iconSize = 19.dp,
                    cornerRadius = 11.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.playbackTitle(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.phase == SpeechPhase.Playing && state.chapterTitle.isNotEmpty()) {
                            state.chapterTitle
                        } else {
                            stringResource(state.phase.labelResource)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SpeechPlayPauseButton(state, onCommand, Modifier.size(44.dp))
            }
        }
    }
}

private data class Choice(val id: String, val title: String, val description: String = "", val enabled: Boolean = true)

@Composable
private fun SpeechChoiceDialog(
    title: String,
    current: String,
    options: List<Choice>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onPreview: ((String) -> Unit)? = null,
    selection: ((String) -> SpeechVoiceSelection?)? = null,
    onDialogue: ((String) -> Unit)? = null,
    previewPlaying: (String) -> Boolean = { false },
) {
    val tokens = NextVolDesign.tokens
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = { Text(title, fontWeight = FontWeight.W600) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(options, key = { it.id }) { option ->
                    if (selection != null) {
                        val selected = selection(option.id)
                        SpeechSourceRow(
                            name = option.title,
                            selected = selected != null,
                            enabled = option.enabled || selected != null,
                            onClick = { onChoose(option.id) },
                            onLongClick = onDialogue?.let { { it(option.id) } },
                            selection = selected,
                            avatar = {
                                EditorialIconBadge(
                                    painter = painterResource(R.drawable.headphones_24px),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                )
                            },
                            onPreview = null,
                            description = option.description,
                            actions = {
                                if (onPreview != null) {
                                    SpeechPreviewButton(
                                        name = option.id.ifEmpty { option.title },
                                        enabled = option.enabled,
                                        playing = previewPlaying(option.id),
                                    ) { onPreview(option.id) }
                                }
                            },
                        )
                    } else {
                        val isSelected = option.id == current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .squircleBackground(
                                    color = if (isSelected) tokens.activePillFill else MaterialTheme.colorScheme.surfaceContainerLow,
                                    cornerRadius = 14.dp,
                                )
                                .squircleBorder(
                                    width = if (isSelected) 1.25.dp else 0.75.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                                    cornerRadius = 14.dp,
                                )
                                .squircleClip(14.dp)
                                .selectable(
                                    selected = isSelected,
                                    enabled = option.enabled,
                                    role = Role.RadioButton,
                                    onClick = { onChoose(option.id) },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected = isSelected, onClick = null, enabled = option.enabled)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.W600 else FontWeight.W400,
                                )
                                if (option.description.isNotEmpty()) {
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (onPreview != null) {
                                SpeechPreviewButton(
                                    name = option.id.ifEmpty { option.title },
                                    enabled = option.enabled,
                                    playing = previewPlaying(option.id),
                                ) { onPreview(option.id) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun SpeechPreviewButton(name: String, enabled: Boolean, playing: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(if (playing) R.drawable.pause_24px else R.drawable.play_arrow_24px),
            contentDescription = stringResource(if (playing) R.string.tts_pause_preview_voice else R.string.tts_preview_voice, name),
            tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeechSetting(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W500,
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
