package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.SpeechPackageEngine
import hnovel.speech.SpeechPackageNotice
import hnovel.speech.SpeechPackagePreview
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Speaker.getId(): engine group, code, and the nonzero copy type. Names are not identities. */
internal fun externalSpeechVoiceId(engine: String, code: String, type: Int) =
    "${engine}_$code" + if (type == 0) "" else "_$type"

internal fun SavedHttpSpeechSource.packageVoiceId(): String? {
    val engine = importedEngineCode ?: return null
    val speaker = speakerData()
    val code = (speaker["code"] as? JsonPrimitive)?.contentOrNull ?: return null
    return externalSpeechVoiceId(engine, code, (speaker["type"] as? JsonPrimitive)?.intOrNull ?: 0)
}

internal fun InstalledOfflineVoice.packageVoiceId(): String? = configuration?.let {
    externalSpeechVoiceId(it.engine, it.code, it.type)
}

internal fun speechPackageReferences(online: List<SavedHttpSpeechSource>, offline: List<InstalledOfflineVoice>): Map<String, SpeechVoiceRef> {
    val candidates = online.mapNotNull { source ->
        source.packageVoiceId()?.let { id ->
            id to SpeechVoiceRef(httpSource = source.definition.id, label = source.definition.name)
        }
    } + offline.mapNotNull { source ->
        source.packageVoiceId()?.let { id ->
            id to SpeechVoiceRef(offlineVoice = source.reference, label = source.name)
        }
    }
    return candidates.groupBy({ it.first }, { it.second }).mapNotNull { (id, references) ->
        references.singleOrNull()?.let { id to it }
    }.toMap()
}

internal fun SpeechVoiceRef.resolvePackageReference(voices: Map<String, SpeechVoiceRef>): SpeechVoiceRef =
    externalId?.let { voices[it]?.copy(externalId = it) ?: SpeechVoiceRef(externalId = it, label = label) } ?: this

/** MultiTTS Z6.r.b updates the two selections when a speaker's ID changes, leaving role IDs alone. */
internal fun SpeechSettings.updateVoiceSelections(previous: SpeechVoiceRef, replacement: SpeechVoiceRef): SpeechSettings {
    fun update(voice: SpeechVoiceRef) = if (voice.canonicalKey == previous.canonicalKey ||
        (previous.externalId != null && voice.externalId == previous.externalId))
        replacement.copy(externalId = replacement.externalId.takeIf { voice.externalId != null }) else voice
    return copy(narrationVoices = narrationVoices.map(::update), dialogueVoices = dialogueVoices.map(::update))
}

/** A standalone config.yaml can refer to an already configured engine. */
internal fun SpeechPackagePreview.withInstalledEngines(known: List<SpeechEngineConfig>): SpeechPackagePreview {
    val resolved = engines.map { engine ->
        val existing = known.find { it.code == engine.code && !it.deleted }
            ?: if (hnovel.speech.isOnlineSpeechEngine(engine.code, "inner")) SpeechEngineConfig(engine.code, engine.name) else null
        if (engine.raw != null || existing == null) engine else SpeechPackageEngine(engine.code, existing.name,
            existing.type, existing.httpData().packageValue(), engine.voices, engine.support,
            engine.notices - SpeechPackageNotice.MissingEngine, engine.requiredResources)
    }
    return SpeechPackagePreview(name, kinds, documents, resolved, resources, error)
}
