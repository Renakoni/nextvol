package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.BrokerWebSocket
import hnovel.network.BrokerWebSocketMessage
import hnovel.network.ResourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom

/** MultiTTS F6.e: authenticated Doubao voices and AAC WebSocket synthesis. */
internal class DoubaoSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
    private val webSocket: suspend (BrokerRequest, (BrokerWebSocket) -> ByteArray) -> ByteArray,
) : OnlineSpeechBackend {
    private val speaker = metadata.getValue("voice").jsonObject.string("code")
    private val cookie = metadata.getValue("engine").jsonObject.string("param").takeIf(String::isNotEmpty)
        ?.let { Json.parseToJsonElement(it).jsonObject.string("cookie") }.orEmpty()
    private var deviceId = deviceId()
    private var requests = 0

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray = synthesize(text, rate, null)

    suspend fun stream(text: String, rate: Float, receive: (ByteArray, Int, Int) -> Unit) {
        synthesize(text, rate, receive)
    }

    private suspend fun synthesize(text: String, rate: Float, receive: ((ByteArray, Int, Int) -> Unit)?): ByteArray {
        if (requests > 16) { requests = 0; deviceId = deviceId() } else requests++
        val request = BrokerRequest("doubao-speech", "$ENDPOINT?speaker=${URLEncoder.encode(speaker, "UTF-8")}" +
            "&format=aac&speech_rate=${multiTtsControl(rate)}&pitch=0" + sessionQuery(deviceId),
            headers = headers(), kind = ResourceKind.Api, followRedirects = false,
            timeoutMillis = 60_000, maxResponseBytes = MAX_AUDIO_BYTES + 64 * 1024)
        try {
            return webSocket(request) { socket ->
                // The reference concatenates unescaped text; JSON encoding also handles quotes/newlines.
                socket.sendText(buildJsonObject {
                    put("event", "text"); putJsonObject("podcast_extra") { put("role", "") }; put("text", text)
                }.toString())
                socket.sendText("{\"event\":\"finish\"}")
                val audio = DoubaoSpeechAudio(receive)
                while (!audio.receive(socket.readMessage())) { /* event:finish terminates the audio. */ }
                audio.bytes()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { deviceId = deviceId(); throw failure }
    }

    override suspend fun voices(): List<JsonObject> {
        val voices = linkedMapOf<String, JsonObject>()
        for ((type, tab) in listOf(1 to "", 10 to "female", 10 to "male", 10 to "characters", 10 to "accent")) {
            val response = execute(BrokerRequest("doubao-voices", VOICES_URL, method = "POST",
                headers = headers() + ("Content-Type" to "application/json"), body = buildJsonObject {
                    put("page_index", 1); put("page_size", 1000); put("recommend_type", type); put("tab_key", tab)
                }.toString(), kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES))
            for (entry in onlineSpeechJson(response).jsonObject.getValue("data").jsonObject.getValue("ugc_voice_list").jsonArray) {
                val value = entry.jsonObject
                val id = value.string("style_id")
                if (id in voices) continue
                val tags = value.getValue("tag_list").jsonArray.map { it.jsonObject }
                val language = value.string("language_code")
                val region = when (language) { "zh" -> "CN"; "fr" -> "FR"; "es" -> "ES"; else -> "US" }
                val code = id.trim { Character.isWhitespace(it) }
                voices[id] = buildJsonObject {
                    put("group", "doubao"); put("code", code); put("avatar", "$code.jpg")
                    put("name", value.string("name").replace("的声音", "").trim { Character.isWhitespace(it) })
                    put("locale", "$language-$region"); put("sampleRate", 24000); put("type", 1)
                    put("gender", if (tags.any { it["tag_type"]?.jsonPrimitive?.int == 1 &&
                        (it.string("tag_value") == "男" || it.string("tag_value").equals("male", true)) }) 1 else 0)
                    put("desc", tags.filter { it["tag_type"]?.jsonPrimitive?.int != 1 }.joinToString(", ") { it.string("tag_value") })
                }
            }
        }
        return voices.values.toList()
    }

    private fun headers(): Map<String, String> {
        if (cookie.isEmpty()) throw HttpSpeechException(HttpSpeechError.LoginRequired)
        return mapOf("Origin" to "chrome-extension://capohkkfagimodmlpnahjoijgoocdjhd",
            "User-Agent" to MULTITTS_WEB_USER_AGENT, "Cookie" to cookie)
    }

    companion object {
        const val ENDPOINT = "https://ws-samantha.doubao.com/samantha/audio/tts"
        const val VOICES_URL = "https://www.doubao.com/alice/user_voice/recommend?language=zh&browser_language=en-US"

        private fun deviceId() = ThreadLocalRandom.current().let { "${it.nextLong(100000000, 1000000000)}${it.nextLong(100000000, 1000000000)}" }

        private fun sessionQuery(id: String) = "&mode=0&language=zh&browser_language=en-US&device_platform=web" +
            "&aid=586861&real_aid=586861&pkg_type=release_version&device_id=$id&tea_uuid=$id&web_id=$id" +
            "&is_new_user=0&region=US&sys_region=US&use-olympus-account=1&samantha_web=1" +
            "&version=1.20.1&version_code=20800&pc_version=1.20.1%60"
    }
}

internal class DoubaoSpeechAudio(private val receive: ((ByteArray, Int, Int) -> Unit)? = null) {
    private val output = ByteArrayOutputStream()
    private var size = 0

    fun receive(message: BrokerWebSocketMessage): Boolean = when (message) {
        is BrokerWebSocketMessage.Binary -> {
            if (message.bytes.size > MAX_AUDIO_BYTES - size) throw HttpSpeechException(HttpSpeechError.TooLarge)
            size += message.bytes.size
            if (receive == null) output.write(message.bytes) else receive.invoke(message.bytes, 0, message.bytes.size)
            false
        }
        is BrokerWebSocketMessage.Text -> {
            if (message.text.contains("\"message\":\"block\"")) throw HttpSpeechException(HttpSpeechError.Authentication)
            message.text.contains("\"event\":\"finish\"")
        }
    }

    fun bytes(): ByteArray {
        if (size == 0) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        return output.toByteArray()
    }
}
