package indi.renakoni.nextvol.tts

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

@Serializable
data class SpeechRequest(val bookId: String, val chapterId: String, val previewText: String? = null,
    val previewSettings: SpeechSettings? = null) {
    val isPreview get() = previewText != null
}

data class SpeechChapter(
    val bookId: String,
    val id: String,
    val bookTitle: String,
    val title: String,
    val text: String,
    val previousId: String? = null,
    val nextId: String? = null,
) {
    val fingerprint = speechDigest("speech-text-v1\u0000$text")
}

enum class SpeechPhase { Stopped, Preparing, Playing, Paused, Buffering, Completed, Failed }

enum class SpeechError {
    NoEngine, EngineUnavailable, MissingVoice, MissingLanguageData, InitializationTimeout,
    SynthesisTimeout, SynthesisFailed, Network, InvalidAudio, Storage, SourceUnavailable,
    SourceTimeout, EmptyText, UnsupportedContent, Playback, ServiceUnavailable,
    HttpSourceUnavailable, HttpPermission, HttpUnsupported, HttpLogin,
}

class SpeechException(val error: SpeechError) : Exception(error.name)

/** UTF-16 range in the processed chapter, independent of reader pagination and audio prefetch. */
data class SpeechPosition(
    val bookId: String,
    val chapterId: String,
    val fingerprint: String,
    val start: Int,
    val end: Int,
    /** May be proportional for audio-only sources; start/end remain the reliable highlight range. */
    val anchor: Int = start,
)

@Stable
data class ReadAloudState(
    val request: SpeechRequest? = null,
    val phase: SpeechPhase = SpeechPhase.Stopped,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val voiceLabel: String = "",
    val segmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val currentText: String = "",
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
    val error: SpeechError? = null,
    val sleepTimerDeadline: Long? = null,
    val showFloatingPlayer: Boolean = false,
    val position: SpeechPosition? = null,
    val sleepTimerChapters: Int? = null,
    /** Frozen duration while paused; sleepTimerDeadline is present only during book playback. */
    val sleepTimerRemainingMillis: Long? = null,
) {
    val isActive get() = phase in setOf(SpeechPhase.Preparing, SpeechPhase.Playing, SpeechPhase.Buffering)
}

data class SpeechEngine(val packageName: String, val name: String)
data class SpeechVoice(val id: String, val locale: String, val needsNetwork: Boolean, val installed: Boolean)
data class SpeechEngineState(val engine: SpeechEngine, val voices: List<SpeechVoice>, val currentVoice: String?)
