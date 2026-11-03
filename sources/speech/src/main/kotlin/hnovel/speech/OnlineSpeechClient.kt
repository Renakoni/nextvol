package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** The seven dedicated MultiTTS protocols share the owner's broker and lifetime. */
internal class OnlineSpeechClient(
    metadata: JsonObject,
    execute: suspend (BrokerRequest) -> BrokerResponse,
    stream: suspend (BrokerRequest, (ByteArray, Int, Int) -> Unit) -> BrokerResponse,
) {
    private val engine = metadata.getValue("engine").jsonObject
    private val backend: OnlineSpeechBackend = when (engine.string("code")) {
        "ggtrans" -> GoogleTranslateSpeechClient(metadata, execute)
        "gemini" -> GeminiSpeechClient(metadata, execute)
        "huoshan" -> HuoshanSpeechClient(metadata, execute)
        "namiai" -> NamiSpeechClient(metadata, execute, stream)
        "filmora" -> FilmoraSpeechClient(metadata, execute)
        "xfpeiyin" -> XfPeiyinSpeechClient(metadata, execute)
        "peiyinya" -> PeiyinyaSpeechClient(metadata, execute)
        else -> throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
    }

    suspend fun synthesize(text: String, rate: Float, volume: Float, sentenceLength: Int,
        receive: ((ByteArray, Int, Int) -> Unit)?): ByteArray {
        val parts = mutableListOf<ByteArray>()
        var bytes = 0
        val streaming = backend is NamiSpeechClient && receive != null
        val sink: (ByteArray, Int, Int) -> Unit = { buffer, offset, count ->
            if (count > MAX_AUDIO_BYTES - bytes) throw HttpSpeechException(HttpSpeechError.TooLarge)
            bytes += count
            if (streaming) checkNotNull(receive)(buffer, offset, count)
            else parts += buffer.copyOfRange(offset, offset + count)
        }
        for (part in splitOnlineSpeechText(text, sentenceLength)) {
            currentCoroutineContext().ensureActive()
            if (backend is NamiSpeechClient && streaming) backend.stream(part, sink)
            else {
                val audio = backend.synthesize(part, rate, volume)
                currentCoroutineContext().ensureActive()
                sink(audio, 0, audio.size)
            }
        }
        if (bytes == 0) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        val audio = onlineSpeechBytes(parts)
        if (!streaming && receive != null) {
            receive(audio, 0, audio.size)
            return byteArrayOf()
        }
        return audio
    }

    suspend fun voices(): List<HttpSpeechDefinition> = backend.voices().map { voice ->
        currentCoroutineContext().ensureActive()
        HttpSpeechDefinition.parse(buildJsonObject {
            put("name", voice.string("name").ifEmpty { voice.string("code") })
            put("url", onlineSpeechPackageUrl(engine.string("code"), engine.string("param")))
            put("multiTts", buildJsonObject {
                put("engine", engine)
                put("voice", JsonObject(mapOf("group" to JsonPrimitive(engine.string("code")),
                    "speed" to JsonPrimitive(1f), "volume" to JsonPrimitive(1f),
                    "pitch" to JsonPrimitive(1f), "sampleRate" to JsonPrimitive(16000)) + voice))
            })
        })
    }
}
