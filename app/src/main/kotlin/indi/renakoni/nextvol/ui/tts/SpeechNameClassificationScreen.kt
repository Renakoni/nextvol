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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.names.SpeechNameClassification
import indi.renakoni.nextvol.tts.names.SpeechNameClassifier
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
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

@Composable
internal fun SpeechNameClassificationContent() {
    val viewModel = hiltViewModel<SpeechNameClassificationViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    DisposableEffect(viewModel) { onDispose(viewModel::clear) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            NameTestInput(name, { name = it; viewModel.clear() }, stringResource(R.string.tts_name_classification_input),
                Modifier.testTag("name-classification-input"), singleLine = true)
        }
        item {
            NameTestAction(state.running, name.isNotBlank(), stringResource(R.string.tts_name_classification_run),
                { viewModel.classify(name) }, viewModel::clear)
        }
        if (!viewModel.available) item { Text(stringResource(R.string.tts_name_classification_unavailable)) }
        state.error?.let { error -> item {
            Text(stringResource(R.string.tts_name_classification_error, error), color = MaterialTheme.colorScheme.error)
        } }
        state.result?.let { result -> item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (result.hint) {
                        SpeechNameHint.Male -> R.string.tts_name_hint_male
                        SpeechNameHint.Female -> R.string.tts_name_hint_female
                        SpeechNameHint.Unknown -> R.string.tts_name_hint_unknown
                    }), Modifier.testTag("name-classification-result"), style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(when (result.basis) {
                        SpeechNameHintBasis.Model -> R.string.tts_name_basis_model
                        SpeechNameHintBasis.MaleRule -> R.string.tts_name_basis_male
                        SpeechNameHintBasis.FemaleRule -> R.string.tts_name_basis_female
                        SpeechNameHintBasis.None -> R.string.tts_name_basis_none
                    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
    }
}
