package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone

internal class NamiSpeechClient(metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
    private val executeStream: suspend (BrokerRequest, (ByteArray, Int, Int) -> Unit) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject.string("code")
    private val random = Random()

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray =
        onlineSpeechAudio(execute(request(text)))

    suspend fun stream(text: String, receive: (ByteArray, Int, Int) -> Unit) {
        onlineSpeechStatus(executeStream(request(text), receive))
    }

    private fun request(text: String) = BrokerRequest("namiai-speech", "$URL?roleid=$voice", method = "POST",
        headers = headers(System.currentTimeMillis(), random),
        body = "&text=" + URLEncoder.encode(text, "UTF-8") + "&audio_type=mp3&format=stream",
        kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES)

    override suspend fun voices(): List<JsonObject> {
        val response = execute(BrokerRequest("namiai-voices", VOICES_URL,
            headers = headers(System.currentTimeMillis(), random), kind = ResourceKind.Api,
            maxResponseBytes = MAX_AUDIO_BYTES))
        val entries = onlineSpeechJson(response).jsonObject.getValue("data").jsonObject.getValue("list").jsonArray
        val voices = linkedMapOf<String, JsonObject>()
        for (entry in entries) {
            val value = entry.jsonObject
            val tag = value.string("tag")
            val code = tag.trim { Character.isWhitespace(it) }
            voices[tag] = buildJsonObject {
                put("group", "namiai"); put("code", code)
                put("name", value.string("title").replace(Regex("（.*）"), "").trim { Character.isWhitespace(it) })
                put("locale", "zh-CN"); put("sampleRate", 24000); put("type", 1)
                put("gender", if (code in setOf("zhinao", "wenxin", "tongyi", "lingyi", "xunfei", "hunyuan")) 1 else 0)
            }
        }
        return voices.values.toList()
    }

    companion object {
        const val URL = "https://bot.n.cn/api/tts/v1"
        const val VOICES_URL = "https://bot.n.cn/api/robot/platform"

        /** F6.p.k: the timestamp, browser fingerprint and tokens are generated for every request. */
        internal fun headers(now: Long, random: Random): Map<String, String> {
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }.format(Date(now)) + "+08:00"
            val fingerprint = "chrome1.0zh-CNWin32${MULTITTS_WEB_USER_AGENT}1920x108024https://bot.n.cn/chat"
            val mixed = ((random.nextDouble() * 2147483647.0).toLong() xor
                hash(fingerprint + (fingerprint.length xor 1)).toLong()) * 2147483647L
            val time = (random.nextDouble() + random.nextDouble() + now).toLong()
            val token = "${hash("https://bot.n.cn")}$mixed$time".replace(".", "e").substring(0, 32)
            val userAgent = md5(MULTITTS_WEB_USER_AGENT)
            return mapOf("content-type" to "application/x-www-form-urlencoded", "device-platform" to "Web",
                "timestamp" to timestamp, "access-token" to token,
                "zm-token" to md5("Web${timestamp}1.2$token$userAgent"), "zm-ver" to "1.2", "zm-ua" to userAgent)
        }

        private fun hash(text: String): Int {
            var result = 0
            for (character in text.reversed()) {
                result = ((result shl 6) and 268435455) + character.code + (character.code shl 14)
                val high = result and 266338304
                if (high != 0) result = result xor (high shr 21)
            }
            return result
        }

        private fun md5(text: String): String = MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
