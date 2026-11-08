package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SpeechVoiceOperations @Inject constructor(
    private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
) {
    suspend fun enableHttp(sources: List<SavedHttpSpeechSource>, enabled: Boolean) {
        if (sources.isEmpty()) return
        val ids = sources.map { it.definition.id }.toSet()
        if (!enabled && httpInUse(ids)) controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            http.enable(ids, enabled)
        }
    }

    suspend fun deleteHttp(sources: List<SavedHttpSpeechSource>) {
        if (sources.isEmpty()) return
        val ids = sources.filterNot { it.isBuiltIn }.map { it.definition.id }.toSet()
        if (httpInUse(ids)) controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            http.delete(ids)
            settings.updateAll { current ->
                val selected = current.removeVoiceSelections { it.httpSource in ids }
                if (selected.httpSource in ids) selected.copy(httpSource = null, offlineVoice = null) else selected
            }
        }
    }

    private suspend fun httpInUse(ids: Set<String>): Boolean {
        val request = controller.state.value.request ?: return false
        val active = request.previewSettings ?: settings.forBook(request.bookId)
        return active.references().any { it.httpSource in ids }
    }

    suspend fun deleteOffline(voices: List<InstalledOfflineVoice>) {
        if (voices.isEmpty()) return
        val ids = voices.map { it.reference.id }.toSet()
        if (offlineInUse(ids))
            controller.command(SpeechAction.Stop)
        withContext(NonCancellable) {
            offline.delete(voices.map { it.reference }.toSet())
            settings.updateAll { current ->
                val selected = current.removeVoiceSelections { it.offlineVoice?.id in ids }
                if (selected.offlineVoice?.id in ids) selected.copy(offlineVoice = null, httpSource = null) else selected
            }
        }
    }

    suspend fun enableOffline(voices: List<InstalledOfflineVoice>, enabled: Boolean) {
        if (voices.isEmpty()) return
        val ids = voices.map { it.reference.id }.toSet()
        withContext(NonCancellable) {
            val updated = offline.enable(voices.map { it.reference }.toSet(), enabled)
            settings.updateAll { selected -> updated.find { it.reference.id == selected.offlineVoice?.id && it.reference.id in ids }
                ?.let { selected.copy(offlineVoice = it.reference) } ?: selected }
        }
        if (!enabled && offlineInUse(ids))
            controller.command(SpeechAction.Stop)
    }

    private suspend fun offlineInUse(ids: Set<String>): Boolean {
        val request = controller.state.value.request ?: return false
        val active = request.previewSettings ?: settings.forBook(request.bookId)
        return active.references().any { it.offlineVoice?.id in ids }
    }
}
