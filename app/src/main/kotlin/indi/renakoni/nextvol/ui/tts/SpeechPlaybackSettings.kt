package indi.renakoni.nextvol.ui.tts

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.names.SpeechNewWordDiscovery
import indi.renakoni.nextvol.data.setting.AbstractSettingState
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.MenuOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
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
)

@HiltViewModel
class SpeechPlaybackSettingsViewModel @Inject constructor(
    private val repository: SpeechSettingsRepository,
    userData: UserDataRepository,
    newWords: SpeechNewWordDiscovery,
    private val backgroundMusic: BackgroundMusicRepository,
) : ViewModel() {
    val preferences = SpeechPlaybackPreferences(userData, viewModelScope)
    private val mutableState = MutableStateFlow(SpeechPlaybackSettingsState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.changes, backgroundMusic.changes.map { it.enabled }.distinctUntilChanged()) { settings, music -> settings to music }
                .collect { (settings, music) -> mutableState.update {
                it.copy(settings = settings.text, allowMultipleVoices = settings.allowMultipleVoices,
                    engineCacheCapacity = settings.engineCacheCapacity,
                    audio = settings.audio,
                    backgroundMusicEnabled = music,
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

    fun backgroundMusic(enabled: Boolean) {
        viewModelScope.launch {
            try {
                backgroundMusic.setEnabled(enabled)
                mutableState.update { it.copy(error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }

    // MultiTTS seeds the localized default on the first visit to software settings.
    fun initializeRoleContext(default: String) {
        viewModelScope.launch {
            try {
                if (repository.get().roleContextPattern == null) repository.update {
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
@Composable
internal fun SpeechPlaybackSettingsScreen(state: SpeechPlaybackSettingsState, preferences: SpeechPlaybackPreferences, onBack: () -> Unit,
    onUpdate: ((SpeechTextSettings) -> SpeechTextSettings) -> Unit, onMultipleVoices: (Boolean) -> Unit,
    onContextPattern: (String) -> Unit, onNameTest: () -> Unit, onTextAnalysis: () -> Unit,
    onEngineCacheCapacity: (Int) -> Unit,
    onAudioSettings: (SpeechAudioSettings) -> Unit,
    onBackgroundMusic: (Boolean) -> Unit,
    onAutoAssign: (Boolean) -> Unit, onCacheLimit: (Int) -> Unit,
    onDiagnosticLogging: (Boolean) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingContext by rememberSaveable { mutableStateOf(false) }
    var contextPattern by rememberSaveable { mutableStateOf("") }
    var contextError by remember { mutableStateOf(false) }
    var editingMaleRule by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var editingPersonRule by rememberSaveable { mutableStateOf(false) }
    var editingPause by rememberSaveable { mutableStateOf(false) }
    var pauseText by rememberSaveable { mutableStateOf("") }
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
                item { SpeechTextSwitch(R.drawable.music_note_24px, R.string.tts_background_music, R.string.tts_music_description,
                    state.backgroundMusicEnabled, onBackgroundMusic) }
                item { SpeechTextSwitch(R.drawable.tts_decode_stream, R.string.tts_decode_stream,
                    if (state.audio.decodeStream) R.string.tts_decode_stream_on else R.string.tts_decode_stream_off,
                    state.audio.decodeStream) { onAudioSettings(state.audio.copy(decodeStream = it)) } }
                item { SpeechTextEntry(R.drawable.pause_24px, R.string.tts_paragraph_pause, R.string.tts_paragraph_pause_description,
                    Modifier.clickable { pauseText = settings.paragraphPauseMs.toString(); editingPause = true }) {
                    Text(if (settings.paragraphPauseMs == 0) stringResource(R.string.tts_pause_disabled)
                        else stringResource(R.string.tts_pause_duration, settings.paragraphPauseMs),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } }
                item {
                    val processors = remember { object : MenuOptions(Option("200", R.string.tts_audio_sonic),
                        Option("201", R.string.tts_audio_soundtouch)) {} }
                    SpeechTextMenu(R.drawable.tts_text_algorithm, R.string.tts_audio_processor, processors,
                        selectedOptionKey = state.audio.processor.toString(),
                        onOptionChange = { onAudioSettings(state.audio.copy(processor = it.toInt())) })
                }
                item {
                    val rates = remember { object : MenuOptions(Option("16000", R.string.tts_sample_rate_16000),
                        Option("22050", R.string.tts_sample_rate_22050), Option("24000", R.string.tts_sample_rate_24000),
                        Option("32000", R.string.tts_sample_rate_32000), Option("44100", R.string.tts_sample_rate_44100),
                        Option("48000", R.string.tts_sample_rate_48000), Option("64000", R.string.tts_sample_rate_64000),
                        Option("96000", R.string.tts_sample_rate_96000)) {} }
                    SpeechTextMenu(R.drawable.tts_audio_sample_rate, R.string.tts_audio_minimum_rate, rates,
                        selectedOptionKey = state.audio.minimumSampleRate.toString(),
                        onOptionChange = { onAudioSettings(state.audio.copy(minimumSampleRate = it.toInt())) })
                }
                item { SpeechTextSwitch(R.drawable.tts_audio_silence, R.string.tts_audio_check_silence,
                    if (state.audio.checkSilence) R.string.tts_audio_check_silence_on else R.string.tts_audio_check_silence_off,
                    state.audio.checkSilence) { onAudioSettings(state.audio.copy(checkSilence = it)) } }
                item {
                    SpeechTextSlider(R.drawable.tts_text_cache_voice, R.string.tts_engine_cache_capacity,
                        state.engineCacheCapacity, 3f..32f, onChange = onEngineCacheCapacity)
                }
                item { SpeechTextSwitch(R.drawable.tts_text_multiple_voices, R.string.tts_voice_multiple,
                    if (state.allowMultipleVoices) R.string.tts_voice_multiple_on else R.string.tts_voice_multiple_off,
                    state.allowMultipleVoices, onMultipleVoices) }
                item { SpeechTextSwitch(R.drawable.tts_text_dialogue, R.string.tts_text_dialogue, R.string.tts_text_dialogue_description,
                    settings.dialogue) { onUpdate { old -> old.copy(dialogue = it) } } }
                item {
                    Row(Modifier.fillMaxWidth().clickable {
                        contextPattern = state.roleContextPattern; contextError = false; editingContext = true
                    }.heightIn(min = 68.dp).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.tts_text_role_matching), null, Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.tts_role_context_template), style = MaterialTheme.typography.headlineSmall)
                            Text(state.roleContextPattern, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item { SpeechTextSwitch(R.drawable.tts_text_auto_assign, R.string.tts_pool_auto_assign,
                    if (state.autoAssignRole) R.string.tts_pool_auto_on else R.string.tts_pool_auto_off,
                    state.autoAssignRole, onAutoAssign) }
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
                item { SpeechTextSwitch(R.drawable.language_24px, R.string.tts_detect_language, R.string.tts_detect_language_description,
                    settings.detectLanguage) { onUpdate { old -> old.copy(detectLanguage = it) } } }
                item {
                    val algorithms = remember { object : MenuOptions(Option("300", R.string.tts_language_optimaize),
                        Option("301", R.string.tts_language_media_pipe)) {} }
                    SpeechTextMenu(R.drawable.tts_text_algorithm, R.string.tts_language_detector, algorithms,
                        selectedOptionKey = settings.languageDetector.toString(),
                        onOptionChange = { key -> onUpdate { it.copy(languageDetector = key.toInt()) } })
                }
                item { SpeechTextSwitch(R.drawable.language_24px, R.string.tts_filter_language, R.string.tts_filter_language_description,
                    settings.filterLanguage) { onUpdate { old -> old.copy(filterLanguage = it) } } }
                item { SpeechTextSwitch(R.drawable.tts_text_filter_unspoken, R.string.tts_text_filter_unspoken, R.string.tts_text_filter_unspoken_description,
                    settings.filterUnspoken) { onUpdate { old -> old.copy(filterUnspoken = it) } } }
                item {
                    SpeechTextSlider(R.drawable.tts_text_sentence_length, R.string.tts_text_sentence_length,
                        settings.maxSentenceLength, 32f..256f, R.string.tts_text_sentence_length_description,
                        onChange = { length -> onUpdate { it.copy(maxSentenceLength = length) } })
                }
            }
            if (state.settings != null) item {
                var limit by remember(state.maxCacheVoiceNum) { mutableFloatStateOf(state.maxCacheVoiceNum.coerceIn(3, 300).toFloat()) }
                val title = stringResource(R.string.tts_pool_cache_limit)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Icon(painterResource(R.drawable.tts_text_cache_voice), null, Modifier.padding(top = 2.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(limit.roundToInt().toString(), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(limit, { limit = it }, valueRange = 3f..300f,
                            onValueChangeFinished = { onCacheLimit(limit.roundToInt()) },
                            modifier = Modifier.semantics { contentDescription = title },
                            thumb = { Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {} },
                            track = { SliderDefaults.Track(it, Modifier.height(4.dp), thumbTrackGapSize = 0.dp,
                                trackInsideCornerSize = 2.dp, drawStopIndicator = null) })
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(start = 60.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) }
            state.settings?.let { settings -> item {
                val algorithms = remember { object : MenuOptions(Option("101", R.string.tts_name_ner),
                    Option("100", R.string.tts_new_word_algorithm)) {} }
                SpeechTextMenu(R.drawable.tts_text_algorithm, R.string.tts_name_algorithm, algorithms,
                    selectedOptionKey = settings.nameDiscoveryAlgorithm.toString(),
                    onOptionChange = { key -> onUpdate { it.copy(nameDiscoveryAlgorithm = key.toInt()) } })
            } }
            item {
                SettingsClickableEntry(painter = painterResource(R.drawable.search_24px),
                    title = stringResource(R.string.tts_name_test),
                    trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                    onClick = onNameTest)
            }
            item {
                SpeechTextEntry(R.drawable.analytics_24px, R.string.tts_analysis_title, R.string.tts_analysis_description,
                    Modifier.clickable(onClick = onTextAnalysis))
            }
            state.settings?.let { settings ->
                if (state.defaultNamePattern != null) item {
                    SpeechTextEntry(R.drawable.code_24px, R.string.tts_new_word_pattern, R.string.tts_new_word_pattern_description,
                        Modifier.clickable { text = settings.personNameRegex ?: state.defaultNamePattern; editingPersonRule = true })
                }
            }
            state.settings?.let { settings ->
                item {
                    SpeechTextSwitch(R.drawable.autorenew_24px, R.string.tts_analysis_only_new,
                        if (settings.onlyNewPerson) R.string.tts_analysis_only_new_on else R.string.tts_analysis_only_new_off,
                        settings.onlyNewPerson) { value -> onUpdate { it.copy(onlyNewPerson = value) } }
                }
                item {
                    SpeechTextSlider(R.drawable.select_all_24px, R.string.tts_analysis_auto_checked,
                        settings.maxAutoCheckedPerson, 0f..100f,
                        onChange = { count -> onUpdate { it.copy(maxAutoCheckedPerson = count) } })
                }
            }
            state.settings?.let { settings ->
                item {
                    SpeechTextEntry(R.drawable.male_24px, R.string.tts_name_male_rule, R.string.tts_name_male_rule_description,
                        Modifier.clickable { text = settings.maleNameRegex; editingMaleRule = true })
                }
                item {
                    SpeechTextEntry(R.drawable.female_24px, R.string.tts_name_female_rule, R.string.tts_name_female_rule_description,
                        Modifier.clickable { text = settings.femaleNameRegex; editingMaleRule = false })
                }
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_audition_text),
                    option = preferences.auditionText.ifBlank { defaultText },
                    onClick = { text = preferences.auditionText.ifBlank { defaultText }; editing = true })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_show_parameters),
                    trailingContent = { PlaybackSwitch(preferences.showParameters, preferences.parametersData::asynchronousSet) },
                    onClick = { preferences.parametersData.asynchronousSet(!preferences.showParameters) })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_show_extensions),
                    trailingContent = { PlaybackSwitch(preferences.showExtensions, preferences.extensionsData::asynchronousSet) },
                    onClick = { preferences.extensionsData.asynchronousSet(!preferences.showExtensions) })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_http_check_network),
                    trailingContent = { PlaybackSwitch(preferences.checkNetwork, preferences.networkCheckData::asynchronousSet) },
                    onClick = { preferences.networkCheckData.asynchronousSet(!preferences.checkNetwork) })
            }
            item { SpeechTextSwitch(R.drawable.bug_report_24px, R.string.tts_diagnostic_logging,
                R.string.tts_diagnostic_logging_description, state.diagnosticLogging, onDiagnosticLogging) }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.tts_voice_audition_text)) },
        text = { TextField(text, { text = it.take(2000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
        confirmButton = { TextButton(onClick = { preferences.auditionData.asynchronousSet(text.trim()); editing = false }) {
            Text(stringResource(R.string.tts_engine_save))
        } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    if (editingPause) AlertDialog(onDismissRequest = { editingPause = false },
        title = { Text(stringResource(R.string.tts_paragraph_pause)) },
        text = { OutlinedTextField(pauseText, { pauseText = it }, singleLine = true,
            label = { Text(stringResource(R.string.tts_pause_milliseconds)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
        confirmButton = { TextButton(enabled = pauseText.toIntOrNull()?.let { it >= 0 } == true, onClick = {
            val duration = pauseText.toInt()
            onUpdate { it.copy(paragraphPauseMs = duration) }
            editingPause = false
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { editingPause = false }) { Text(stringResource(R.string.cancel)) } })
    if (editingContext) AlertDialog(onDismissRequest = { editingContext = false },
        title = { Text(stringResource(R.string.tts_role_context_template)) },
        text = { ReplacementField(contextPattern, { contextPattern = it; contextError = false }, stringResource(R.string.tts_role_context_template),
            error = if (contextError) stringResource(R.string.tts_replace_error_pattern) else null) },
        confirmButton = { TextButton(onClick = {
            try {
                Pattern.compile(contextPattern)
                onContextPattern(contextPattern.replace("__ALLROLE__", "__OTHER__"))
                editingContext = false
            } catch (_: PatternSyntaxException) { contextError = true }
        }) {
            Text(stringResource(R.string.confirm))
        } }, dismissButton = { TextButton(onClick = { editingContext = false }) { Text(stringResource(R.string.cancel)) } })
    editingMaleRule?.let { male ->
        SpeechNameRuleDialog(stringResource(if (male) R.string.tts_name_male_rule else R.string.tts_name_female_rule),
            text, { text = it }, onSave = {
                val pattern = text
                onUpdate { if (male) it.copy(maleNameRegex = pattern) else it.copy(femaleNameRegex = pattern) }
                editingMaleRule = null
            }, onDismiss = { editingMaleRule = null })
    }
    if (editingPersonRule) SpeechNameRuleDialog(stringResource(R.string.tts_new_word_pattern),
        text, { text = it }, onSave = {
            val pattern = text
            onUpdate { it.copy(personNameRegex = pattern.takeUnless { it == state.defaultNamePattern }) }
            editingPersonRule = false
        }, onDismiss = { editingPersonRule = false })
}

@Composable
private fun SpeechTextSwitch(@DrawableRes icon: Int, @StringRes title: Int, @StringRes description: Int,
    checked: Boolean, onChange: (Boolean) -> Unit) {
    SpeechTextEntry(icon, title, description, Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        PlaybackSwitch(checked)
    }
}

@Composable
private fun SpeechTextEntry(@DrawableRes icon: Int, @StringRes title: Int, @StringRes description: Int,
    modifier: Modifier, trailingContent: @Composable () -> Unit = {}) {
    SpeechTextEntry(icon, title, stringResource(description), modifier, trailingContent)
}

@Composable
private fun SpeechTextEntry(@DrawableRes icon: Int, @StringRes title: Int, description: String,
    modifier: Modifier, trailingContent: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth()
        .heightIn(min = 68.dp).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailingContent()
    }
}

@Composable
private fun SpeechTextMenu(@DrawableRes icon: Int, @StringRes title: Int, options: MenuOptions,
    selectedOptionKey: String, onOptionChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.getOrNull(selectedOptionKey)
    Box {
        SpeechTextEntry(icon, title, selected?.let { stringResource(it.nameId) } ?: selectedOptionKey,
            Modifier.clickable { expanded = true })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, offset = DpOffset(60.dp, 0.dp)) {
            options.optionList.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(option.nameId)) }, onClick = {
                    onOptionChange(option.key)
                    expanded = false
                }, trailingIcon = if (option.key == selectedOptionKey) ({
                    Icon(painterResource(R.drawable.check_24px), null)
                }) else null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechTextSlider(@DrawableRes icon: Int, @StringRes title: Int, value: Int,
    valueRange: ClosedFloatingPointRange<Float>, @StringRes description: Int? = null, onChange: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val label = stringResource(title)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Text(current.roundToInt().toString(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            description?.let {
                Text(stringResource(it), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(current, { current = it }, valueRange = valueRange,
                onValueChangeFinished = { onChange(current.roundToInt()) },
                modifier = Modifier.semantics { contentDescription = label },
                thumb = { Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {} },
                track = { SliderDefaults.Track(it, Modifier.height(4.dp), thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 2.dp, drawStopIndicator = null) })
        }
    }
}

@Composable
private fun PlaybackSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Switch(checked, onCheckedChange, modifier = Modifier.scale(0.65f), colors = SwitchDefaults.colors(
        checkedThumbColor = colors.primary,
        checkedTrackColor = colors.primary.copy(alpha = 0.24f),
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = colors.onSurfaceVariant,
        uncheckedTrackColor = colors.onSurfaceVariant.copy(alpha = 0.12f),
        uncheckedBorderColor = Color.Transparent,
    ))
}
