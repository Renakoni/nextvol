package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.concurrent.ThreadLocalRandom

/** MultiTTS F6.b/F6.c: a Clipchamp login token obtains a regional Azure session. */
internal class ClipchampSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val parameters = microsoftPackageParameters(metadata.getValue("engine").jsonObject.string("param"))
    private val loginToken = parameters.string("token")
    private val outputFormat = parameters.string("audioFormat").ifEmpty { "audio-24khz-48kbitrate-mono-mp3" }
    private val voice = metadata.getValue("voice").jsonObject.let { original ->
        val parts = original.string("param").split(',').toMutableList()
        val name = parts.first().trim()
        if (name.startsWith('@')) parts[0] = nativeSpeechExtensions(original.string("extendUI"))[name].orEmpty()
        MicrosoftPackageVoice(JsonObject(original + ("param" to JsonPrimitive(parts.joinToString(",")))))
    }
    private class Token(val region: String, val authorization: String)
    private var token: Token? = null
    private var forwardedFor = newForwardedFor()

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val active = authenticate()
        return try {
            val audio = onlineSpeechAudio(execute(BrokerRequest("clipchamp-audio", endpoint(active.region), method = "POST",
                headers = headers(active) + mapOf("Content-Type" to "application/ssml+xml",
                    "X-Microsoft-OutputFormat" to outputFormat), body = voice.ssml(text, rate, volume),
                kind = ResourceKind.Api, followRedirects = false, maxResponseBytes = MAX_AUDIO_BYTES)))
            microsoftFormatAudio(audio, outputFormat)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // The original refreshes after a failed synthesis. Defer the exchange until the next request.
            token = null
            forwardedFor = newForwardedFor()
            throw failure
        }
    }

    override suspend fun voices(): List<JsonObject> {
        val active = authenticate()
        return try {
            microsoftVoiceCatalog(onlineSpeechJson(execute(BrokerRequest("clipchamp-voices",
                "${endpoint(active.region).substringBeforeLast('/')}/voices/list", headers = headers(active),
                kind = ResourceKind.Api, followRedirects = false, maxResponseBytes = MAX_AUDIO_BYTES))).jsonArray)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { token = null; forwardedFor = newForwardedFor(); throw failure }
    }

    private suspend fun authenticate(): Token {
        token?.let { return it }
        if (loginToken.isEmpty()) throw HttpSpeechException(HttpSpeechError.LoginRequired)
        val result = onlineSpeechJson(execute(BrokerRequest("clipchamp-token", AUTH_URL,
            headers = mapOf("Authorization" to "Bearer $loginToken", "Referer" to "https://app.clipchamp.com/editor",
                "Content-Type" to "application/json; charset=utf-8", "User-Agent" to USER_AGENT,
                "X-Forwarded-For" to forwardedFor),
            kind = ResourceKind.Api, followRedirects = false, maxResponseBytes = 64 * 1024))).jsonObject
        return Token(result.getValue("region").jsonPrimitive.content,
            "Bearer ${result.getValue("access_token").jsonPrimitive.content}").also { token = it }
    }

    private fun headers(active: Token) = mapOf("Authorization" to active.authorization, "User-Agent" to USER_AGENT,
        "X-Forwarded-For" to forwardedFor)

    private fun newForwardedFor() = ThreadLocalRandom.current().let {
        "${it.nextInt(128) + 8}.${it.nextInt(128) + 32}.${it.nextInt(256)}.${it.nextInt(256)}"
    }

    companion object {
        const val AUTH_URL = "https://app.clipchamp.com/v2/azure-cognitive/auth-token"
        private fun endpoint(region: String) = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    }
}
