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
        val candidates = if (copy || creating) emptyList() else online.sources()
        val baseline = voice.defaults?.configuration ?: configuration
        val identity = if (voice.sourceKey.isEmpty()) null else Triple(configuration.engine, baseline.code, baseline.type.toString())
        val target = candidates.firstOrNull { identity != null && it.packageIdentity() == identity } ?: candidates.firstOrNull {
            !it.isVoiceCopy && it.importedEngineCode == configuration.engine && it.speakerData().let { data ->
                data["code"]?.jsonPrimitive?.content == configuration.code && data["type"]?.jsonPrimitive?.intOrNull == configuration.type
            }
        }
        val source = if (target != null) editHttpVoice(target, voice.speakerFields(), voice.enabled, copy = false)
        else {
            val id = "nextvol:voice:${if (copy) UUID.randomUUID() else voice.reference.id}"
            val draft = newHttpVoice(engine)
            editHttpVoice(draft.copy(definition = previewHttpSpeech(JsonObject(draft.definition.raw +
                ("id" to JsonPrimitive(id))).toString()).sources.single()), voice.speakerFields(), voice.enabled, copy = false)
                .copy(isVoiceCopy = copy || voice.sourceKey.isEmpty(),
                    speakerDefaults = voice.defaults?.let { defaults -> voice.copy(name = defaults.name,
                        configuration = defaults.configuration, metadata = defaults.metadata).speakerFields() })
        }
        val retained = target?.offlineResources?.takeIf { offline.matchesResources(configuration, it.sizes) }
        online.saveVoice(source.copy(offlineResources = retained ?: voice.sizes.takeIf { it.isNotEmpty() }?.let {
            OfflineSpeechResources(voice.resourceReference, it, voice.packageAvatar)
        }))
        if (!copy && !creating) {
            replaceReference(SpeechVoiceRef(offlineVoice = previous.reference, externalId = previous.packageVoiceId()),
                SpeechVoiceRef(httpSource = source.definition.id, label = voice.name, externalId = source.packageVoiceId()),
                target?.let { SpeechVoiceRef(httpSource = it.definition.id, externalId = it.packageVoiceId()) })
            offline.delete(voice.reference)
        }
    }

    suspend fun toOffline(source: SavedHttpSpeechSource, edited: InstalledOfflineVoice, copy: Boolean) {
        val engine = requireNotNull(source.httpEngine())
        val configuration = requireNotNull(edited.configuration).copy(engine = engine.code)
        val defaults = source.speakerDefaults ?: source.defaultSpeaker()
        val original = defaults.offlineConfiguration(engine.code)
        val sourceKey = if (copy || source.isVoiceCopy) "" else "${engine.code}\u0000${original.code}"
        val candidates = if (copy || online.sources().none { it.definition.id == source.definition.id }) emptyList() else offline.voices()
        val target = candidates.firstOrNull { sourceKey.isNotEmpty() && it.sourceKey == sourceKey } ?: candidates.firstOrNull {
            it.sourceKey.isNotEmpty() && it.configuration?.let { current ->
                current.engine == engine.code && current.code == configuration.code && current.type == configuration.type
            } == true
        }
        val voice = if (target != null) offline.save(target.copy(name = edited.name, configuration = configuration,
            enabled = edited.enabled, metadata = edited.metadata), retained = source.offlineResources)
        else {
            val existingId = source.definition.id.removePrefix("nextvol:voice:").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            val reference = OfflineVoiceRef(if (copy) UUID.randomUUID().toString() else existingId ?: UUID.randomUUID().toString(),
                UUID.randomUUID().toString())
            offline.create(edited.copy(reference = reference, resourceReference = reference, configuration = configuration,
                sourceKey = sourceKey,
                defaults = OfflineVoiceDefaults(defaults["name"]?.jsonPrimitive?.content.orEmpty(), original, defaults.offlineMetadata())),
                source.offlineResources)
        }
        if (!copy) {
            replaceReference(SpeechVoiceRef(httpSource = source.definition.id, externalId = source.packageVoiceId()),
                SpeechVoiceRef(offlineVoice = voice.reference, label = voice.name, externalId = voice.packageVoiceId()),
                target?.let { SpeechVoiceRef(offlineVoice = it.reference, externalId = it.packageVoiceId()) })
            online.delete(source.definition.id)
        }
    }

    internal suspend fun replaceReference(previous: SpeechVoiceRef, replacement: SpeechVoiceRef, overwritten: SpeechVoiceRef? = null) {
        settings.updateAll {
            val updated = it.replaceVoiceReference(previous, replacement)
            if (overwritten == null) updated else updated.updateVoiceSelections(overwritten, replacement)
        }
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
    val selections = updateVoiceSelections(previous, replacement)
    if (selections == this && references().none { it.canonicalKey == previous.canonicalKey }) return this
    val selected = replace(voiceReference())
    return copy(engine = selected.engine, voice = selected.voice, httpSource = selected.httpSource, offlineVoice = selected.offlineVoice,
        httpRevision = UUID.randomUUID().toString(), voiceRevision = UUID.randomUUID().toString(),
        narrationVoices = selections.narrationVoices, dialogueVoices = selections.dialogueVoices,
        speakerVoices = speakerVoices.mapValues { replace(it.value) }, roleRules = roleRules.map { it.copy(speaker = it.speaker?.let(::replace)) },
        voiceItems = voiceItems.map { it.copy(value = replace(it.value)) })
}
