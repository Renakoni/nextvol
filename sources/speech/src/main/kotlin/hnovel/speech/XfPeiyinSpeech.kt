package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** MultiTTS 1.8.3 F6.x: request a text-bound signature, then fetch the synthesized audio. */
internal class XfPeiyinSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject.let { speaker ->
        val parameter = speaker.string("param").substringBefore(',').trim { Character.isWhitespace(it) }
        if (parameter.isEmpty()) null
        else if (parameter.startsWith('@')) nativeSpeechExtensions(speaker.string("extendUI"))[parameter] else parameter
    }
    private val forwardedFor = ThreadLocalRandom.current().let {
        "${it.nextInt(128) + 8}.${it.nextInt(128) + 32}.${it.nextInt(256)}.${it.nextInt(256)}"
    }
    private val headers = mapOf("User-Agent" to USER_AGENT, "X-Forwarded-For" to forwardedFor)

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val hash = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val request = buildJsonObject { put("synth_text_hash_code", hash) }.toString()
        val body = buildJsonObject { put("req", Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE, request.toByteArray(Charsets.UTF_8)))) }
        val response = onlineSpeechJson(execute(BrokerRequest("xfpeiyin-sign", SIGN_URL, method = "POST",
            headers = headers + ("Content-Type" to "application/json"), body = body.toString(),
            kind = ResourceKind.Api, maxResponseBytes = 64 * 1024))).jsonObject
        val signature = Json.parseToJsonElement(crypt(Cipher.DECRYPT_MODE,
            Base64.getDecoder().decode(response.getValue("body").jsonPrimitive.content)).toString(Charsets.UTF_8)).jsonObject
        val parameters = linkedMapOf("ts" to signature.getValue("time_stamp").jsonPrimitive.content,
            "sign" to signature.getValue("sign_text").jsonPrimitive.content, "sid" to "", "vid" to voice,
            "volume" to ((multiTtsControl(volume) * 0.4) - 20).toInt().toString(),
            "speed" to (4 * multiTtsControl(rate) - 200).toString(), "content" to text, "listen" to "0")
        val query = parameters.entries.joinToString("&") { (key, value) ->
            if (value == null) key else "$key=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}" }
        return onlineSpeechAudio(execute(BrokerRequest("xfpeiyin-audio", "$SYNTH_URL?$query",
            headers = headers, kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES)))
    }

    private fun crypt(mode: Int, bytes: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(mode, SecretKeySpec("G%.g7\"Y&Nf^40Ee<".toByteArray(Charsets.UTF_8), "AES"))
        doFinal(bytes)
    }

    companion object {
        const val SIGN_URL = "https://peiyin.xunfei.cn/web-server/1.0/works_synth_sign"
        const val SYNTH_URL = "https://peiyin.xunfei.cn/synth"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    }
}
