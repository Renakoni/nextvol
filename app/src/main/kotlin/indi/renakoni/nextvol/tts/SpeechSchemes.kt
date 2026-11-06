package indi.renakoni.nextvol.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

const val DEFAULT_SPEECH_SCHEME = "default"

@Serializable
data class SpeechSchemeMusic(val enabled: Boolean = false, val selected: Set<String> = emptySet(), val volume: Float = 0.5f)

@Serializable
data class SpeechScheme(val id: String, val name: String = "",
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val settings: SpeechSettings = SpeechSettings())

@Serializable
data class SpeechRuntimeSettings(val engineCacheCapacity: Int = 10, val maxCacheVoiceNum: Int = 50,
    val diagnosticLogging: Boolean = false, val recordText: Boolean = false, val recordVoice: Boolean = false,
    val filterLanguage: Boolean = false)

@Serializable
data class SpeechSchemes(
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault
    val items: List<SpeechScheme> = listOf(SpeechScheme(DEFAULT_SPEECH_SCHEME)),
    val defaultId: String = DEFAULT_SPEECH_SCHEME,
    val bindings: Map<String, String> = emptyMap(),
    val runtime: SpeechRuntimeSettings = SpeechRuntimeSettings(),
) {
    fun effectiveId(bookId: String?) = bindings[bookId] ?: defaultId

    fun settings(id: String): SpeechSettings {
        val scheme = items.first { it.id == id }
        return scheme.settings.copy(schemeId = id, schemeName = scheme.name,
            engineCacheCapacity = runtime.engineCacheCapacity, maxCacheVoiceNum = runtime.maxCacheVoiceNum,
            diagnosticLogging = runtime.diagnosticLogging,
            text = scheme.settings.text.copy(recordText = runtime.recordText,
                recordVoice = runtime.recordVoice, filterLanguage = runtime.filterLanguage))
    }

    fun forBook(bookId: String?) = settings(effectiveId(bookId)).copy(bookId = bookId)

    internal fun update(id: String, change: (SpeechSettings) -> SpeechSettings): SpeechSchemes {
        val updated = change(settings(id)).validated()
        return copy(items = items.map { if (it.id == id) it.copy(settings = updated.schemeContent()) else it },
            runtime = SpeechRuntimeSettings(updated.engineCacheCapacity, updated.maxCacheVoiceNum,
                updated.diagnosticLogging, updated.text.recordText, updated.text.recordVoice, updated.text.filterLanguage))
    }

    internal fun delete(id: String, replacement: String): SpeechSchemes {
        require(id != replacement && items.any { it.id == replacement })
        return copy(items = items.filterNot { it.id == id }, defaultId = if (defaultId == id) replacement else defaultId,
            bindings = bindings.mapValues { if (it.value == id) replacement else it.value })
    }
}

internal fun SpeechSettings.schemeContent() = copy(schemeId = DEFAULT_SPEECH_SCHEME, schemeName = "", bookId = null,
    engineCacheCapacity = 10, maxCacheVoiceNum = 50, diagnosticLogging = false,
    text = text.copy(recordText = false, recordVoice = false, filterLanguage = false),
    speakerVoices = emptyMap(), speakerBookId = "", voiceRevision = "", httpRevision = "")
