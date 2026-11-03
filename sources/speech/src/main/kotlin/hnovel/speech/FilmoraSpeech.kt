package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Locale

/** MultiTTS 1.8.3 F6.g: generated session token, task submission, polling and audio download. */
internal class FilmoraSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject.string("code")
    private val language = Locale.getDefault().let { "${it.language}-${it.country}" }
    private var token = newToken()

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        try {
            val body = buildJsonObject {
                put("lang_code", language)
                put("voice_code", voice)
                put("speech_rate", ((rate * 50).toDouble() * 0.08 - 3).toInt())
                put("volume", ((volume * 50).toDouble() * 0.1 - 5).toInt())
                putJsonArray("task_contents") { add(buildJsonObject {
                    put("task_index", 1); put("sentence", text)
                }) }
                put("model_id", "eleven_multilingual_v2")
                put("url_alias", "v5_voice_tts_internal_transfer_no_consume")
                put("pitch_rate", 0); put("ver", 2); put("platform_id", 10)
            }
            val task = post(ENDPOINT, body, SYNTHESIS_SIGNATURE, "1734030144540")
                .getValue("data").jsonObject.getValue("task_id").jsonPrimitive.content
            val url = audioUrl(task)
            return onlineSpeechAudio(execute(BrokerRequest("filmora-audio", url,
                headers = headers(), kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            token = newToken()
            throw failure
        }
    }

    private suspend fun audioUrl(task: String): String {
        val body = buildJsonObject {
            put("task_id", task); put("url_alias", "v5_voice_tts_internal_result_task_id_no_consume")
        }
        repeat(15) {
            val data = post(ENDPOINT, body, RESULT_SIGNATURE, "1734030147020").getValue("data").jsonObject
            when (data.getValue("status").jsonPrimitive.int) {
                1, 3 -> return data.getValue("list").jsonArray.first().jsonObject
                    .getValue("download_url").jsonPrimitive.content.takeIf(String::isNotEmpty)
                    ?: throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                2 -> delay(200)
                else -> throw HttpSpeechException(HttpSpeechError.InvalidAudio)
            }
        }
        throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    }

    override suspend fun voices(): List<JsonObject> {
        val response = post(VOICES_URL, buildJsonObject {
            put("device_lang", "en-us"); put("text_lang", "en-US")
        }, VOICES_SIGNATURE, "1734030100738")
        val voices = linkedMapOf<String, JsonObject>()
        val descriptions = linkedMapOf<String, LinkedHashSet<String>>()
        for (group in response.getValue("data").jsonArray) {
            for (item in group.jsonObject.getValue("list").jsonArray) {
                val entry = item.jsonObject
                val code = entry.getValue("voice_code").jsonPrimitive.content
                if (code !in voices) {
                    val name = entry.getValue("show_name").jsonPrimitive.content
                    voices[code] = buildJsonObject {
                        put("code", code); put("name", name); put("avatar", "$name.png")
                        put("locale", entry.getValue("lang_code"))
                        put("gender", if (entry.getValue("sex").jsonPrimitive.int == 1) 1 else 0)
                        put("sampleRate", 44100); put("type", 1)
                    }
                    descriptions[code] = linkedSetOf()
                }
                descriptions.getValue(code).add(entry.getValue("classify").jsonPrimitive.content)
                entry.getValue("tag").jsonArray.map { it.jsonPrimitive.content }
                    .filter { it != "Child" }.forEach { descriptions.getValue(code).add(it) }
            }
        }
        return voices.map { (code, voice) -> JsonObject(voice +
            ("desc" to JsonPrimitive(descriptions.getValue(code).joinToString(", ")))) }
    }

    private suspend fun post(url: String, body: JsonObject, signature: String, timestamp: String): JsonObject =
        onlineSpeechJson(execute(BrokerRequest("filmora", url, method = "POST", body = body.toString(),
            headers = headers() + mapOf("x-signature" to signature, "x-timestamp" to timestamp,
                "content-type" to "application/json"), kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES))).jsonObject

    private fun headers() = mapOf(
        "x-client-type" to "4", "x-client-sn" to "5b3313416fe18e739860f16b5bd4ced2",
        "x-app-key" to "a57b8e91c3a5ef000619cd9f910f4be7", "x-prod-id" to "1937",
        "user-agent" to "com.wondershare.filmorago13861", "x-prod-ver" to "13.8.61",
        "x-plat-id" to "1937", "x-prod-name" to "FilmoraV13_Android", "x-ver" to "",
        "x-lang" to language.lowercase(Locale.US), "authorization" to "Bearer $token", "x-user-type" to "1937",
    )

    companion object {
        const val ENDPOINT = "https://ct-api.wondershare.cc/v2/ctsrv/relay_app"
        private const val VOICES_URL = "https://ct-api.wondershare.cc/v2/ctsrv/fxm_music_tag"
        // The shipped client uses these fixed values; only the 23-character bearer token is generated.
        private const val SYNTHESIS_SIGNATURE = "740cfb16c4c6b6ee962f43a311b5370fc2b8673483e06d7d2230a512008edb3c"
        private const val RESULT_SIGNATURE = "47601a772fb88e9bb9e7c2f3567193b21f8d68449ae0cae7b76608198033fb9a"
        private const val VOICES_SIGNATURE = "604ab173876cce34c2f9b96b1366092ade0440541e491af05083824a29b5bcde"

        private fun newToken(): String {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val random = SecureRandom()
            return buildString { repeat(23) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
    }
}
