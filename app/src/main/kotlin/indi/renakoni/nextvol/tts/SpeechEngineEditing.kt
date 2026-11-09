package indi.renakoni.nextvol.tts

import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal val SpeechEngineConfig.key get() = "$type:$code"

/** Owns engine edit commits and their existing effect on the selected playback voice. */
class SpeechEngineEditing @Inject constructor(
    private val repository: SpeechEngineRepository,
    private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
) {
    suspend fun save(configuration: SpeechEngineConfig, creating: Boolean, loadedEngines: List<SpeechEngineConfig>) {
        val config = if (creating) {
            val code = configuration.code.trim()
            require(code.matches(Regex("[a-zA-Z0-9_]+")))
            configuration.copy(code = if (configuration.type != "http" || code.startsWith("http_")) code else "http_$code")
        } else configuration
        val old = loadedEngines.find { it.key == config.key }
        val executionChanged = old != null && (old.enabled != config.enabled || old.deleted != config.deleted ||
            old.url != config.url || old.header != config.header || old.parameters != config.parameters)
        withContext(NonCancellable) {
            saveConfiguration(config, creating)
            if (executionChanged) stopAffected(config)
        }
    }

    private suspend fun saveConfiguration(config: SpeechEngineConfig, creating: Boolean) {
        if (config.isOnline) {
            http.saveEngine(config, creating)
        } else if (creating) {
            require(repository.get(config.code) == null)
            repository.register(config)
        } else repository.save(config)
    }

    suspend fun order(keys: List<String>) {
        val ranks = keys.withIndex().associate { it.value to it.index }
        withContext(NonCancellable) { repository.order(ranks); http.orderEngines(ranks) }
    }

    private suspend fun stopAffected(engine: SpeechEngineConfig) {
        val active = controller.state.value.request?.let { it.previewSettings ?: settings.forBook(it.bookId) } ?: settings.get()
        val onlineUsed = if (engine.isOnline) {
            val ids = http.sources().filter { it.importedEngineCode == engine.code }.map { it.definition.id }.toSet()
            active.httpSource in ids
        } else false
        val offlineUsed = run {
            val ids = offline.voices().filter { (it.configuration?.engine ?: "sherpa") == engine.code }.map { it.reference.id }.toSet()
            active.offlineVoice?.id in ids
        }
        if (onlineUsed || offlineUsed) controller.command(SpeechAction.Stop)
    }
}
