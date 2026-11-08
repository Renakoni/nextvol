package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerWebSocket
import hnovel.network.BrokerWebSocketMessage
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** MultiTTS Z1.c and the Baidu 6.1.4 SDK's LVTV WebSocket exchange. */
class BaiduOnlineSpeechClient(
    metadata: JsonObject,
    private val webSocket: suspend (BrokerRequest, (BrokerWebSocket) -> ByteArray) -> ByteArray,
    private val decoderFactory: ((ByteArray) -> Unit) -> Decoder,
) : OnlineSpeechBackend {
    interface Decoder : AutoCloseable {
        fun decode(bytes: ByteArray, index: Int)
    }

    private val voice = metadata.getValue("voice").jsonObject
    private val parameters = resolveOfflineParameters(voice.string("param"), voice.string("extendUI")).split(',')
    private val speaker = parameters.getOrNull(0)?.trim()?.takeIf(String::isNotEmpty)?.toInt() ?: 4100
    private val product = parameters.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)?.toInt() ?: 1337
    private val sampleRate = voice["sampleRate"]?.jsonPrimitive?.int ?: 16000
    private val clientId = UUID.randomUUID().toString()

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val serial = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("pdt", product); put("key", "com.baidu.tts.voicesearch"); put("sn", serial)
            put("ctp", 10); put("multiplex", 1); put("tex", text); put("cuid", clientId)
            put("lip", 0); put("spd", (multiTtsControl(rate) * .14).toFloat()); put("pit", 5)
            put("vol", (multiTtsControl(volume) * .15).toFloat())
            put("per", speaker); put("aue", 2); put("ver", "6.1.4.1add0da"); put("rate", 64)
            put("lan", "ZH"); put("xml", 0); put("mode", 0); put("platform", "Android")
            put("audio_ctrl", "{\"pack_size_type\":1}")
        }.toString().toByteArray(Charsets.UTF_8)
        val request = BrokerRequest("baidu-speech", "$ENDPOINT?sn=${UUID.randomUUID()}",
            kind = ResourceKind.Api, followRedirects = false, timeoutMillis = 60_000,
            maxResponseBytes = MAX_AUDIO_BYTES + 64 * 1024)
        return webSocket(request) { socket ->
            val pcm = ByteArrayOutputStream()
            decoderFactory { bytes ->
                if (bytes.size > MAX_AUDIO_BYTES - 44 - pcm.size()) throw HttpSpeechException(HttpSpeechError.TooLarge)
                pcm.write(bytes)
            }.use { decoder ->
                socket.sendBinary(ByteBuffer.allocate(payload.size + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(payload.size + 4); put(1); put(1); put(0); put(1); put(payload)
                }.array())
                while (true) {
                    val frame = (socket.readMessage() as? BrokerWebSocketMessage.Binary)?.bytes ?: continue
                    if (frame.size < 8) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                    if (frame[5] != 1.toByte()) continue
                    if (frame[7] == 95.toByte()) break
                    if (frame[7] != 80.toByte()) continue
                    val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
                    if (frame.size < 12) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                    val length = buffer.getInt(8)
                    if (length < 0 || length > frame.size - 12) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                    val result = Json.parseToJsonElement(frame.decodeToString(12, 12 + length)).jsonObject
                    if (result["err_no"]?.jsonPrimitive?.int != 0) throw HttpSpeechException(HttpSpeechError.Network)
                    if (result.string("sn") != serial) continue
                    val index = result.getValue("idx").jsonPrimitive.int
                    val audioLength = result.getValue("audio_len").jsonPrimitive.int
                    if (audioLength < 0 || audioLength > frame.size - 12 - length) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                    decoder.decode(frame.copyOfRange(12 + length, 12 + length + audioLength), index)
                    if (index < 0) break
                }
            }
            if (pcm.size() == 0) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
            speechPcmWave(pcm.toByteArray(), sampleRate)
        }
    }

    companion object { const val ENDPOINT = "https://tts.baidu.com/ws/sdktts" }
}
