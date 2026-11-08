package indi.renakoni.nextvol.tts

import kotlinx.serialization.json.Json
import java.util.Locale

/** Configuration projections only; persistence, routing and runtime lifetimes stay with their owners. */
internal fun SpeechSettings.schemeContent() = copy(schemeId = DEFAULT_SPEECH_SCHEME, schemeName = "", bookId = null,
    engineCacheCapacity = 10, maxCacheVoiceNum = 50, diagnosticLogging = false,
    text = text.copy(recordText = false, recordVoice = false, filterLanguage = false),
    speakerVoices = emptyMap(), speakerBookId = "", voiceRevision = "", httpRevision = "")

internal fun SpeechSettings.runtimeSettings() = SpeechRuntimeSettings(engineCacheCapacity, maxCacheVoiceNum,
    diagnosticLogging, text.recordText, text.recordVoice, text.filterLanguage)

/** The caller selects the scheme; shared runtime and book identity are applied after its stored content. */
internal fun SpeechScheme.effectiveSettings(runtime: SpeechRuntimeSettings, bookId: String? = settings.bookId) =
    settings.copy(schemeId = id, schemeName = name, bookId = bookId,
        engineCacheCapacity = runtime.engineCacheCapacity, maxCacheVoiceNum = runtime.maxCacheVoiceNum,
        diagnosticLogging = runtime.diagnosticLogging,
        text = settings.text.copy(recordText = runtime.recordText,
            recordVoice = runtime.recordVoice, filterLanguage = runtime.filterLanguage))

internal val SpeechSettings.hasVoiceRouting get() =
    voiceRoutingConfigured || narrationVoices.isNotEmpty() || dialogueVoices.isNotEmpty() || speakerVoices.isNotEmpty() || roleRules.any { it.activate } || autoAssignRole

/** Reuse the selected identities, including the installed offline resource revision, without a new schema. */
internal fun SpeechSettings.synthesisSettings(): SpeechSettings {
    val selected = copy(allowMultipleVoices = false, diagnosticLogging = false,
        schemeName = "", text = text.copy(filterLanguage = false), backgroundMusic = SpeechSchemeMusic())
    if (selected.hasVoiceRouting) return selected
    return if (selected.offlineVoice != null || selected.httpSource != null) selected.copy(engine = "", voice = "")
        else selected
}

/** Audition/resolved voice input. Catalog resolution adds the actual HTTP revision after this projection. */
internal fun SpeechSettings.forVoice(reference: SpeechVoiceRef) = copy(
    engine = reference.engine, voice = reference.voice, httpSource = reference.httpSource,
    offlineVoice = reference.offlineVoice, narrationVoices = emptyList(), dialogueVoices = emptyList(),
    speakerVoices = emptyMap(), speakerBookId = "", voiceRoutingConfigured = false, roleRules = emptyList(),
    voiceItems = emptyList(), autoAssignRole = false,
).synthesisSettings()

/** Durable text/context identity, not the full plan or engine cache identity. Keep the v5 encoding unchanged. */
internal fun SpeechSettings.textProcessingKey(maxInputLength: Int, locale: Locale = Locale.getDefault()) =
    speechDigest("speech-text-v5\u0000" + schemeId + "\u0000" + Json.encodeToString(replacements) + "\u0000" +
        Json.encodeToString(text) + "\u0000" + maxInputLength + "\u0000" + locale.toLanguageTag() +
        "\u0000" + Json.encodeToString(narrationVoices) + Json.encodeToString(dialogueVoices) +
        Json.encodeToString(speakerVoices) + speakerBookId + voiceRevision + voiceRoutingConfigured +
        Json.encodeToString(roleRules) + roleContextPattern + Json.encodeToString(voiceItems) + autoAssignRole + maxCacheVoiceNum)
