package indi.renakoni.nextvol.tts

import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import hnovel.network.StorageCipher
import hnovel.speech.*
import indi.renakoni.nextvol.tts.online.AzureSpeechBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import javax.inject.Inject

/** Network directories are fetched only by the user's manual refresh action. */
class SpeechEngineCatalogService @Inject constructor(
    private val repository: HttpSpeechRepository,
    private val cipher: StorageCipher,
    private val authority: ExecutionAuthority,
    private val runner: RuleTaskRunner,
    private val settings: SpeechSettingsRepository,
) {
    suspend fun refresh(): SpeechError? = withContext(Dispatchers.IO) {
        val engines = repository.engines().filter {
            it.type == "inner" && it.enabled && !it.deleted && supportsSpeechVoiceCatalog(it.code)
        }
        if (engines.isEmpty()) return@withContext null
        val filterLanguage = settings.get().text.filterLanguage
        var error: SpeechError? = null
        for (engine in engines) {
            try { refresh(engine, filterLanguage) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: HttpSpeechException) {
                if (error == null) error = when (failure.error) {
                    HttpSpeechError.PermissionDenied -> SpeechError.HttpPermission
                    HttpSpeechError.UnsupportedDependency -> SpeechError.HttpUnsupported
                    HttpSpeechError.InvalidSource, HttpSpeechError.Script -> SpeechError.HttpConfiguration
                    HttpSpeechError.Authentication -> SpeechError.HttpAuthentication
                    HttpSpeechError.LoginRequired -> SpeechError.HttpLogin
                    HttpSpeechError.Network -> SpeechError.Network
                    HttpSpeechError.Timeout -> SpeechError.SynthesisTimeout
                    HttpSpeechError.InvalidAudio, HttpSpeechError.TooLarge -> SpeechError.InvalidAudio
                }
            } catch (failure: Exception) {
                if (error == null) error = failure.speechError(SpeechError.Storage)
            }
        }
        error
    }

    private suspend fun refresh(engine: SpeechEngineConfig, filterLanguage: Boolean) {
        var owner: SourceBroker? = null
        var source: SavedHttpSpeechSource? = null
        val result = try {
            val client = repository.withCatalogSource(engine) { current, runtime ->
                source = current
                val broker = SourceBroker(runtime.toPath(), cipher = cipher).also { owner = it }
                client(current, broker)
            }
            client?.use { it.refreshVoices() to it.updatedEngineParameters } ?: return
        } finally { owner?.close() }
        // Saving engine parameters can revoke its source sessions, so close the directory client first.
        repository.saveCatalog(engine, filterSpeechCatalog(engine.code, result.first, filterLanguage),
            checkNotNull(source).origins, result.second)
    }

    private fun client(source: SavedHttpSpeechSource, broker: SourceBroker) = HttpSpeechClient(
        source.playbackDefinition(), broker, source.origins.map(::NetworkGrant), authority,
        createSdkBackend = { metadata, execute, webSocket ->
            when (metadata.getValue("engine").jsonObject.getValue("code").jsonPrimitive.content) {
                "azure" -> AzureSpeechBackend(metadata, execute)
                "vivo" -> VivoOnlineSpeechBackend(metadata, execute, webSocket)
                else -> throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
            }
        }, run = runner::execute)
}

/** Q6.a.e: filter incoming Microsoft catalogs; saved voices and their bindings are retained. */
internal fun filterSpeechCatalog(engine: String, voices: List<HttpSpeechDefinition>, enabled: Boolean,
    locale: Locale = Locale.getDefault()): List<HttpSpeechDefinition> {
    if (!enabled || engine !in setOf("azure", "clipchamp")) return voices
    return voices.filter {
        val voice = it.raw.getValue("multiTts").jsonObject.getValue("voice").jsonObject
        val language = voice.getValue("locale").jsonPrimitive.content
        language.contains(locale.language + "-") || language.contains("-" + locale.country) || language.contains("en-")
    }
}
