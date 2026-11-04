package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.SpeechPackageEngine
import hnovel.speech.SpeechPackageNotice
import hnovel.speech.SpeechPackagePreview
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Speaker.getId(): engine group, code, and the nonzero copy type. Names are not identities. */
internal fun externalSpeechVoiceId(engine: String, code: String, type: Int) =
    "${engine}_$code" + if (type == 0) "" else "_$type"

internal fun speechPackageReferences(online: List<SavedHttpSpeechSource>, offline: List<InstalledOfflineVoice>): Map<String, SpeechVoiceRef> {
    val candidates = online.mapNotNull { source ->
        source.packageIdentity()?.let { (engine, code, type) ->
            val currentType = if (engine == "msctts") (source.speakerData()["type"] as? JsonPrimitive)?.intOrNull ?: 0
                else type.toIntOrNull() ?: return@mapNotNull null
            externalSpeechVoiceId(engine, code, currentType) to
                SpeechVoiceRef(httpSource = source.definition.id, label = source.definition.name)
        }
    } + offline.mapNotNull { source ->
        if (source.sourceKey.isEmpty()) return@mapNotNull null
        val original = source.defaults?.configuration ?: source.configuration ?: return@mapNotNull null
        val type = if (original.engine == "msctts") source.configuration?.type ?: original.type else original.type
        externalSpeechVoiceId(original.engine, original.code, type) to
            SpeechVoiceRef(offlineVoice = source.reference, label = source.name)
    }
    return candidates.groupBy({ it.first }, { it.second }).mapNotNull { (id, references) ->
        references.singleOrNull()?.let { id to it }
    }.toMap()
}

internal fun SpeechVoiceRef.resolvePackageReference(voices: Map<String, SpeechVoiceRef>): SpeechVoiceRef =
    externalId?.let { voices[it]?.copy(externalId = it) } ?: this

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
