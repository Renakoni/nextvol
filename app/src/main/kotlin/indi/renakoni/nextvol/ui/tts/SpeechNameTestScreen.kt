package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.names.SpeechNameCandidate
import indi.renakoni.nextvol.tts.names.SpeechNameRecognizer
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val saved = rememberSaveableStateHolder()
    val tabs = listOf(
        stringResource(R.string.tts_name_ner),
        stringResource(R.string.tts_new_word_algorithm),
        stringResource(R.string.tts_name_classification_tab),
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_name_test),
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            EditorialPillTabRow(
                tabs = tabs,
                selectedTabIndex = selected,
                onTabSelected = { selected = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            NameTestInput(
                value = text,
                onChange = {
                    text = it
                    viewModel.clear()
                },
                label = stringResource(R.string.tts_name_input),
                modifier = Modifier.testTag("name-test-input"),
            )
        }
        item {
            NameTestAction(
                running = state.running,
                enabled = viewModel.available && text.isNotBlank(),
                label = stringResource(R.string.tts_name_run),
                onRun = { viewModel.recognize(text) },
                onCancel = viewModel::clear,
            )
        }
        if (!viewModel.available) {
            item { Text(stringResource(R.string.tts_name_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        state.error?.let { error ->
            item { Text(stringResource(R.string.tts_name_error, error), color = MaterialTheme.colorScheme.error) }
        }
        state.candidates?.let { candidates ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.tts_name_results),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.W600,
                    )
                    Text(
                        text = candidates.size.toString(),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (candidates.isEmpty()) {
                item { Text(stringResource(R.string.tts_name_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val highlight = SpanStyle(
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = buildAnnotatedString {
                                    var offset = 0
                                    candidates.forEach { candidate ->
                                        append(text.substring(offset, candidate.start))
                                        withStyle(highlight) { append(text.substring(candidate.start, candidate.end)) }
                                        offset = candidate.end
                                    }
                                    append(text.substring(offset))
                                },
                                modifier = Modifier.testTag("name-test-result"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            EditorialHairlineDivider()
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                candidates.forEach {
                                    Text(
                                        text = it.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.W600,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NameTestInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 6,
            maxLines = if (singleLine) 1 else 12,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
        )
    }
}

@Composable
internal fun NameTestAction(
    running: Boolean,
    enabled: Boolean,
    label: String,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Button(
        onClick = if (running) onCancel else onRun,
        enabled = running || enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = CircleShape,
    ) {
        if (running) {
            CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.play_arrow_24px), null, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (running) stringResource(R.string.cancel) else label, fontWeight = FontWeight.W600)
    }
}
