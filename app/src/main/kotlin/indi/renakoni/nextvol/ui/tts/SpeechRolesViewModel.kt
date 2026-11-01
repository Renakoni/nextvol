package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeechRolesState(
    val settings: SpeechSettings = SpeechSettings(),
    val bookId: String? = null,
    val bookTitle: String = "",
    val loading: Boolean = true,
    val catalogLoading: Boolean = true,
    val saving: Boolean = false,
    val draft: SpeechRoleRule? = null,
    val choices: List<SpeechVoiceRef> = emptyList(),
    val preview: SpeechPlan? = null,
    val previewing: Boolean = false,
    val failure: SpeechRoleFailure? = null,
    val error: SpeechError? = null,
)

@HiltViewModel
class SpeechRolesViewModel @Inject constructor(
    private val repository: SpeechSettingsRepository,
    private val catalog: SpeechVoiceCatalog,
    private val plans: SpeechPlanPreparer,
    private val chapters: SpeechChapterSource,
    val controller: ReadAloudController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechRolesState())
    val state = mutableState.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            repository.changes.collect { settings ->
                clearPreview()
                mutableState.update { it.copy(settings = settings, loading = false) }
            }
        }
        refresh()
    }

    fun refresh() {
        val playback = controller.state.value
        if (playback.request?.isPreview != true) mutableState.update {
            it.copy(bookId = playback.request?.bookId, bookTitle = playback.bookTitle)
        }
        mutableState.update { it.copy(catalogLoading = true) }
        viewModelScope.launch {
            try {
                val choices = catalog.choices()
                mutableState.update { it.copy(choices = choices) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
            finally { mutableState.update { it.copy(catalogLoading = false) } }
        }
    }

    fun editRule(id: String?, bookOnly: Boolean) {
        clearPreview()
        mutableState.update { it.copy(draft = it.settings.roleRules.find { rule -> rule.id == id }
            ?: SpeechRoleRule(bookId = it.bookId.takeIf { bookOnly }), error = null) }
    }

    fun edit(change: (SpeechRoleRule) -> SpeechRoleRule) {
        clearPreview()
        mutableState.update { it.copy(draft = it.draft?.let(change), error = null) }
    }

    fun save(onSaved: () -> Unit) {
        val draft = state.value.draft ?: return
        update(onSaved) { it.copy(roleRules = it.roleRules.withRole(draft)) }
    }

    fun toggle(id: String, enabled: Boolean) = update { settings ->
        settings.copy(roleRules = settings.roleRules.map { if (it.id == id) it.copy(activate = enabled) else it })
    }

    fun delete(id: String) = update { settings -> settings.copy(roleRules = settings.roleRules.filterNot { it.id == id }) }

    fun move(id: String, delta: Int) = update { settings ->
        val rules = settings.roleRules
        val from = rules.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in rules.indices) settings else settings.copy(roleRules = rules.toMutableList().apply {
            add(to, removeAt(from))
        })
    }

    fun saveContext(pattern: String, onSaved: () -> Unit) = update(onSaved) { it.copy(roleContextPattern = pattern) }

    private fun update(onSaved: () -> Unit = {}, change: (SpeechSettings) -> SpeechSettings) {
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { repository.update(change); onSaved() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    fun audition(rule: SpeechRoleRule, text: String) {
        val voice = rule.speaker
        if (voice == null) mutableState.update { it.copy(error = SpeechError.MissingVoice) }
        else controller.preview(text, state.value.settings.forVoice(voice))
    }

    fun clearPreview() {
        previewJob?.cancel()
        mutableState.update { it.copy(preview = null, previewing = false, failure = null) }
    }

    fun preview(text: String, draft: Boolean = false, currentChapter: Boolean = false) {
        clearPreview()
        val state = state.value
        val selected = if (draft && state.draft != null)
            state.settings.copy(roleRules = state.settings.roleRules.withRole(state.draft.copy(activate = true))) else state.settings
        val playback = controller.state.value
        val request = playback.request?.takeUnless { it.isPreview }
        mutableState.update { it.copy(previewing = true, error = null) }
        previewJob = viewModelScope.launch {
            try {
                val chapter = if (currentChapter && request != null) chapters.load(request.bookId, request.chapterId)
                    else SpeechChapter(state.bookId.orEmpty(), "", state.bookTitle, "", text)
                val context = if (currentChapter && request != null) playback.planContext else SpeechContext()
                val plan = withContext(Dispatchers.Default) { plans.prepare(chapter, selected, context) }
                mutableState.update { it.copy(preview = plan) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SpeechRoleException) { mutableState.update { it.copy(failure = failure.failure) } }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.speechError(SpeechError.SynthesisFailed)) } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(previewing = false) } }
        }
    }
}

private fun List<SpeechRoleRule>.withRole(rule: SpeechRoleRule): List<SpeechRoleRule> =
    if (any { it.id == rule.id }) map { if (it.id == rule.id) rule else it } else this + rule
