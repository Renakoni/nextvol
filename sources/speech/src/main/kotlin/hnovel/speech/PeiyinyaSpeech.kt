package hnovel.speech

import hnovel.network.BrokerLimits
import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** MultiTTS 1.8.3 F6.q: AES payloads with RSA-wrapped keys, followed by an audio URL. */
internal class PeiyinyaSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val parameters = metadata.getValue("voice").jsonObject.let { voice ->
        val extensions = nativeSpeechExtensions(voice.string("extendUI"))
        val values = java.util.regex.Pattern.compile(",").split(voice.string("param"))
        (0..2).map { index ->
            val value = values.getOrNull(index)?.trim { Character.isWhitespace(it) }.orEmpty()
            if (value.isEmpty()) { if (index == 0) null else "" }
            else if (value.startsWith('@')) extensions[value] else value
        }
    }

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val body = JsonObject(baseParameters() + buildJsonObject {
            parameters[0]?.let { put("zbid", it) }
            parameters[1]?.let { put("emotion", it) }
            parameters[2]?.let { put("emotiondegree", it) }
            put("speed", multiTtsControl(rate) - 100); put("volume", multiTtsControl(volume)); put("pitch", 0)
            put("audiotype", "0"); put("isurl", "2"); put("viptype", "0")
            put("t", SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date()))
            put("syncttsid", UUID.randomUUID().toString().replace("-", ""))
            put("text", text); put("jytext", text)
        })
        val result = post(ENDPOINT, body)
        val url = (result["model"] as? JsonObject)?.get("audiourl")?.jsonPrimitive?.contentOrNull
            ?: throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        return onlineSpeechAudio(execute(BrokerRequest("peiyinya-audio", url,
            kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES)))
    }

    override suspend fun voices(): List<JsonObject> = peiyinyaVoices(post(VOICES_URL, baseParameters()))

    private suspend fun post(url: String, body: JsonObject): JsonObject {
        val response = execute(BrokerRequest("peiyinya", url, method = "POST", body = encode(body.toString()),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            kind = ResourceKind.Api, maxResponseBytes = if (url == VOICES_URL) BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES else MAX_AUDIO_BYTES))
        onlineSpeechStatus(response)
        return Json.parseToJsonElement(decode(response.text())).jsonObject
    }

    private fun encode(text: String): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val key = ByteArray(16) { alphabet[ThreadLocalRandom.current().nextInt(alphabet.length)].code.toByte() }
        val aes = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(REQUEST_KEY)))) }
        fun encoded(bytes: ByteArray) = URLEncoder.encode(Base64.getEncoder().encodeToString(bytes), "UTF-8")
        return "req=${encoded(aes.doFinal(text.toByteArray(Charsets.UTF_8)))}&sec=${encoded(rsa.doFinal(key))}"
    }

    private fun decode(text: String): String {
        val separator = text.indexOf('&')
        val encrypted = Base64.getDecoder().decode(URLDecoder.decode(text.substring(5, separator), "UTF-8"))
        val wrappedKey = Base64.getDecoder().decode(URLDecoder.decode(text.substring(separator + 5), "UTF-8"))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(RESPONSE_KEY)))) }
        val keyData = ByteArrayOutputStream().use { output ->
            var offset = 0
            while (offset < wrappedKey.size) {
                output.write(rsa.doFinal(wrappedKey, offset, minOf(256, wrappedKey.size - offset)))
                offset += 256
            }
            output.toByteArray().toString(Charsets.UTF_8).takeLast(16).toByteArray(Charsets.UTF_8)
        }
        return Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyData, "AES"))
            doFinal(encrypted).toString(Charsets.UTF_8)
        }
    }

    private fun baseParameters() = buildJsonObject {
        put("ver", "010003"); put("uid", ""); put("qd", "122912"); put("did", "bc7e70491f6b1eb8")
    }

    companion object {
        const val ENDPOINT = "http://pysq.shipook.com/v2app/peiyin/synctts"
        const val VOICES_URL = "http://pysq.shipook.com/v2app/peiyin/music/qryttszhubov5"
        // These are protocol constants shipped in F6.q.c, not account or user credentials.
        private const val REQUEST_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCHhUM2vlHEINGlqySUEjY+VR9IG8nGtFJHLr8/9w9PCahi211CDVH7+0wk6nred0qj4ljtw6IbJS8kDMF57z7sfCnbfk8WNih2z6i01sCL0A8LoUVLvlHWDFpTAUK6qBwUOZzpNVp7syiELt6UtzZQsjU+d9QUWLuVPigzw3YsBwIDAQAB"
        private const val RESPONSE_KEY = "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBALPJXXVGr56SP9mzL9V9eWWwW++k+GwYvo4kOZaTo6ckhX+mpZzx4eoqUGQR+8LZzhd4B23nZNKnxthYsGhIY6QgMjvQw+oMG8HQNHfjOVrlvQc1RFTeHn37kG2wLPdGrZjmPGxdWjXTVSjGtRv+P4BDxfcmBDmyQJBsXRrGL1H/AgMBAAECgYAwVBPkX6D73lJ9odZc7ELC3BqKm+0PmcM3kHNOj9dxKbcdLl3ch4T0HnXHwLHNmiD1XBWbLJWErwcbxxN8pODrnBU6HHyOB2BQME4d46wDx77vVkMBykEPfemlhX7mivuR5yz8ZTepzUDna5xjhYC9WVm7qo8AKILdepAb7jeBoQJBAPfS1rYWBk4u201KBvFsHASO+PLi2ishbZRWucDkLbAdCE8J0AWZBc154lFbLw5I2giL9zouak94uxJxtrEt7LECQQC5t9+5eAsQnmS8oVD2xdtqYArmUbn6zhK6iDH+IRoBt75AhPdsOoKXc3tnRenyCaTg7VihnRGrtqST3LxxYBWvAkEAsUrXqlplVmd1Inz0LKEABk7WUMTz+WknwLiumQ1klhqfCBRoCqknmpE41KUcK+b4fKbNToVILuSbSJ2ramQFcQJAG1rG/DV/Q7pg0hvcpgxsjhWJZXVCNHvn7EulOW48u2YKAszS+MEBfTdIROTaWYksHOk4tc1Bzg3/3buUkgp6BwJADVT4CmjebhpVmg0zxlcYErdBjsf7Z2RCFgzRyfXCZJGtw3fj2ZtYbw31tMPGs2rm01zmTGlOA7VWrNR/vnjLCg=="
    }
}
