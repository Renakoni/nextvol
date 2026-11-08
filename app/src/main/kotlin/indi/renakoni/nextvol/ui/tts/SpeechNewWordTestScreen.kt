package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.names.RealtimeNameCandidate
import indi.renakoni.nextvol.tts.names.SpeechNewWordDiscovery
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
    private val settings: SpeechSettingsEditor,
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

@Composable
internal fun SpeechNewWordTestContent() {
    val viewModel = hiltViewModel<SpeechNewWordTestViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sample = stringResource(R.string.tts_new_word_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    DisposableEffect(viewModel) { onDispose(viewModel::clear) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            NameTestInput(text, { text = it; viewModel.clear() }, stringResource(R.string.tts_name_input),
                Modifier.testTag("new-word-input"))
        }
        item {
            NameTestAction(state.running, text.isNotBlank(), stringResource(R.string.tts_new_word_run),
                { viewModel.discover(text) }, viewModel::clear)
        }
        state.error?.let { error -> item {
            Text(stringResource(R.string.tts_new_word_error, error), color = MaterialTheme.colorScheme.error)
        } }
        state.candidates?.let { candidates ->
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tts_new_word_candidates), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tts_new_word_score), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (candidates.isEmpty()) item { Text(stringResource(R.string.tts_new_word_empty)) }
            else items(candidates) { candidate ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(candidate.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("%.4f".format(candidate.score), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
