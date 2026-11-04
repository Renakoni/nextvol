package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*

/** F6.m uses the same JSON/audio.data contract on its extension and web endpoints. */
internal class HuoshanSpeechClient(metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject
    private val url = if (voice.string("param").contains("web")) WEB_URL else CRX_URL
    private val language = voice.string("locale").ifEmpty { "en" }.substringBefore('-')

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val response = execute(BrokerRequest("huoshan-speech", url, method = "POST", headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "chrome-extension://klgfhbiooeogdfodpopgppeadghjjemk",
            "Cookie" to "vtm%253D%257B%2522vtm_aid%2522%253A%2522www.volcengine.com%2522%252C%2522vtm_bid%2522%253A%2522%252Fdocs%252F6561%252F1257544%2522%252C%2522vtm_render_id%2522%253A%2522d47d4dc3-f680-4a0a-8401-fb023a274da4%2522%257D",
            "User-Agent" to MULTITTS_WEB_USER_AGENT), body = buildJsonObject {
                put("speaker", voice.string("code")); put("language", language); put("text", text)
            }.toString(), kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES * 2))
        val audio = (onlineSpeechJson(response).jsonObject["audio"] as? JsonObject)?.string("data")
            ?: throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        return decodeSpeechDataUri("base64://$audio")
    }

    companion object {
        const val CRX_URL = "https://translate.volcengine.com/crx/tts/v1/"
        const val WEB_URL = "https://translate.volcengine.com/web/tts/v1/?msToken=&X-Bogus=DFSzswVLQDatNxPvtKdGa5TQh4C9"
    }
}
