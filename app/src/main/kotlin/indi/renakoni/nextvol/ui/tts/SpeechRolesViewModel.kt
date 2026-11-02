package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.inject.Inject

data class SpeechRolesState(
    val settings: SpeechSettings = SpeechSettings(),
    val loading: Boolean = true,
    val catalogLoading: Boolean = true,
    val saving: Boolean = false,
    val draft: SpeechRoleRule? = null,
    val engines: List<SpeechRoleEngine> = emptyList(),
    val engineKey: String? = null,
    val saveError: Int? = null,
    val error: SpeechError? = null,
)

@HiltViewModel
class SpeechRolesViewModel @Inject constructor(
    private val repository: SpeechSettingsRepository,
    private val catalog: SpeechVoiceCatalog,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechRolesState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.changes.collect { settings ->
                val filterChanged = state.value.settings.text.filterLanguage != settings.text.filterLanguage
                mutableState.update { it.copy(settings = settings, loading = false) }
                if (filterChanged) refresh()
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val engines = catalog.roleEngines()
                mutableState.update { it.copy(engines = engines, catalogLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage, catalogLoading = false) } }
        }
    }

    fun editRule(id: String?) {
        val state = state.value
        val existing = state.settings.roleRules.find { it.id == id }
        val engine = if (existing == null) state.engines.firstOrNull()
            else state.engines.find { group -> group.voices.any { it.key == existing.speaker?.key } }
        mutableState.update { it.copy(draft = existing ?: SpeechRoleRule(
            group = state.settings.roleRules.firstOrNull()?.group.orEmpty(), speaker = engine?.voices?.firstOrNull()),
            engineKey = engine?.key, saveError = null, error = null) }
    }

    fun edit(change: (SpeechRoleRule) -> SpeechRoleRule) {
        mutableState.update { it.copy(draft = it.draft?.let(change), saveError = null, error = null) }
    }

    fun selectEngine(key: String) {
        val group = state.value.engines.find { it.key == key } ?: return
        mutableState.update { state -> state.copy(engineKey = key, saveError = null,
            draft = state.draft?.let { draft -> draft.copy(speaker =
                group.voices.find { it.key == draft.speaker?.key } ?: group.voices.firstOrNull()) }) }
    }

    fun save(defaultGroup: String, onSaved: () -> Unit) {
        val draft = state.value.draft ?: return
        val languageMode = draft.mode == 1 || draft.mode != 0 && draft.pattern.startsWith("lang:")
        // Original required fields; compile the actual regex instead of its mistaken name field.
        val error = when {
            draft.name.isEmpty() -> R.string.tts_replace_name_required
            draft.speaker == null -> R.string.tts_role_voice_required
            languageMode && draft.regex.removePrefix("lang:") !in speechDetectionLanguages(state.value.settings.text.languageDetector) ->
                R.string.tts_role_language_required
            languageMode -> null
            !draft.matchBefore && !draft.matchAfter -> R.string.tts_role_direction_required
            else -> try { Pattern.compile(draft.pattern); null }
                catch (_: PatternSyntaxException) { R.string.tts_replace_error_pattern }
        }
        mutableState.update { it.copy(saveError = error) }
        if (error != null) return
        val saved = draft.copy(regex = draft.pattern, group = draft.group.ifEmpty { defaultGroup })
        update(onSaved) { rules -> if (rules.any { it.id == saved.id })
            rules.map { if (it.id == saved.id) saved else it } else rules + saved }
    }

    fun toggle(ids: Set<String>, enabled: Boolean) = update { rules ->
        rules.map { if (it.id in ids) it.copy(activate = enabled) else it }
    }

    fun renameGroup(ids: Set<String>, name: String) {
        if (name.isEmpty()) return
        update { rules -> rules.map { if (it.id in ids) it.copy(group = name) else it } }
    }

    fun delete(ids: Set<String>) = update { rules -> rules.filterNot { it.id in ids } }

    fun order(rules: List<SpeechRoleRule>) = update { current ->
        val rank = rules.mapIndexed { index, rule -> rule.id to index }.toMap()
        current.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    private fun update(onSaved: () -> Unit = {}, change: (List<SpeechRoleRule>) -> List<SpeechRoleRule>) {
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { repository.update { it.copy(roleRules = change(it.roleRules)) }; onSaved() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}

/** b7.h.c swaps same-group rules, or whole groups while preserving their members. */
internal fun moveSpeechRoles(rules: List<SpeechRoleRule>, from: String, to: String): List<SpeechRoleRule> {
    if (from.startsWith("group:") && to.startsWith("group:")) {
        val groups = rules.groupBy { it.group }
        val order = groups.keys.toMutableList()
        val first = order.indexOf(from.removePrefix("group:"))
        val second = order.indexOf(to.removePrefix("group:"))
        if (first < 0 || second < 0) return rules
        Collections.swap(order, first, second)
        return order.flatMap { groups.getValue(it) }
    }
    val first = rules.indexOfFirst { "role:${it.id}" == from }
    val second = rules.indexOfFirst { "role:${it.id}" == to }
    if (first < 0 || second < 0 || rules[first].group != rules[second].group) return rules
    return rules.toMutableList().apply { Collections.swap(this, first, second) }
}
