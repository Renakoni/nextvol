package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import indi.renakoni.nextvol.tts.names.SpeechNameClassification
import indi.renakoni.nextvol.tts.names.SpeechNameClassifier
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeechNameClassificationState(
    val running: Boolean = false,
    val result: SpeechNameClassification? = null,
    val error: String? = null,
)

@HiltViewModel
class SpeechNameClassificationViewModel @Inject constructor(
    private val classifier: SpeechNameClassifier,
    private val settings: SpeechSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechNameClassificationState())
    val state = mutableState.asStateFlow()
    val available get() = classifier.available
    private var job: Job? = null

    fun clear() {
        job?.cancel()
        mutableState.value = SpeechNameClassificationState()
    }

    fun classify(name: String) {
        clear()
        job = viewModelScope.launch {
            mutableState.value = SpeechNameClassificationState(running = true)
            try {
                val rules = settings.get().text
                val result = classifier.classify(name, rules.maleNameRegex, rules.femaleNameRegex)
                mutableState.value = SpeechNameClassificationState(result = result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                mutableState.value = SpeechNameClassificationState(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechNameClassificationScreen(state: SpeechNameClassificationState, available: Boolean,
    onBack: () -> Unit, onClassify: (String) -> Unit, onClear: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose(onClear) }
    Scaffold(topBar = {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_name_classification_test, onBack)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.tts_name_classification_scope), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(name, { name = it; onClear() }, Modifier.fillMaxWidth().testTag("name-classification-input"),
                    label = { Text(stringResource(R.string.tts_name_classification_input)) }, singleLine = true)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onClassify(name) }, enabled = !state.running && name.isNotBlank()) {
                        Text(stringResource(R.string.tts_name_classification_run))
                    }
                    if (state.running) TextButton(onClick = onClear) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (!available) item {
                Text(stringResource(R.string.tts_name_classification_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            if (state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item {
                Text(stringResource(R.string.tts_name_classification_error, error), color = MaterialTheme.colorScheme.error)
            } }
            state.result?.let { result -> item {
                Text(stringResource(when (result.hint) {
                    SpeechNameHint.Male -> R.string.tts_name_hint_male
                    SpeechNameHint.Female -> R.string.tts_name_hint_female
                    SpeechNameHint.Unknown -> R.string.tts_name_hint_unknown
                }), Modifier.testTag("name-classification-result"), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(when (result.basis) {
                    SpeechNameHintBasis.Model -> R.string.tts_name_basis_model
                    SpeechNameHintBasis.MaleRule -> R.string.tts_name_basis_male
                    SpeechNameHintBasis.FemaleRule -> R.string.tts_name_basis_female
                    SpeechNameHintBasis.None -> R.string.tts_name_basis_none
                }), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}
