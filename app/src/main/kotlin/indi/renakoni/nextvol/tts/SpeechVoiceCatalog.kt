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

/** Reads configuration only. Planning must not open models, run scripts or acquire runtime permits. */
class SpeechVoiceCatalog @Inject constructor(private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository, engines: OfflineEngineRepository,
    private val settings: SpeechSettingsRepository, @ApplicationContext private val context: Context) {
    val changes = combine(http.changes, offline.changes, engines.changes) { a, b, c -> Triple(a, b, c) }

    suspend fun choices(): List<SpeechVoiceRef> {
        val selected = settings.get()
        return http.sources().map { SpeechVoiceRef(httpSource = it.definition.id, label = it.definition.name) } +
            offline.voices().map { SpeechVoiceRef(offlineVoice = it.reference, label = it.name) } +
            selected.references().filter { it.httpSource == null && it.offlineVoice == null }.distinctBy { it.key }.map(::systemLabel)
    }

    private fun systemLabel(voice: SpeechVoiceRef) = voice.copy(label = voice.label.ifEmpty {
        voice.voice.ifEmpty { voice.engine.ifEmpty { context.getString(R.string.tts_system_default) } }
    })

    suspend fun resolve(settings: SpeechSettings): Map<String, SpeechResolvedVoice> {
        val online = http.sources().associateBy { it.definition.id }
        val installed = offline.voices().associateBy { it.reference.id }
        return settings.references().associate { reference ->
            val value = when {
                reference.offlineVoice != null -> {
                    val voice = installed[reference.offlineVoice.id]
                    val configuration = voice?.let { offline.configuration(it) }
                    val current = reference.copy(offlineVoice = voice?.reference ?: reference.offlineVoice,
                        label = voice?.name ?: reference.label)
                    SpeechResolvedVoice(current, settings.forVoice(current), speechOfflineInputLimit(configuration?.engine),
                        revision = voice?.let { speechDigest(Json.encodeToString(it.resourceReference) +
                            Json.encodeToString(configuration) + it.enabled + offline.engineConfiguration(it)?.enabled) }.orEmpty(),
                        error = if (voice == null || !voice.enabled || offline.engineConfiguration(voice)?.enabled == false)
                            SpeechError.OfflineUnavailable else null)
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
                            else -> null })
                }
                else -> SpeechResolvedVoice(systemLabel(reference), settings.forVoice(reference), TextToSpeech.getMaxSpeechInputLength())
            }
            reference.key to value
        }
    }

    suspend fun revised(settings: SpeechSettings): SpeechSettings = settings.copy(voiceRevision =
        speechDigest(resolve(settings).values.joinToString("\u0000") { it.reference.key + it.revision + it.error }))
}

internal fun SpeechSettings.references(): List<SpeechVoiceRef> =
    (listOf(voiceReference()) + narrationVoices + dialogueVoices + speakerVoices.values + roleRules.mapNotNull { it.speaker }).distinctBy { it.key }

internal const val SPEECH_HTTP_INPUT_LIMIT = 65536

internal fun speechOfflineInputLimit(engine: String?) = when (engine) {
    "sherpa", "vocalizer" -> 200
    "xiaomi" -> 250
    else -> Int.MAX_VALUE
}
