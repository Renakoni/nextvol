package indi.renakoni.nextvol.data.logging

import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechVoiceRef

enum class SpeechLogStage { Import, Configuration, Analysis, Request, Initialization, Audio, Playback }
enum class SpeechLogEvent { Started, Completed, Failed }

/** Processing text is explicit debug output; request configuration and exception messages never enter the log. */
data class SpeechLogRecord(
    val stage: SpeechLogStage,
    val event: SpeechLogEvent,
    val error: SpeechError? = null,
    val voice: SpeechVoiceRef? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val subject: String? = null,
    val processId: Long? = null,
    val segment: Int? = null,
    val details: List<Pair<Int, String>> = emptyList(),
    val truncated: Boolean = false,
) {
    val level get() = if (error != null || event == SpeechLogEvent.Failed) LogLevel.ERROR else LogLevel.INFO
}
