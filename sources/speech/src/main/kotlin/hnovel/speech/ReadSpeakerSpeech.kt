package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** MultiTTS F6.r: ReadSpeaker's HTML/form protocol uses the speaker code, not param. */
internal class ReadSpeakerSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject
    private val locale = voice.string("locale").ifEmpty { "en_us" }.replace('-', '_').lowercase(Locale.ROOT)
    private val forwardedFor = ThreadLocalRandom.current().let {
        "${it.nextInt(128) + 8}.${it.nextInt(128) + 32}.${it.nextInt(256)}.${it.nextInt(256)}"
    }

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        // Escape text inside the HTML container so literal markup is spoken instead of interpreted.
        val html = Base64.getEncoder().encodeToString("<div>${xmlSpeech(text)}</div>".toByteArray(Charsets.UTF_8))
        val body = "customerid=17&lang=$locale&url=https%3A%2F%2Fwww.readspeaker.com%2Fdemo%2F" +
            "&voice=${voice.string("code")}&speed=${multiTtsControl(rate) * 2}&readid=rs-kdxxiq1e" +
            "&rsjs_ver=3.8.5_rev2605-wr&synccontainer=rs%3Aspan&wrc=2014018991" +
            "&audioformat=mp3&sync=user&html_base64=${URLEncoder.encode(html, "UTF-8")}"
        return onlineSpeechAudio(execute(BrokerRequest("readspeaker-audio", ENDPOINT, method = "POST", body = body,
            headers = mapOf("X-Forwarded-For" to forwardedFor, "Connection" to "keep-alive",
                "Origin" to "https://www.readspeaker.com", "Referer" to "https://www.readspeaker.com/",
                "Content-Type" to "text/plain;charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"),
            kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES)))
    }

    companion object {
        const val ENDPOINT = "https://app-eu.readspeaker.com/cgi-bin/rsent?logid=17&ver=3.8.5_rev2605-wr&wrc=2014018996"
    }
}
