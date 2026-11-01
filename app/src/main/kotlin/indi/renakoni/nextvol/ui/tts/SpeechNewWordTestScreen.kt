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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.names.RealtimeNameCandidate
import indi.renakoni.nextvol.tts.names.SpeechNewWordDiscovery
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeechNewWordTestState(
    val running: Boolean = false,
    val candidates: List<RealtimeNameCandidate>? = null,
    val error: String? = null,
)

@HiltViewModel
class SpeechNewWordTestViewModel @Inject constructor(
    private val discovery: SpeechNewWordDiscovery,
    private val settings: SpeechSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechNewWordTestState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun clear() {
        job?.cancel()
        mutableState.value = SpeechNewWordTestState()
    }

    fun discover(text: String) {
        clear()
        job = viewModelScope.launch {
            mutableState.value = SpeechNewWordTestState(running = true)
            try {
                val pattern = settings.get().text.personNameRegex
                mutableState.value = SpeechNewWordTestState(candidates = discovery.preview(text, pattern))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                mutableState.value = SpeechNewWordTestState(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechNewWordTestScreen(state: SpeechNewWordTestState, onBack: () -> Unit,
    onDiscover: (String) -> Unit, onClear: () -> Unit) {
    val sample = stringResource(R.string.tts_new_word_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    DisposableEffect(Unit) { onDispose(onClear) }
    Scaffold(topBar = {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_new_word_test, onBack)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.tts_new_word_scope), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(text, { text = it; onClear() }, Modifier.fillMaxWidth().testTag("new-word-input"),
                    label = { Text(stringResource(R.string.tts_name_input)) }, minLines = 4, maxLines = 8)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDiscover(text) }, enabled = !state.running && text.isNotBlank()) {
                        Text(stringResource(R.string.tts_new_word_run))
                    }
                    if (state.running) TextButton(onClick = onClear) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item {
                Text(stringResource(R.string.tts_new_word_error, error), color = MaterialTheme.colorScheme.error)
            } }
            state.candidates?.let { candidates ->
                if (candidates.isEmpty()) item { Text(stringResource(R.string.tts_new_word_empty)) }
                else {
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.tts_new_word_candidates), Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.tts_new_word_score), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(candidates) { candidate ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(candidate.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("%.4f".format(candidate.score), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
