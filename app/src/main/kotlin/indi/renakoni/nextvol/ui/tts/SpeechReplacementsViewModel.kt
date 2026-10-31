package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeechReplacementsState(
    val rules: List<SpeechReplacementRule> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val storageFailed: Boolean = false,
    val previewing: Boolean = false,
    val preview: SpeechPlan? = null,
    val failure: SpeechReplacementFailure? = null,
    val previewError: SpeechError? = null,
)

@HiltViewModel
class SpeechReplacementsViewModel @Inject constructor(private val repository: SpeechSettingsRepository,
    private val plans: SpeechPlanPreparer) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechReplacementsState())
    val state = mutableState.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            repository.changes.map { it.replacements }.distinctUntilChanged().catch {
                mutableState.update { it.copy(loading = false, storageFailed = true) }
            }.collect { rules ->
                clearPreview()
                mutableState.update { it.copy(rules = rules, loading = false, storageFailed = false) }
            }
        }
    }

    fun save(rule: SpeechReplacementRule, onSaved: () -> Unit) = update(onSaved) { rules -> rules.withDraft(rule) }
    fun toggle(id: String, enabled: Boolean) = update { rules -> rules.map { if (it.id == id) it.copy(activate = enabled) else it } }
    fun delete(id: String) = update { rules -> rules.filterNot { it.id == id } }
    fun move(id: String, delta: Int) = update { rules ->
        val from = rules.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in rules.indices) rules
        else rules.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun update(onSaved: () -> Unit = {}, change: (List<SpeechReplacementRule>) -> List<SpeechReplacementRule>) {
        if (state.value.loading || state.value.saving) return
        mutableState.update { it.copy(saving = true, storageFailed = false) }
        viewModelScope.launch {
            try {
                repository.update { it.copy(replacements = change(it.replacements)) }
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(storageFailed = true) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    fun clearPreview() {
        previewJob?.cancel()
        mutableState.update { it.copy(preview = null, failure = null, previewError = null, previewing = false) }
    }

    fun preview(text: String, draft: SpeechReplacementRule? = null) {
        clearPreview()
        val rules = draft?.let { state.value.rules.withDraft(it) } ?: state.value.rules
        mutableState.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.Default) {
                    plans.prepare(SpeechChapter("", "", "", "", text), SpeechSettings(replacements = rules))
                }
                mutableState.update { it.copy(preview = plan, previewing = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SpeechReplacementException) {
                mutableState.update { it.copy(failure = failure.failure, previewing = false) }
            } catch (failure: SpeechException) {
                mutableState.update { it.copy(previewError = failure.error, previewing = false) }
            }
        }
    }
}

private fun List<SpeechReplacementRule>.withDraft(rule: SpeechReplacementRule): List<SpeechReplacementRule> =
    if (any { it.id == rule.id }) map { if (it.id == rule.id) rule else it } else this + rule
