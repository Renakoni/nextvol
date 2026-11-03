package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.CancellationException
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
        fun countBytes(count: Int) {
            if (count > MAX_AUDIO_BYTES - bytes) throw HttpSpeechException(HttpSpeechError.TooLarge)
            bytes += count
        }
        val sink = receive?.takeIf { backend is NamiSpeechClient }?.let { output ->
            { buffer: ByteArray, offset: Int, count: Int ->
                countBytes(count)
                output(buffer, offset, count)
            }
        }
        for (part in splitOnlineSpeechText(text, sentenceLength)) {
            currentCoroutineContext().ensureActive()
            // Malformed service payloads are response failures, not invalid saved source settings.
            val audio = try {
                if (backend is NamiSpeechClient && sink != null) {
                    backend.stream(part, sink)
                    null
                } else backend.synthesize(part, rate, volume)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                throw failure as? HttpSpeechException ?: HttpSpeechException(HttpSpeechError.InvalidAudio)
            }
            if (audio != null) {
                currentCoroutineContext().ensureActive()
                countBytes(audio.size)
                parts += audio
            }
        }
        if (bytes == 0) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        if (sink != null) return byteArrayOf()
        val audio = onlineSpeechBytes(parts)
        if (receive != null) {
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
