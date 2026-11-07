package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class SpeechVoicePoolDraft(val id: String? = null, val pair: SpeechVoicePair? = null,
    val group: String = "", val person: String = "", val voice: SpeechVoiceRef? = null)

internal data class SpeechVoicePoolState(
    val settings: SpeechSettings = SpeechSettings(),
    val pairs: List<SpeechVoicePair> = emptyList(),
    val engines: List<SpeechRoleEngine> = emptyList(),
    val loading: Boolean = true,
    val catalogLoading: Boolean = true,
    val saving: Boolean = false,
    val draft: SpeechVoicePoolDraft? = null,
    val engineKey: String? = null,
    val error: Int? = null,
)

@HiltViewModel
class SpeechVoicePoolViewModel @Inject constructor(private val settings: SpeechSettingsEditor,
    private val repository: SpeechVoicePoolRepository, private val catalog: SpeechVoiceCatalog) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechVoicePoolState())
    internal val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch { settings.changes.collect { value ->
            val filterChanged = !state.value.loading && state.value.settings.text.filterLanguage != value.text.filterLanguage
            mutableState.update { it.copy(settings = value, loading = false) }
            if (filterChanged) refreshCatalog()
        } }
        viewModelScope.launch { combine(repository.changes, settings.changes) { pairs, selected ->
            pairs.filter { it.schemeId == selected.schemeId && (settings.bookId == null || it.bookId == settings.bookId) }
        }.collect { pairs -> mutableState.update { it.copy(pairs = pairs) } } }
        viewModelScope.launch { catalog.changes.collect { refreshCatalog() } }
    }

    private suspend fun refreshCatalog() {
        try {
            val engines = catalog.roleEngines(settings.get())
            mutableState.update { it.copy(engines = engines, catalogLoading = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = R.string.tts_error_storage, catalogLoading = false) } }
    }

    internal fun editItem(item: SpeechVoiceItem? = null) = open(SpeechVoicePoolDraft(
        id = item?.id, group = item?.group ?: SPEECH_POOL_MALE, voice = item?.value
            ?: state.value.engines.firstOrNull()?.candidates?.firstOrNull()))

    internal fun editPair(pair: SpeechVoicePair) = open(SpeechVoicePoolDraft(
        pair = pair, group = pair.group, person = pair.person, voice = pair.value))

    private fun open(draft: SpeechVoicePoolDraft) = work {
        val voice = draft.voice?.let { voice ->
            if (voice.externalId == null) voice else
                voice.resolvePackageReference(catalog.packageReferences())
        }
        val engine = state.value.engines.find { group -> group.voices.any { it.canonicalKey == voice?.canonicalKey } }
            ?: state.value.engines.firstOrNull().takeIf { draft.id == null && draft.pair == null }
        mutableState.update { it.copy(draft = draft.copy(voice = voice), engineKey = engine?.key, error = null) }
    }

    internal fun edit(change: (SpeechVoicePoolDraft) -> SpeechVoicePoolDraft) {
        mutableState.update { it.copy(draft = it.draft?.let(change), error = null) }
    }

    internal fun dismissEditor() { mutableState.update { it.copy(draft = null, error = null) } }

    internal fun selectEngine(key: String) {
        val engine = state.value.engines.find { it.key == key } ?: return
        mutableState.update { state -> state.copy(engineKey = key, draft = state.draft?.let { draft ->
            draft.copy(voice = draft.voice?.takeIf { voice -> engine.voices.any { it.canonicalKey == voice.canonicalKey } }
                ?: engine.candidates.firstOrNull())
        }, error = null) }
    }

    internal fun selectGroup(group: String) = work {
        val draft = state.value.draft ?: return@work
        edit { it.copy(group = group) }
        if (draft.pair == null || draft.group == group) return@work
        val current = settings.get()
        val voices = catalog.resolve(current)
        // Original g7.d.i draws on role selection. Keep that draw in the draft until confirmation.
        val candidate = current.voiceItems.filter { it.group == group && it.activate }
            .mapNotNull { voices[it.value.key]?.takeIf { it.error == null }?.reference }
            .distinctBy { it.canonicalKey }.randomOrNull() ?: return@work
        val engine = state.value.engines.find { it.voices.any { voice -> voice.canonicalKey == candidate.canonicalKey } }
        mutableState.update { it.copy(draft = it.draft?.copy(voice = candidate), engineKey = engine?.key) }
    }

    internal fun save() {
        val draft = state.value.draft ?: return
        val error = when {
            draft.pair != null && draft.person.isEmpty() -> R.string.tts_replace_name_required
            draft.group.isEmpty() -> R.string.tts_pool_group_required
            draft.voice == null -> R.string.tts_role_voice_required
            else -> null
        }
        mutableState.update { it.copy(error = error) }
        if (error != null) return
        val voice = draft.voice ?: return
        work {
            if (draft.pair != null) repository.setManual(draft.pair.copy(person = draft.person, group = draft.group, value = voice),
                settings.get().activeSpeechPeople())
            else {
                var duplicate = false
                val references = catalog.packageReferences()
                val resolvedVoice = voice.resolvePackageReference(references)
                settings.update { current ->
                    duplicate = current.voiceItems.any {
                        it.id != draft.id && it.group == draft.group &&
                            it.value.resolvePackageReference(references).canonicalKey == resolvedVoice.canonicalKey
                    }
                    if (duplicate) current else {
                        val existing = current.voiceItems.find { it.id == draft.id }
                        val saved = existing?.copy(group = draft.group, value = resolvedVoice, name = resolvedVoice.label)
                            ?: SpeechVoiceItem(group = draft.group, value = resolvedVoice, name = resolvedVoice.label)
                        current.copy(voiceItems = if (existing == null) current.voiceItems + saved
                            else current.voiceItems.map { if (it.id == saved.id) saved else it })
                    }
                }
                if (duplicate) {
                    mutableState.update { it.copy(error = R.string.tts_pool_duplicate) }
                    return@work
                }
            }
            dismissEditor()
        }
    }

    internal fun toggle(ids: Set<String>, enabled: Boolean) = work {
        settings.update { it.copy(voiceItems = it.voiceItems.map { item -> if (item.id in ids) item.copy(activate = enabled) else item }) }
    }

    internal fun deleteItems(ids: Set<String>) = work { settings.update { it.copy(voiceItems = it.voiceItems.filterNot { item -> item.id in ids }) } }
    internal fun order(items: List<SpeechVoiceItem>) = work {
        val rank = items.mapIndexed { index, item -> item.id to index }.toMap()
        settings.update { it.copy(voiceItems = it.voiceItems.sortedBy { item -> rank[item.id] ?: Int.MAX_VALUE }) }
    }
    internal fun deletePairs(ids: Set<String>) = work { repository.remove(ids) }
    internal fun clearPairs(bookId: String?) = work { repository.clear(bookId ?: settings.bookId, settings.get().schemeId) }

    internal fun reassign(ids: Set<String>) = work {
        val current = settings.get()
        val failed = repository.reassign(ids, current.voiceItems, catalog.resolve(current), current.activeSpeechPeople())
        if (failed.isNotEmpty()) mutableState.update { it.copy(error = R.string.tts_pool_no_candidate) }
    }

    private fun work(action: suspend () -> Unit) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = R.string.tts_error_storage) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}

internal fun SpeechSettings.poolGroups(): List<String> =
    (listOf(SPEECH_POOL_MALE, SPEECH_POOL_FEMALE) + roleRules.filter { it.isAbstract }.map { configurationPoolGroup(it.name) }).distinct()

internal fun moveSpeechVoicePool(items: List<SpeechVoiceItem>, from: String, to: String): List<SpeechVoiceItem> {
    if (from.startsWith("group:") && to.startsWith("group:")) {
        val groups = items.groupBy { it.group }
        val order = groups.keys.toMutableList()
        val first = order.indexOf(from.removePrefix("group:"))
        val second = order.indexOf(to.removePrefix("group:"))
        if (first < 0 || second < 0) return items
        java.util.Collections.swap(order, first, second)
        return order.flatMap { groups.getValue(it) }
    }
    val first = items.indexOfFirst { "voice:${it.id}" == from }
    val second = items.indexOfFirst { "voice:${it.id}" == to }
    if (first < 0 || second < 0 || items[first].group != items[second].group) return items
    return items.toMutableList().apply { java.util.Collections.swap(this, first, second) }
}
