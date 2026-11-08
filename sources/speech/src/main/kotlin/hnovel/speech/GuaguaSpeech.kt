package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/** MultiTTS F6.k / F6.j: wxToken -> account -> access token; cvRobotId lives in voice.param. */
internal class GuaguaSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val speaker = metadata.getValue("voice").jsonObject.string("param")
    private val parameters = metadata.getValue("engine").jsonObject.string("param").takeIf(String::isNotEmpty)
        ?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
    private val wxToken = (parameters["wxToken"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private var userId = (parameters["userId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private var accessToken: String? = null
    override val updatedEngineParameters: String? get() =
        if (userId.isNotEmpty() && userId != (parameters["userId"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            JsonObject(parameters + ("userId" to JsonPrimitive(userId))).toString() else null

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val token = token()
        val body = buildJsonObject {
            put("cvRobotId", speaker); put("speedFactor", (multiTtsControl(rate) * 1.5 + 50).toInt())
            put("durationFactorSilence", 100); put("genMode", 0); put("ssml", text)
        }
        try {
            return onlineSpeechAudio(execute(BrokerRequest("guagua-speech", ENDPOINT, method = "POST",
                headers = headers(token), body = body.toString(), kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { accessToken = null; throw failure }
    }

    override suspend fun voices(): List<JsonObject> = try {
        val response = onlineSpeechJson(execute(BrokerRequest("guagua-voices", VOICES_URL,
            headers = headers(token()), kind = ResourceKind.Api, maxResponseBytes = MAX_AUDIO_BYTES))).jsonObject
        response.getValue("data").jsonObject.getValue("list").jsonArray.map { item ->
            val voice = item.jsonObject
            val name = voice.string("name")
            val age = listOf("儿童", "少年", "青年", "中年", "老年")[voice.getValue("ageType").jsonPrimitive.int]
            buildJsonObject {
                put("group", "guagua"); put("type", 1); put("speed", 1.5f); put("volume", 2f)
                put("locale", "zh-CN"); put("sampleRate", 48000); put("name", name)
                put("code", guaguaVoiceCode(name)); put("param", voice.string("id"))
                put("gender", 2 - voice.getValue("gender").jsonPrimitive.int)
                put("desc", (voice.string("genres").replace(";", "、") + "、$age").removePrefix("、"))
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { accessToken = null; throw failure }

    private suspend fun token(): String {
        accessToken?.let { return it }
        if (wxToken.isEmpty()) throw HttpSpeechException(HttpSpeechError.LoginRequired)
        if (userId.isEmpty()) {
            val response = onlineSpeechJson(execute(BrokerRequest("guagua-account", "$PORTAL_URL?token=$wxToken",
                headers = headers(), kind = ResourceKind.Api))).jsonObject
            userId = response["data"]?.jsonObject?.get("list")?.jsonArray?.firstOrNull()?.jsonObject?.string("id").orEmpty()
            if (userId.isEmpty()) throw HttpSpeechException(HttpSpeechError.Authentication)
        }
        val response = onlineSpeechJson(execute(BrokerRequest("guagua-login", LOGIN_URL, method = "POST",
            headers = headers() + ("Authorization" to "Bearer undefined"),
            body = buildJsonObject { put("id", userId); put("token", wxToken) }.toString(), kind = ResourceKind.Api))).jsonObject
        val token = response["data"]?.jsonObject?.string("access_token").orEmpty()
        if (token.isEmpty()) throw HttpSpeechException(HttpSpeechError.Authentication)
        accessToken = token
        return token
    }

    private fun headers(token: String? = null): Map<String, String> = buildMap {
        put("Cookie", "wxToken=$wxToken" + if (token == null) "" else "; access_token=$token")
        put("Authority", "www.gstudios.com.cn"); put("Origin", "https://www.gstudios.com.cn")
        put("Content-Type", "application/json; charset=utf-8"); put("User-Agent", MULTITTS_WEB_USER_AGENT)
        if (token != null) { put("Authorization", "Bearer $token"); put("X-Requested-With", "XMLHttpRequest") }
    }

    companion object {
        const val ENDPOINT = "https://www.gstudios.com.cn/story_v2/api/record/tts/trail"
        const val VOICES_URL = "https://www.gstudios.com.cn/story_v2/api/record/cv/list/robot/trial"
        const val LOGIN_URL = "https://www.gstudios.com.cn/story/open/user/login"
        const val PORTAL_URL = "https://www.gstudios.com.cn/story/open/user/portal"
    }
}

internal fun guaguaVoiceCode(name: String): String = PinyinHelper.toHanYuPinyinString(name.replace("MK", "_MK"),
    HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }, "", true)
