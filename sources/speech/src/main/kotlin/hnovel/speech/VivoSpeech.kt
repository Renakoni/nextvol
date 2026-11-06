package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.BrokerWebSocket
import hnovel.network.BrokerWebSocketMessage
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** MultiTTS R4.b and Vivo Speech SDK 5.1.6.11's signed online protocol. */
class VivoSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
    private val webSocket: suspend (BrokerRequest, (BrokerWebSocket) -> ByteArray) -> ByteArray,
    private val decoderFactory: (Int) -> Decoder,
    private val device: Map<String, String> = emptyMap(),
) : OnlineSpeechBackend {
    interface Decoder : AutoCloseable { fun decode(bytes: ByteArray): ByteArray }

    private val voice = metadata.getValue("voice").jsonObject
    private val parameters = resolveOfflineParameters(voice.string("param"), voice.string("extendUI"))
        .takeIf(String::isNotEmpty)?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    private val speaker = parameters.string("voice").ifEmpty { voice.string("code") }
    private val appId = parameters.string("appId").ifEmpty { "3815786070" }
    private val appKey = parameters.string("appKey").ifEmpty { "ICvjiGIoUedqzMih" }
    private val engineType = parameters.string("engineType").ifEmpty { "long_audio_synthesis_screen" }
    private val engineMode = if (parameters.string("engineType").isEmpty()) 1
        else parameters["engineMode"]?.jsonPrimitive?.int ?: 1
    private val sampleRate = voice["sampleRate"]?.jsonPrimitive?.int ?: 16000
    private val userId = UUID.randomUUID().toString()

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        // Mixer mode 5 uses the same online service; device IPC/offline modes need their own runtime.
        if (engineMode != 1 && engineMode != 5) throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
        val requestId = UUID.randomUUID().toString()
        return webSocket(request("/tts")) { socket ->
            val output = ByteArrayOutputStream()
            decoderFactory(sampleRate).use { decoder ->
                socket.sendText(buildJsonObject {
                    put("rdn", 0); put("ent", engineType); put("text", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
                    put("encoding", "utf8"); put("vcn", speaker); put("reg", 2)
                    put("speed", multiTtsControl(rate).coerceAtLeast(40)); put("aue", 1)
                    put("volume", multiTtsControl(volume))
                    put("auf", "audio/L16;rate=${when (sampleRate) { 24000, 32000, 48000 -> sampleRate; else -> 16000 }}")
                    put("pitch", 50); put("reqId", requestId)
                }.toString())
                while (true) {
                    val message = socket.readMessage() as? BrokerWebSocketMessage.Text ?: continue
                    val result = Json.parseToJsonElement(message.text).jsonObject
                    if ((result["error_code"]?.jsonPrimitive?.int ?: 0) != 0)
                        throw HttpSpeechException(HttpSpeechError.Network)
                    if (result.string("req_id").let { it.isNotEmpty() && it != requestId }) continue
                    val data = when (val value = result["data"]) {
                        is JsonObject -> value
                        is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotEmpty)
                            ?.let { Json.parseToJsonElement(it).jsonObject }
                        else -> null
                    } ?: continue
                    val encoded = data.string("audio")
                    if (encoded.isNotEmpty()) {
                        val pcm = decoder.decode(Base64.getDecoder().decode(encoded.replace("\\", "")))
                        if (pcm.size > MAX_AUDIO_BYTES - 44 - output.size()) throw HttpSpeechException(HttpSpeechError.TooLarge)
                        output.write(pcm)
                    }
                    if (data["status"]?.jsonPrimitive?.int in listOf(2, 3)) break
                }
            }
            if (output.size() == 0) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
            speechPcmWave(output.toByteArray(), sampleRate)
        }
    }

    override suspend fun voices(): List<JsonObject> {
        val result = onlineSpeechJson(execute(request("/vcns_config"))).jsonObject
        if (result.getValue("code").jsonPrimitive.int != 0) throw HttpSpeechException(HttpSpeechError.Network)
        return result.getValue("vcn_list").jsonArray.map { item ->
            val entry = item.jsonObject
            buildJsonObject {
                put("group", "vivo"); put("code", entry.string("vcn_id")); put("name", entry.string("vcn_name"))
                put("locale", "zh-CN"); put("sampleRate", 48000); put("type", 1)
                put("param", JsonObject(parameters + ("engineType" to JsonPrimitive(entry.string("engineid"))) +
                    ("voice" to JsonPrimitive(entry.string("vcn_id")))).toString())
            }
        }
    }

    private fun request(path: String): BrokerRequest {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
        val fields = linkedMapOf("android_version" to device["android_version"].orEmpty(), "client_version" to "14.8.9.44",
            "engineid" to engineType, "mfr" to "v", "model" to device["model"].orEmpty(), "net_type" to "1",
            "package" to "com.vivo.agent", "product" to device["product"].orEmpty(), "rom" to device["rom"].orEmpty(),
            "sdk_version" to "5.1.6.11", "system_time" to timestamp, "system_version" to device["system_version"].orEmpty(), "user_id" to userId)
        val query = fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.replace(" ", ""), "UTF-8")}" }
        val canonical = "GET\n$path\n$query\n$appId\n$timestamp\nx-ai-gateway-app-id:$appId\nx-ai-gateway-timestamp:$timestamp\nx-ai-gateway-nonce:$nonce"
        val signature = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(appKey.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
            .doFinal(canonical.toByteArray(Charsets.UTF_8))
        return BrokerRequest("vivo-speech", "https://tts-v2.vivo.com.cn$path?$query", headers = mapOf(
            "X-AI-GATEWAY-APP-ID" to appId, "X-AI-GATEWAY-TIMESTAMP" to timestamp, "X-AI-GATEWAY-NONCE" to nonce,
            "X-AI-GATEWAY-SIGNED-HEADERS" to "x-ai-gateway-app-id;x-ai-gateway-timestamp;x-ai-gateway-nonce",
            "X-AI-GATEWAY-SIGNATURE" to Base64.getEncoder().encodeToString(signature),
            "imei" to userId, "vaid" to userId, "appid" to appId),
            kind = ResourceKind.Api, followRedirects = false, timeoutMillis = 5000, maxResponseBytes = MAX_AUDIO_BYTES + 64 * 1024)
    }
}
