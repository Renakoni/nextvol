package indi.renakoni.nextvol.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.imports.SourceOriginCandidates
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
import java.io.File
import javax.inject.Inject

/** Network directories are fetched only by the user's manual refresh action. */
class SpeechEngineCatalogService @Inject constructor(
    private val repository: HttpSpeechRepository,
    private val cipher: StorageCipher,
    private val authority: ExecutionAuthority,
    private val runner: RuleTaskRunner,
    private val network: SpeechNetworkCheck,
    @ApplicationContext private val context: Context,
) {
    suspend fun refresh(): SpeechError? = withContext(Dispatchers.IO) {
        val engines = repository.engines().filter {
            it.type == "inner" && it.enabled && !it.deleted && supportsSpeechVoiceCatalog(it.code)
        }
        if (engines.isEmpty()) return@withContext null
        try { network.check() } catch (failure: SpeechException) { return@withContext failure.error }
        val sources = repository.sources()
        var error: SpeechError? = null
        for (engine in engines) {
            val source = sources.firstOrNull { it.importedEngineCode == engine.code }
            try { refresh(engine, source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: HttpSpeechException) {
                source?.let { repository.recordDeniedOrigins(it.definition.id, failure.deniedOrigins) }
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

    private suspend fun refresh(engine: SpeechEngineConfig, saved: SavedHttpSpeechSource?) {
        var owner: SourceBroker? = null
        var source = saved
        val result = try {
            val client = if (saved != null) repository.withSource(saved.definition.id) { current, runtime ->
                if (current.importedEngine != engine.httpData()) return@withSource null
                source = current
                val broker = SourceBroker(runtime.toPath(), cipher = cipher).also { owner = it }
                client(current, broker)
            } else {
                // A registered native engine may not have any saved speakers yet.
                val draft = newHttpVoice(engine).let {
                    it.copy(origins = SourceOriginCandidates.speech(it.definition.raw).map { origin -> origin.origin }.distinct())
                }
                source = draft
                val runtime = File(context.noBackupFilesDir, "speech-catalog/${speechDigest(engine.code)}")
                val broker = SourceBroker(runtime.toPath(), cipher = cipher).also { owner = it }
                client(draft, broker)
            }
            client?.use { it.refreshVoices() to it.updatedEngineParameters } ?: return
        } finally { owner?.close() }
        // Saving engine parameters can revoke its source sessions, so close the directory client first.
        repository.saveCatalog(engine, result.first, checkNotNull(source).origins, result.second)
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
