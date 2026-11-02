package hnovel.speech

import hnovel.network.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal class EdgeSpeechClient(
    voice: JsonObject,
    private val execute: suspend (BrokerRequest, (BrokerWebSocket) -> ByteArray) -> ByteArray,
) {
    private val name = java.util.regex.Pattern.compile(",").split(voice.string("param")).firstOrNull()
        .orEmpty().trim { Character.isWhitespace(it) }.let { parameter ->
            if (!parameter.startsWith('@')) parameter else nativeSpeechExtensions(voice.string("extendUI"))[parameter].orEmpty()
        }

    suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        if (name.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidSource)
        val id = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(now))
        val request = BrokerRequest("edge-speech",
            "$ENDPOINT?TrustedClientToken=${EdgeGec.TRUSTED_CLIENT_TOKEN}&ConnectionId=$id" +
                "&Sec-MS-GEC=${EdgeGec.token(now)}&Sec-MS-GEC-Version=${EdgeGec.VERSION}",
            headers = mapOf("Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
                "Accept-Language" to "en-US,en;q=0.9", "Cookie" to "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)};"),
            kind = ResourceKind.Api, followRedirects = false, timeoutMillis = 60_000, maxResponseBytes = MAX_AUDIO_BYTES + 64 * 1024)
        return execute(request) { socket ->
            socket.sendText("X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}")
            socket.sendText("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n" +
                ssml(name, text, rate, volume))
            val audio = EdgeSpeechAudio()
            while (!audio.receive(socket.readMessage())) { /* turn.end owns completion. */ }
            audio.bytes()
        }
    }

    companion object {
        const val ENDPOINT = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        internal fun ssml(voice: String, text: String, rate: Float, volume: Float) =
            "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">" +
                "<voice name=\"${xmlSpeech(voice)}\"><prosody rate=\"${(rate * 40).toInt()}%\" volume=\"${(volume * 50).toInt()}%\" " +
                "pitch=\"+0Hz\">${xmlSpeech(text)}</prosody></voice></speak>"
    }
}

/** Edge's message header is distinct from WebSocket framing. Only turn.end publishes the accumulated MP3. */
internal class EdgeSpeechAudio {
    private val audio = ByteArrayOutputStream()

    fun receive(message: BrokerWebSocketMessage): Boolean {
        when (message) {
            is BrokerWebSocketMessage.Text -> {
                val header = message.text.substringBefore("\r\n\r\n")
                when (headers(header)["Path"]) {
                    "turn.start", "audio.metadata" -> Unit
                    "response" -> {
                        val body = message.text.substringAfter("\r\n\r\n", "")
                        val response = try { Json.parseToJsonElement(body).jsonObject }
                            catch (_: IllegalArgumentException) { throw HttpSpeechException(HttpSpeechError.InvalidAudio) }
                        if (response["error"] != null) throw HttpSpeechException(HttpSpeechError.Network)
                    }
                    "turn.end" -> return true
                    else -> throw HttpSpeechException(HttpSpeechError.Network)
                }
            }
            is BrokerWebSocketMessage.Binary -> {
                val bytes = message.bytes
                if (bytes.size < 2) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                val start = length + 2
                if (start > bytes.size) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                val fields = headers(bytes.copyOfRange(2, start).toString(Charsets.UTF_8))
                val type = fields["Content-Type"]
                if (fields["Path"] != "audio" || !(type == "audio/mpeg" || type == null && start == bytes.size))
                    throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                if (audio.size() + bytes.size - start > MAX_AUDIO_BYTES) throw HttpSpeechException(HttpSpeechError.TooLarge)
                audio.write(bytes, start, bytes.size - start)
            }
        }
        return false
    }

    fun bytes(): ByteArray = audio.toByteArray().also(::checkSpeechAudioContainer)

    private fun headers(text: String) = text.split("\r\n").filter { ':' in it }
        .associate { it.substringBefore(':') to it.substringAfter(':').trim() }
}
