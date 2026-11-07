package indi.renakoni.nextvol.tts

import android.speech.tts.TextToSpeech
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class SpeechRoleEngine(val key: String, val name: String, val voices: List<SpeechVoiceRef>)

/** Reads configuration only. Planning must not open models, run scripts or acquire runtime permits. */
class SpeechVoiceCatalog @Inject constructor(private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository, private val engines: OfflineEngineRepository,
    @ApplicationContext private val context: Context,
    private val pairs: SpeechVoicePoolRepository) {
    val changes = combine(http.changes, offline.changes, engines.changes) { a, b, c -> Triple(a, b, c) }

    suspend fun roleEngines(current: SpeechSettings): List<SpeechRoleEngine> {
        val online = http.sources()
        val installed = offline.voices()
        val configurations = (http.engines() + engines.all()).distinctBy { "${it.type}:${it.code}" }.filter { engine ->
            !engine.deleted || online.any { it.importedEngineCode == engine.code }
        }.sortedBy { it.order }
        val groups = configurations.map { engine ->
            val voices = online.filter { it.importedEngineCode == engine.code }
                .map { SpeechVoiceRef(httpSource = it.definition.id, label = it.definition.name) } +
            installed.filter { (it.configuration?.engine ?: "sherpa") == engine.code }
                .map { SpeechVoiceRef(offlineVoice = it.reference, label = it.name) }
            SpeechRoleEngine("${engine.type}:${engine.code}", engine.name, voices)
        }
        val builtIn = online.filter { it.importedEngineCode == null }.groupBy { it.category.orEmpty() }.map { (group, voices) ->
            SpeechRoleEngine("builtin-http:$group", group.ifEmpty { context.getString(R.string.tts_network_voice) },
                voices.map { SpeechVoiceRef(httpSource = it.definition.id, label = it.definition.name) })
        }
        val system = current.references().filter { it.externalId == null && it.httpSource == null && it.offlineVoice == null }
            .distinctBy { it.key }.map(::systemLabel)
        return groups + builtIn + SpeechRoleEngine("system", context.getString(R.string.tts_system_speech), system)
    }

    private fun systemLabel(voice: SpeechVoiceRef) = voice.copy(label = voice.label.ifEmpty {
        voice.voice.ifEmpty { voice.engine.ifEmpty { context.getString(R.string.tts_system_default) } }
    })

    internal suspend fun packageReferences() = speechPackageReferences(http.sources(), offline.voices())

    suspend fun resolve(settings: SpeechSettings, savedPairs: List<SpeechVoicePair> = emptyList(),
        includeParameters: Boolean = false): Map<String, SpeechResolvedVoice> {
        val online = http.sources().associateBy { it.definition.id }
        val installed = offline.voices().associateBy { it.reference.id }
        val external = speechPackageReferences(online.values.toList(), installed.values.toList())
        return (settings.references() + savedPairs.map { it.value }).distinctBy { it.key }.associate { requested ->
            val reference = requested.resolvePackageReference(external)
            val value = when {
                reference.externalId != null && reference.httpSource == null && reference.offlineVoice == null ->
                    SpeechResolvedVoice(reference, settings.forVoice(reference), SPEECH_HTTP_INPUT_LIMIT, error = SpeechError.MissingVoice)
                reference.offlineVoice != null -> {
                    val voice = installed[reference.offlineVoice.id]
                    val configuration = voice?.let { offline.configuration(it) }
                    val current = reference.copy(offlineVoice = voice?.reference ?: reference.offlineVoice,
                        label = voice?.name ?: reference.label)
                    SpeechResolvedVoice(current, settings.forVoice(current), speechOfflineInputLimit(configuration?.engine),
                        revision = voice?.let { speechDigest(Json.encodeToString(it.resourceReference) +
                            Json.encodeToString(configuration) + it.enabled + offline.engineConfiguration(it)?.enabled) }.orEmpty(),
                        error = if (voice == null || !voice.enabled || offline.engineConfiguration(voice)?.enabled == false)
                            SpeechError.OfflineUnavailable else null,
                        voiceParameters = if (includeParameters) configuration?.let {
                            SpeechAudioParameters(it.speed, it.pitch, it.volume) } else null)
                }
                reference.httpSource != null -> {
                    val voice = online[reference.httpSource]
                    val revision = voice?.let { speechDigest(it.definition.raw.toString() +
                        Json.encodeToString(it.credentials) + it.origins + it.available) }.orEmpty()
                    val current = reference.copy(label = voice?.definition?.name ?: reference.label)
                    SpeechResolvedVoice(current, settings.forVoice(current).copy(httpRevision = revision),
                        SPEECH_HTTP_INPUT_LIMIT, revision = revision,
                        error = when { voice == null || !voice.available -> SpeechError.HttpSourceUnavailable
                            !voice.isConfigured -> SpeechError.HttpLogin
                            else -> null },
                        voiceParameters = if (includeParameters) voice?.let { httpVoiceParameters(it.definition) } else null)
                }
                else -> SpeechResolvedVoice(systemLabel(reference), settings.forVoice(reference), TextToSpeech.getMaxSpeechInputLength())
            }
            reference.key to value
        }
    }

    suspend fun revised(settings: SpeechSettings): SpeechSettings = settings.copy(voiceRevision =
        speechDigest(resolve(settings, pairs.pairs(settings.bookId, settings.schemeId)).values.joinToString("\u0000") { it.reference.key + it.revision + it.error }) + ":" + pairs.revision(settings.bookId, settings.schemeId))
}

internal fun SpeechSettings.references(): List<SpeechVoiceRef> =
    listOf(voiceReference()) + narrationVoices + dialogueVoices + speakerVoices.values + roleRules.mapNotNull { it.speaker } +
        voiceItems.map { it.value }

internal const val SPEECH_HTTP_INPUT_LIMIT = 65536

internal fun speechOfflineInputLimit(engine: String?) = when (engine) {
    "sherpa", "vocalizer" -> 200
    "xiaomi" -> 250
    else -> Int.MAX_VALUE
}
