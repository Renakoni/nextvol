package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.names.SpeechNameCandidate
import indi.renakoni.nextvol.tts.names.SpeechNameRecognizer
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
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
internal fun SpeechNameTestScreen(state: SpeechNameTestState, available: Boolean, onBack: () -> Unit,
    onRecognize: (String) -> Unit, onClear: () -> Unit) {
    val sample = stringResource(R.string.tts_name_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    DisposableEffect(Unit) { onDispose(onClear) }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_name_test, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.tts_name_ner), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.tts_name_scope), Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(text, { text = it; onClear() }, Modifier.fillMaxWidth().testTag("name-test-input"),
                    label = { Text(stringResource(R.string.tts_name_input)) }, minLines = 4, maxLines = 8)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRecognize(text) }, enabled = available && !state.running && text.isNotBlank()) {
                        Text(stringResource(R.string.tts_name_run))
                    }
                    if (state.running) TextButton(onClick = onClear) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (!available) item { Text(stringResource(R.string.tts_name_unavailable), style = MaterialTheme.typography.bodyMedium) }
            if (state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item {
                Text(stringResource(R.string.tts_name_error, error), color = MaterialTheme.colorScheme.error)
            } }
            state.candidates?.let { candidates ->
                item { Text(stringResource(R.string.tts_name_results), style = MaterialTheme.typography.titleSmall) }
                if (candidates.isEmpty()) item { Text(stringResource(R.string.tts_name_empty)) }
                else {
                    item {
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
                        }, Modifier.testTag("name-test-result"))
                    }
                    items(candidates) { candidate ->
                        Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
