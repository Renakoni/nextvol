package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.names.SpeechNameCandidate
import indi.renakoni.nextvol.tts.names.SpeechNameRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class SpeechNameTestState(
    val running: Boolean = false,
    val candidates: List<SpeechNameCandidate>? = null,
    val error: String? = null,
)

@HiltViewModel
class SpeechNameTestViewModel @Inject constructor(private val recognizer: SpeechNameRecognizer) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechNameTestState())
    val state = mutableState.asStateFlow()
    val available get() = recognizer.available
    private var job: Job? = null

    fun clear() {
        job?.cancel()
        mutableState.value = SpeechNameTestState()
    }

    fun recognize(text: String) {
        clear()
        job = viewModelScope.launch {
            mutableState.value = SpeechNameTestState(running = true)
            try {
                mutableState.value = SpeechNameTestState(candidates = recognizer.recognize(text))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                mutableState.value = SpeechNameTestState(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechNameTestScreen(onBack: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val saved = rememberSaveableStateHolder()
    val tabs = listOf(R.string.tts_name_ner, R.string.tts_new_word_algorithm, R.string.tts_name_classification_tab)
    Scaffold(topBar = { SpeechSettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_name_test, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 20.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected == index, onClick = { selected = index }, text = { Text(stringResource(title)) })
                }
            }
            saved.SaveableStateProvider(selected) {
                when (selected) {
                    0 -> SpeechNameRecognitionContent()
                    1 -> SpeechNewWordTestContent()
                    2 -> SpeechNameClassificationContent()
                }
            }
        }
    }
}

@Composable
internal fun SpeechNameRecognitionContent(modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<SpeechNameTestViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sample = stringResource(R.string.tts_name_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    DisposableEffect(viewModel) { onDispose(viewModel::clear) }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            NameTestInput(text, { text = it; viewModel.clear() }, stringResource(R.string.tts_name_input),
                Modifier.testTag("name-test-input"))
        }
        item {
            NameTestAction(state.running, viewModel.available && text.isNotBlank(),
                stringResource(R.string.tts_name_run), { viewModel.recognize(text) }, viewModel::clear)
        }
        if (!viewModel.available) item { Text(stringResource(R.string.tts_name_unavailable)) }
        state.error?.let { error -> item {
            Text(stringResource(R.string.tts_name_error, error), color = MaterialTheme.colorScheme.error)
        } }
        state.candidates?.let { candidates ->
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tts_name_results), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Text(candidates.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (candidates.isEmpty()) item { Text(stringResource(R.string.tts_name_empty)) }
            else item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val highlight = SpanStyle(background = MaterialTheme.colorScheme.secondaryContainer,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(buildAnnotatedString {
                            var offset = 0
                            candidates.forEach { candidate ->
                                append(text.substring(offset, candidate.start))
                                withStyle(highlight) { append(text.substring(candidate.start, candidate.end)) }
                                offset = candidate.end
                            }
                            append(text.substring(offset))
                        }, Modifier.testTag("name-test-result"), style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            candidates.forEach { Text(it.name, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NameTestInput(value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        TextField(value, onChange, modifier.fillMaxWidth(),
            singleLine = singleLine, minLines = if (singleLine) 1 else 7, maxLines = if (singleLine) 1 else 12,
            textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
internal fun NameTestAction(running: Boolean, enabled: Boolean, label: String, onRun: () -> Unit, onCancel: () -> Unit) {
    Button(onClick = if (running) onCancel else onRun, enabled = running || enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
        if (running) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.play_arrow_24px), null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (running) stringResource(R.string.cancel) else label)
    }
}
