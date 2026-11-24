package indi.renakoni.nextvol.ui.tts

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.setting.AbstractSettingState
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechAudioSettings
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechNetworkCheck
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechTextSettings
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.names.SpeechNewWordDiscovery
import indi.renakoni.nextvol.tts.sovits.DeviceAcceleration
import indi.renakoni.nextvol.tts.sovits.SovitsAccelerationStatus
import indi.renakoni.nextvol.ui.components.MenuOptions
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.inject.Inject
import kotlin.math.roundToInt

data class SpeechPlaybackSettingsState(
    val settings: SpeechTextSettings? = null,
    val allowMultipleVoices: Boolean = false,
    val autoAssignRole: Boolean = false,
    val maxCacheVoiceNum: Int = 50,
    val roleContextPattern: String = "__ROLE__",
    val defaultNamePattern: String? = null,
    val error: SpeechError? = null,
    val engineCacheCapacity: Int = 10,
    val audio: SpeechAudioSettings = SpeechAudioSettings(),
    val backgroundMusicEnabled: Boolean = false,
    val diagnosticLogging: Boolean = false,
    val acceleration: SovitsAccelerationStatus = SovitsAccelerationStatus(),
)

@HiltViewModel
class SpeechPlaybackSettingsViewModel @Inject constructor(
    private val repository: SpeechSettingsEditor,
    userData: UserDataRepository,
    newWords: SpeechNewWordDiscovery,
) : ViewModel() {
    val preferences = SpeechPlaybackPreferences(userData, viewModelScope)
    private val mutableState = MutableStateFlow(SpeechPlaybackSettingsState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            DeviceAcceleration.status.collect { status -> mutableState.update { it.copy(acceleration = status) } }
        }
        viewModelScope.launch {
            repository.changes.collect { settings -> mutableState.update {
                it.copy(settings = settings.text, allowMultipleVoices = settings.allowMultipleVoices,
                    engineCacheCapacity = settings.engineCacheCapacity,
                    audio = settings.audio,
                    backgroundMusicEnabled = settings.backgroundMusic.enabled,
                    diagnosticLogging = settings.diagnosticLogging,
                    autoAssignRole = settings.autoAssignRole, maxCacheVoiceNum = settings.maxCacheVoiceNum,
                    roleContextPattern = settings.roleContextPattern ?: "__ROLE__")
            } }
        }
        viewModelScope.launch {
            try {
                val pattern = newWords.defaultNamePattern()
                mutableState.update { it.copy(defaultNamePattern = pattern) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }

    fun update(change: (SpeechTextSettings) -> SpeechTextSettings) = updateSettings { it.copy(text = change(it.text)) }

    fun allowMultipleVoices(enabled: Boolean) = updateSettings { it.copy(allowMultipleVoices = enabled) }

    fun autoAssign(enabled: Boolean) = updateSettings { it.copy(autoAssignRole = enabled) }

    fun cacheLimit(limit: Int) = updateSettings { it.copy(maxCacheVoiceNum = limit) }

    fun roleContextPattern(pattern: String) = updateSettings { it.copy(roleContextPattern = pattern) }

    fun engineCacheCapacity(capacity: Int) = updateSettings { it.copy(engineCacheCapacity = capacity) }

    fun audioSettings(audio: SpeechAudioSettings) = updateSettings { it.copy(audio = audio) }

    fun diagnosticLogging(enabled: Boolean) = updateSettings { it.copy(diagnosticLogging = enabled) }

    fun backgroundMusic(enabled: Boolean) = updateSettings { it.copy(backgroundMusic = it.backgroundMusic.copy(enabled = enabled)) }

    // MultiTTS seeds the localized default on the first visit to software settings.
    fun initializeRoleContext(default: String) {
        viewModelScope.launch {
            try {
                repository.update {
                    it.copy(roleContextPattern = it.roleContextPattern ?: default)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }

    private fun updateSettings(change: (SpeechSettings) -> SpeechSettings) {
        viewModelScope.launch {
            try {
                repository.update(change)
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
    val networkCheckData = userData.booleanUserData(SpeechNetworkCheck.SETTING_KEY)
    val auditionText by auditionData.asState("")
    val showParameters by parametersData.asState(false)
    val showExtensions by extensionsData.asState(false)
    val checkNetwork by networkCheckData.asState(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechPlaybackSettingsScreen(
    state: SpeechPlaybackSettingsState,
    preferences: SpeechPlaybackPreferences,
    onBack: () -> Unit,
    onUpdate: ((SpeechTextSettings) -> SpeechTextSettings) -> Unit,
    onMultipleVoices: (Boolean) -> Unit,
    onContextPattern: (String) -> Unit,
    onNameTest: () -> Unit,
    onTextAnalysis: () -> Unit,
    onEngineCacheCapacity: (Int) -> Unit,
    onAudioSettings: (SpeechAudioSettings) -> Unit,
    onBackgroundMusic: (Boolean) -> Unit,
    onAutoAssign: (Boolean) -> Unit,
    onCacheLimit: (Int) -> Unit,
    onDiagnosticLogging: (Boolean) -> Unit,
    onManageBackgroundMusic: (() -> Unit)? = null,
) {
    val tokens = NextVolDesign.tokens
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmingGpuOff by rememberSaveable { mutableStateOf(false) }
    var editingContext by rememberSaveable { mutableStateOf(false) }
    var contextPattern by rememberSaveable { mutableStateOf("") }
    var contextError by remember { mutableStateOf(false) }
    var editingMaleRule by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var editingPersonRule by rememberSaveable { mutableStateOf(false) }
    var editingPause by rememberSaveable { mutableStateOf(false) }
    var pauseText by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    val defaultText = stringResource(R.string.tts_preview_text)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_playback_preferences),
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
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.settings == null) {
                item(key = "loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            state.settings?.let { settings ->
                // 1. Hardware acceleration
                item(key = "section_acceleration") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(R.string.tts_runtime_sovits),
                        )
                        EditorialCard(modifier = Modifier.fillMaxWidth()) {
                            val gpuSupported = state.acceleration.gpu
                            val gpuChecked = state.audio.sovitsGpu && gpuSupported == true
                            SpeechTextEntry(
                                icon = R.drawable.tts_speed,
                                title = R.string.sovits_gpu_acceleration,
                                description = "${state.acceleration.gpuInfo?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sovits_acceleration_unknown_device)} · ${
                                    stringResource(
                                        when (gpuSupported) {
                                            null -> R.string.sovits_acceleration_checking
                                            false -> R.string.sovits_acceleration_unsupported
                                            true -> R.string.sovits_acceleration_supported
                                        },
                                    )
                                }",
                                enabled = gpuSupported == true,
                                modifier = Modifier.toggleable(
                                    value = gpuChecked,
                                    enabled = gpuSupported == true,
                                    role = Role.Switch,
                                    onValueChange = {
                                        if (it) onAudioSettings(state.audio.copy(sovitsGpu = true)) else confirmingGpuOff = true
                                    },
                                ),
                            ) {
                                PlaybackSwitch(
                                    checked = gpuChecked,
                                    enabled = gpuSupported == true,
                                    onCheckedChange = {
                                        if (it) onAudioSettings(state.audio.copy(sovitsGpu = true)) else confirmingGpuOff = true
                                    },
                                )
                            }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            val npuSupported = state.acceleration.npu
                            val npuEnabled = npuSupported == true && state.audio.sovitsGpu
                            val npuChecked = state.audio.sovitsNpu && npuEnabled
                            SpeechTextEntry(
                                icon = R.drawable.tts_speed,
                                title = R.string.sovits_device_acceleration,
                                description = "${state.acceleration.npuInfo?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sovits_acceleration_unknown_device)} · ${
                                    stringResource(
                                        when {
                                            npuSupported == null -> R.string.sovits_acceleration_checking
                                            !npuSupported -> R.string.sovits_acceleration_unsupported
                                            !state.audio.sovitsGpu -> R.string.sovits_requires_gpu
                                            else -> R.string.sovits_acceleration_supported
                                        },
                                    )
                                }",
                                enabled = npuEnabled,
                                modifier = Modifier.toggleable(
                                    value = npuChecked,
                                    enabled = npuEnabled,
                                    role = Role.Switch,
                                    onValueChange = { onAudioSettings(state.audio.copy(sovitsNpu = it)) },
                                ),
                            ) {
                                PlaybackSwitch(
                                    checked = npuChecked,
                                    enabled = npuEnabled,
                                    onCheckedChange = { onAudioSettings(state.audio.copy(sovitsNpu = it)) },
                                )
                            }
                        }
                    }
                }

                // 2. Dialogue & Role matching
                item(key = "section_roles") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(R.string.tts_roles),
                        )
                        EditorialCard(modifier = Modifier.fillMaxWidth()) {
                            SpeechTextSwitch(
                                R.drawable.tts_text_dialogue,
                                R.string.tts_text_dialogue,
                                R.string.tts_text_dialogue_description,
                                settings.dialogue,
                            ) { onUpdate { old -> old.copy(dialogue = it) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_multiple_voices,
                                R.string.tts_voice_multiple,
                                if (state.allowMultipleVoices) R.string.tts_voice_multiple_on else R.string.tts_voice_multiple_off,
                                state.allowMultipleVoices,
                                onMultipleVoices,
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextEntry(
                                icon = R.drawable.tts_text_role_matching,
                                title = R.string.tts_role_context_template,
                                description = state.roleContextPattern,
                                modifier = Modifier.clickable {
                                    contextPattern = state.roleContextPattern
                                    contextError = false
                                    editingContext = true
                                },
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextEntry(
                                icon = R.drawable.search_24px,
                                title = R.string.tts_name_test,
                                description = "",
                                modifier = Modifier.clickable(onClick = onNameTest),
                            ) {
                                Icon(
                                    painterResource(R.drawable.arrow_forward_ios_24px),
                                    null,
                                    Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            val nameAlgorithms = remember {
                                object : MenuOptions(
                                    Option("101", R.string.tts_name_ner),
                                    Option("100", R.string.tts_new_word_algorithm),
                                ) {}
                            }
                            SpeechTextMenu(
                                icon = R.drawable.tts_text_algorithm,
                                title = R.string.tts_name_algorithm,
                                options = nameAlgorithms,
                                selectedOptionKey = settings.nameDiscoveryAlgorithm.toString(),
                                onOptionChange = { key -> onUpdate { it.copy(nameDiscoveryAlgorithm = key.toInt()) } },
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            val langAlgorithms = remember {
                                object : MenuOptions(
                                    Option("300", R.string.tts_language_optimaize),
                                    Option("301", R.string.tts_language_media_pipe),
                                ) {}
                            }
                            SpeechTextMenu(
                                icon = R.drawable.tts_text_algorithm,
                                title = R.string.tts_language_detector,
                                options = langAlgorithms,
                                selectedOptionKey = settings.languageDetector.toString(),
                                onOptionChange = { key -> onUpdate { it.copy(languageDetector = key.toInt()) } },
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextEntry(
                                icon = R.drawable.female_24px,
                                title = R.string.tts_name_female_rule,
                                description = stringResource(R.string.tts_name_female_rule_description),
                                modifier = Modifier.clickable {
                                    text = settings.femaleNameRegex
                                    editingMaleRule = false
                                },
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextEntry(
                                icon = R.drawable.male_24px,
                                title = R.string.tts_name_male_rule,
                                description = stringResource(R.string.tts_name_male_rule_description),
                                modifier = Modifier.clickable {
                                    text = settings.maleNameRegex
                                    editingMaleRule = true
                                },
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_auto_assign,
                                R.string.tts_pool_auto_assign,
                                if (state.autoAssignRole) R.string.tts_pool_auto_on else R.string.tts_pool_auto_off,
                                state.autoAssignRole,
                                onAutoAssign,
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_record_voice,
                                R.string.tts_text_record_voice,
                                R.string.tts_text_record_voice_description,
                                settings.recordVoice,
                            ) { onUpdate { old -> old.copy(recordVoice = it) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_record_text,
                                R.string.tts_text_record_text,
                                R.string.tts_text_record_text_description,
                                settings.recordText,
                            ) { onUpdate { old -> old.copy(recordText = it) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_continue_dialogue,
                                R.string.tts_text_continue_dialogue,
                                R.string.tts_text_continue_dialogue_description,
                                settings.continueDialogue,
                            ) { onUpdate { old -> old.copy(continueDialogue = it) } }
                        }
                    }
                }

                // 3. Text processing
                item(key = "section_text_processing") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(R.string.tts_replacements),
                        )
                        EditorialCard(modifier = Modifier.fillMaxWidth()) {
                            SpeechTextSwitch(
                                R.drawable.tts_text_replace_emoji,
                                R.string.tts_text_replace_emoji,
                                R.string.tts_text_replace_emoji_description,
                                settings.replaceEmoji,
                            ) { onUpdate { old -> old.copy(replaceEmoji = it) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_separate_english,
                                R.string.tts_text_separate_english,
                                R.string.tts_text_separate_english_description,
                                settings.separateEnglish,
                            ) { onUpdate { old -> old.copy(separateEnglish = it) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_text_filter_unspoken,
                                R.string.tts_text_filter_unspoken,
                                R.string.tts_text_filter_unspoken_description,
                                settings.filterUnspoken,
                            ) { onUpdate { old -> old.copy(filterUnspoken = it) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSlider(
                                icon = R.drawable.tts_text_sentence_length,
                                title = R.string.tts_text_sentence_length,
                                value = settings.maxSentenceLength,
                                valueRange = 32f..256f,
                                description = R.string.tts_text_sentence_length_description,
                                onChange = { length -> onUpdate { it.copy(maxSentenceLength = length) } },
                            )
                        }
                    }
                }

                // 4. Audio & Background Music
                item(key = "section_audio") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(R.string.tts_background_music),
                        )
                        EditorialCard(modifier = Modifier.fillMaxWidth()) {
                            SpeechTextEntry(
                                icon = R.drawable.pause_24px,
                                title = R.string.tts_paragraph_pause,
                                description = stringResource(R.string.tts_paragraph_pause_description),
                                modifier = Modifier.clickable {
                                    pauseText = settings.paragraphPauseMs.toString()
                                    editingPause = true
                                },
                            ) {
                                Text(
                                    text = if (settings.paragraphPauseMs == 0) {
                                        stringResource(R.string.tts_pause_disabled)
                                    } else {
                                        stringResource(R.string.tts_pause_duration, settings.paragraphPauseMs)
                                    },
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                    fontWeight = FontWeight.W600,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_audio_silence,
                                R.string.tts_audio_check_silence,
                                if (state.audio.checkSilence) R.string.tts_audio_check_silence_on else R.string.tts_audio_check_silence_off,
                                state.audio.checkSilence,
                            ) { onAudioSettings(state.audio.copy(checkSilence = it)) }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.music_note_24px,
                                R.string.tts_background_music,
                                R.string.tts_music_description,
                                state.backgroundMusicEnabled,
                                onBackgroundMusic,
                            )
                            if (onManageBackgroundMusic != null) {
                                EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                                SpeechTextEntry(
                                    icon = R.drawable.music_note_24px,
                                    title = R.string.tts_music_tracks,
                                    description = "",
                                    modifier = Modifier.clickable(onClick = onManageBackgroundMusic),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.arrow_forward_ios_24px),
                                        null,
                                        Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.tts_decode_stream,
                                R.string.tts_decode_stream,
                                if (state.audio.decodeStream) R.string.tts_decode_stream_on else R.string.tts_decode_stream_off,
                                state.audio.decodeStream,
                            ) { onAudioSettings(state.audio.copy(decodeStream = it)) }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            val rates = remember {
                                object : MenuOptions(
                                    Option("16000", R.string.tts_sample_rate_16000),
                                    Option("22050", R.string.tts_sample_rate_22050),
                                    Option("24000", R.string.tts_sample_rate_24000),
                                    Option("32000", R.string.tts_sample_rate_32000),
                                    Option("44100", R.string.tts_sample_rate_44100),
                                    Option("48000", R.string.tts_sample_rate_48000),
                                    Option("64000", R.string.tts_sample_rate_64000),
                                    Option("96000", R.string.tts_sample_rate_96000),
                                ) {}
                            }
                            SpeechTextMenu(
                                icon = R.drawable.tts_audio_sample_rate,
                                title = R.string.tts_audio_minimum_rate,
                                options = rates,
                                selectedOptionKey = state.audio.minimumSampleRate.toString(),
                                onOptionChange = { onAudioSettings(state.audio.copy(minimumSampleRate = it.toInt())) },
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            val processors = remember {
                                object : MenuOptions(
                                    Option("200", R.string.tts_audio_sonic),
                                    Option("201", R.string.tts_audio_soundtouch),
                                ) {}
                            }
                            SpeechTextMenu(
                                icon = R.drawable.tts_text_algorithm,
                                title = R.string.tts_audio_processor,
                                options = processors,
                                selectedOptionKey = state.audio.processor.toString(),
                                onOptionChange = { onAudioSettings(state.audio.copy(processor = it.toInt())) },
                            )
                        }
                    }
                }
            }

            // 5. Audition & Character Analysis
            item(key = "section_analysis") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(
                        modifier = Modifier.padding(start = 6.dp),
                        text = stringResource(R.string.tts_analysis_title),
                    )
                    EditorialCard(modifier = Modifier.fillMaxWidth()) {
                        SpeechTextEntry(
                            icon = R.drawable.text_snippet_24px,
                            title = R.string.tts_voice_audition_text,
                            description = preferences.auditionText.ifBlank { defaultText },
                            modifier = Modifier.clickable {
                                text = preferences.auditionText.ifBlank { defaultText }
                                editing = true
                            },
                        )
                        state.settings?.let { settings ->
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.language_24px,
                                R.string.tts_filter_language,
                                R.string.tts_filter_language_description,
                                settings.filterLanguage,
                            ) { onUpdate { old -> old.copy(filterLanguage = it) } }
                        }
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        SpeechTextEntry(
                            icon = R.drawable.analytics_24px,
                            title = R.string.tts_analysis_title,
                            description = stringResource(R.string.tts_analysis_description),
                            modifier = Modifier.clickable(onClick = onTextAnalysis),
                        ) {
                            Icon(
                                painterResource(R.drawable.arrow_forward_ios_24px),
                                null,
                                Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.settings?.let { settings ->
                            if (state.defaultNamePattern != null) {
                                EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                                SpeechTextEntry(
                                    icon = R.drawable.code_24px,
                                    title = R.string.tts_new_word_pattern,
                                    description = stringResource(R.string.tts_new_word_pattern_description),
                                    modifier = Modifier.clickable {
                                        text = settings.personNameRegex ?: state.defaultNamePattern
                                        editingPersonRule = true
                                    },
                                )
                            }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSwitch(
                                R.drawable.autorenew_24px,
                                R.string.tts_analysis_only_new,
                                if (settings.onlyNewPerson) R.string.tts_analysis_only_new_on else R.string.tts_analysis_only_new_off,
                                settings.onlyNewPerson,
                            ) { value -> onUpdate { it.copy(onlyNewPerson = value) } }
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSlider(
                                icon = R.drawable.select_all_24px,
                                title = R.string.tts_analysis_auto_checked,
                                value = settings.maxAutoCheckedPerson,
                                valueRange = 0f..100f,
                                onChange = { count -> onUpdate { it.copy(maxAutoCheckedPerson = count) } },
                            )
                        }
                    }
                }
            }

            // 6. Cache & Diagnostics
            item(key = "section_cache_advanced") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(
                        modifier = Modifier.padding(start = 6.dp),
                        text = stringResource(R.string.tts_diagnostics),
                    )
                    EditorialCard(modifier = Modifier.fillMaxWidth()) {
                        state.settings?.let {
                            SpeechTextSlider(
                                icon = R.drawable.tts_text_cache_voice,
                                title = R.string.tts_pool_cache_limit,
                                value = state.maxCacheVoiceNum.coerceIn(3, 300),
                                valueRange = 3f..300f,
                                onChange = onCacheLimit,
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            SpeechTextSlider(
                                icon = R.drawable.tts_text_cache_voice,
                                title = R.string.tts_engine_cache_capacity,
                                value = state.engineCacheCapacity,
                                valueRange = 3f..32f,
                                onChange = onEngineCacheCapacity,
                            )
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        }
                        SpeechTextSwitch(
                            R.drawable.outline_settings_24px,
                            R.string.tts_voice_hide_parameters,
                            null,
                            !preferences.showParameters,
                        ) { preferences.parametersData.asynchronousSet(!it) }
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        SpeechTextSwitch(
                            R.drawable.settings_applications_24px,
                            R.string.tts_voice_hide_extensions,
                            null,
                            !preferences.showExtensions,
                        ) { preferences.extensionsData.asynchronousSet(!it) }
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        SpeechTextSwitch(
                            R.drawable.public_24px,
                            R.string.tts_http_check_network,
                            null,
                            preferences.checkNetwork,
                            preferences.networkCheckData::asynchronousSet,
                        )
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        SpeechTextSwitch(
                            R.drawable.bug_report_24px,
                            R.string.tts_diagnostic_logging,
                            R.string.tts_diagnostic_logging_description,
                            state.diagnosticLogging,
                            onDiagnosticLogging,
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_voice_audition_text), fontWeight = FontWeight.W600) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 2,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        preferences.auditionData.asynchronousSet(text.trim())
                        editing = false
                    },
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmingGpuOff) {
        AlertDialog(
            onDismissRequest = { confirmingGpuOff = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.sovits_disable_gpu), fontWeight = FontWeight.W600) },
            text = {
                Text(
                    text = stringResource(R.string.sovits_disable_gpu_warning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAudioSettings(state.audio.copy(sovitsGpu = false))
                        confirmingGpuOff = false
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.sovits_disable_gpu))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingGpuOff = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (editingPause) {
        AlertDialog(
            onDismissRequest = { editingPause = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_paragraph_pause), fontWeight = FontWeight.W600) },
            text = {
                OutlinedTextField(
                    value = pauseText,
                    onValueChange = { pauseText = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.tts_pause_milliseconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                Button(
                    enabled = pauseText.toIntOrNull()?.let { it >= 0 } == true,
                    shape = CircleShape,
                    onClick = {
                        val duration = pauseText.toInt()
                        onUpdate { it.copy(paragraphPauseMs = duration) }
                        editingPause = false
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPause = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (editingContext) {
        AlertDialog(
            onDismissRequest = { editingContext = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_role_context_template), fontWeight = FontWeight.W600) },
            text = {
                ReplacementField(
                    value = contextPattern,
                    onChange = {
                        contextPattern = it
                        contextError = false
                    },
                    label = stringResource(R.string.tts_role_context_template),
                    error = if (contextError) stringResource(R.string.tts_replace_error_pattern) else null,
                )
            },
            confirmButton = {
                Button(
                    shape = CircleShape,
                    onClick = {
                        try {
                            Pattern.compile(contextPattern)
                            onContextPattern(contextPattern.replace("__ALLROLE__", "__OTHER__"))
                            editingContext = false
                        } catch (_: PatternSyntaxException) {
                            contextError = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingContext = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    editingMaleRule?.let { male ->
        SpeechNameRuleDialog(
            title = stringResource(if (male) R.string.tts_name_male_rule else R.string.tts_name_female_rule),
            value = text,
            onChange = { text = it },
            onSave = {
                val pattern = text
                onUpdate { if (male) it.copy(maleNameRegex = pattern) else it.copy(femaleNameRegex = pattern) }
                editingMaleRule = null
            },
            onDismiss = { editingMaleRule = null },
        )
    }
    if (editingPersonRule) {
        SpeechNameRuleDialog(
            title = stringResource(R.string.tts_new_word_pattern),
            value = text,
            onChange = { text = it },
            onSave = {
                val pattern = text
                onUpdate { it.copy(personNameRegex = pattern.takeUnless { it == state.defaultNamePattern }) }
                editingPersonRule = false
            },
            onDismiss = { editingPersonRule = false },
        )
    }
}

@Composable
private fun SpeechTextSwitch(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes description: Int?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SpeechTextEntry(
        icon = icon,
        title = title,
        description = description?.let { stringResource(it) }.orEmpty(),
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    ) {
        PlaybackSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SpeechTextEntry(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    description: String,
    modifier: Modifier,
    enabled: Boolean = true,
    trailingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EditorialIconBadge(
            painter = painterResource(icon),
            size = 36.dp,
            iconSize = 18.dp,
            cornerRadius = 11.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W500,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent()
    }
}

@Composable
private fun SpeechTextMenu(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    options: MenuOptions,
    selectedOptionKey: String,
    onOptionChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.getOrNull(selectedOptionKey)
    Box {
        SpeechTextEntry(
            icon = icon,
            title = title,
            description = selected?.let { stringResource(it.nameId) } ?: selectedOptionKey,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Icon(
                painter = painterResource(R.drawable.unfold_more_double_24px),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EditorialPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            EditorialPopupMenuHeader(text = stringResource(title))
            options.optionList.forEach { option ->
                EditorialPopupMenuItem(
                    text = stringResource(option.nameId),
                    selected = option.key == selectedOptionKey,
                    onClick = {
                        onOptionChange(option.key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechTextSlider(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    @StringRes description: Int? = null,
    onChange: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val label = stringResource(title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EditorialIconBadge(
            painter = painterResource(icon),
            size = 36.dp,
            iconSize = 18.dp,
            cornerRadius = 11.dp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.W500,
                )
                Text(
                    text = current.roundToInt().toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            description?.let {
                Text(
                    text = stringResource(it),
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = current,
                onValueChange = { current = it },
                valueRange = valueRange,
                onValueChangeFinished = { onChange(current.roundToInt()) },
                modifier = Modifier.semantics { contentDescription = label },
                thumb = {
                    Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                },
                track = {
                    SliderDefaults.Track(
                        sliderState = it,
                        modifier = Modifier.height(4.dp),
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 2.dp,
                        drawStopIndicator = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun PlaybackSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    MiuixSwitch(
        checked = checked,
        onCheckedChange = { if (enabled) onCheckedChange(it) },
        enabled = enabled,
    )
}
