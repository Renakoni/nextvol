package indi.renakoni.nextvol.tts

import hnovel.speech.*
import indi.renakoni.nextvol.tts.matcha.*
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

/** MultiTTS stores online and offline MSC/Microsoft speakers under the same engine. */
class MscVoiceEditing @Inject constructor(
    private val online: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val pairs: SpeechVoicePoolRepository,
) {
    suspend fun toOnline(previous: InstalledOfflineVoice, voice: InstalledOfflineVoice, copy: Boolean, creating: Boolean) {
        val configuration = requireNotNull(voice.configuration)
        val engine = online.engines().single { it.code == configuration.engine }
        val id = "nextvol:voice:${if (copy) UUID.randomUUID() else voice.reference.id}"
        val draft = newHttpVoice(engine)
        val source = editHttpVoice(draft.copy(definition = previewHttpSpeech(JsonObject(draft.definition.raw +
            ("id" to JsonPrimitive(id))).toString()).sources.single()), voice.speakerFields(), voice.enabled, copy = false)
            .copy(isVoiceCopy = copy || voice.sourceKey.isEmpty(),
                speakerDefaults = voice.defaults?.let { defaults -> voice.copy(name = defaults.name,
                    configuration = defaults.configuration, metadata = defaults.metadata).speakerFields() },
                offlineResources = voice.sizes.takeIf { it.isNotEmpty() }?.let {
                OfflineSpeechResources(voice.resourceReference, it, voice.packageAvatar)
            })
        online.saveVoice(source)
        if (!copy && !creating) {
            replaceReference(SpeechVoiceRef(offlineVoice = previous.reference, externalId = previous.packageVoiceId()),
                SpeechVoiceRef(httpSource = id, label = voice.name, externalId = source.packageVoiceId()))
            offline.delete(voice.reference)
        }
    }

    suspend fun toOffline(source: SavedHttpSpeechSource, edited: InstalledOfflineVoice, copy: Boolean) {
        val engine = requireNotNull(source.httpEngine())
        val existingId = source.definition.id.removePrefix("nextvol:voice:").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        val reference = OfflineVoiceRef(if (copy) UUID.randomUUID().toString() else existingId ?: UUID.randomUUID().toString(),
            UUID.randomUUID().toString())
        val defaults = source.speakerDefaults ?: source.defaultSpeaker()
        val original = defaults.offlineConfiguration(engine.code)
        val voice = offline.create(edited.copy(reference = reference, resourceReference = reference,
            configuration = requireNotNull(edited.configuration).copy(engine = engine.code),
            sourceKey = if (copy || source.isVoiceCopy) "" else "${engine.code}\u0000${original.code}",
            defaults = OfflineVoiceDefaults(defaults["name"]?.jsonPrimitive?.content.orEmpty(), original, defaults.offlineMetadata())),
            source.offlineResources)
        if (!copy) {
            replaceReference(SpeechVoiceRef(httpSource = source.definition.id, externalId = source.packageVoiceId()),
                SpeechVoiceRef(offlineVoice = voice.reference, label = voice.name, externalId = voice.packageVoiceId()))
            online.delete(source.definition.id)
        }
    }

    private suspend fun replaceReference(previous: SpeechVoiceRef, replacement: SpeechVoiceRef) {
        settings.update { it.replaceVoiceReference(previous, replacement) }
        pairs.replaceVoiceReference(previous, replacement)
    }
}

internal fun InstalledOfflineVoice.speakerFields(): JsonObject {
    val configuration = requireNotNull(configuration)
    return buildJsonObject {
        put("code", configuration.code); put("name", name); put("locale", configuration.locale)
        put("param", configuration.rawParameters ?: configuration.parameters); put("extendUI", configuration.extendUI)
        put("speed", configuration.speed); put("pitch", configuration.pitch); put("volume", configuration.volume)
        put("sampleRate", configuration.sampleRate); put("gender", configuration.gender); put("type", configuration.type)
        put("note", metadata.note); put("desc", metadata.description); put("avatar", metadata.avatar)
    }
}

internal fun JsonObject.offlineConfiguration(engine: String): OfflineVoiceConfig {
    fun text(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
    return OfflineVoiceConfig(engine, text("code"), rawParameters = text("param"), extendUI = text("extendUI"),
        locale = text("locale"), speed = text("speed", "1").toFloat(), pitch = text("pitch", "1").toFloat(),
        volume = text("volume", "1").toFloat(), sampleRate = text("sampleRate", "16000").toInt(),
        gender = text("gender", "0").toInt(), type = text("type", "0").toInt())
}

internal fun JsonObject.offlineMetadata() = OfflineVoiceMetadata(
    get("note")?.jsonPrimitive?.content.orEmpty(), get("desc")?.jsonPrimitive?.content.orEmpty(), get("avatar")?.jsonPrimitive?.content.orEmpty())

internal fun SpeechSettings.replaceVoiceReference(previous: SpeechVoiceRef, replacement: SpeechVoiceRef): SpeechSettings {
    fun replace(value: SpeechVoiceRef) = if (value.canonicalKey == previous.canonicalKey) replacement.copy(externalId = value.externalId) else value
    val selected = replace(voiceReference())
    val selections = updateVoiceSelections(previous, replacement)
    return copy(engine = selected.engine, voice = selected.voice, httpSource = selected.httpSource, offlineVoice = selected.offlineVoice,
        httpRevision = UUID.randomUUID().toString(), voiceRevision = UUID.randomUUID().toString(),
        narrationVoices = selections.narrationVoices, dialogueVoices = selections.dialogueVoices,
        speakerVoices = speakerVoices.mapValues { replace(it.value) }, roleRules = roleRules.map { it.copy(speaker = it.speaker?.let(::replace)) },
        voiceItems = voiceItems.map { it.copy(value = replace(it.value)) })
}
