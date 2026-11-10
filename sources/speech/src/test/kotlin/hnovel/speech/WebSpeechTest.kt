package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class WebSpeechTest {
    @Test fun googleWebRpcsKeepTheirDistinctArgumentsAndExtractOnlyAudio() = runBlocking {
        val text = "你好 \"reader\"\n次の文。"
        val audio = "ID3reference-audio!".toByteArray()
        for ((engine, rpcId) in listOf("ggtrans" to "jQ1olc", "gemini" to "XqA3Ic")) {
            var request: BrokerRequest? = null
            var body = ")]}'\n\n123\n" + buildJsonArray { add(buildJsonArray {
                val payload = buildJsonArray { add(Base64.getEncoder().encodeToString(audio)) }.toString()
                add("wrb.fr"); add(rpcId); add(if (engine == "gemini") payload.replace("=", "\\u003d") else payload)
                add(JsonNull)
            }) }.toString()
            val execute: suspend (BrokerRequest) -> BrokerResponse = { request = it; response(body) }
            val metadata = metadata(engine, "ja", engineParameters = "{\"Host\":\"speech.example\"}")
            val client = if (engine == "ggtrans") GoogleTranslateSpeechClient(metadata, execute)
                else GeminiSpeechClient(metadata, execute)
            assertArrayEquals(audio, client.synthesize(text, 1.7f, 1.4f))
            assertTrue(request!!.url.startsWith("https://speech.example/_/"))
            val envelope = Json.parseToJsonElement(URLDecoder.decode(request.body!!.removePrefix("f.req="), "UTF-8"))
                .jsonArray[0].jsonArray[0].jsonArray
            assertEquals(rpcId, envelope[0].jsonPrimitive.content)
            val arguments = Json.parseToJsonElement(envelope[1].jsonPrimitive.content).jsonArray
            assertEquals(if (engine == "ggtrans") buildJsonArray { add(text); add("ja"); add(JsonNull); add(JsonNull) }
                else buildJsonArray { add(JsonNull); add(text); add("ja"); add(JsonNull); add(2) }, arguments)
            body = "{\"error\":\"private service message\"}"
            try { client.synthesize(text, 1f, 1f); fail("Missing RPC audio must fail") }
            catch (error: HttpSpeechException) { assertEquals(HttpSpeechError.InvalidAudio, error.error) }
        }
    }

    @Test fun huoshanKeepsCrxAndWebBranchesAndDoesNotReturnJsonAsAudio() = runBlocking {
        val requests = mutableListOf<BrokerRequest>()
        val audio = "ID3reference-audio".toByteArray()
        var body = "{\"audio\":{\"data\":\"${Base64.getEncoder().encodeToString(audio)}\"}}"
        val execute: suspend (BrokerRequest) -> BrokerResponse = { requests += it; response(body) }
        for (parameter in listOf("", "web")) {
            assertArrayEquals(audio, OnlineSpeechClient(metadata("huoshan", "tts.other.BV050_streaming", parameter), execute)
                .synthesize("你好 & 世界", 1.5f, 2f, null))
        }
        assertEquals(listOf(HuoshanSpeechClient.CRX_URL, HuoshanSpeechClient.WEB_URL), requests.map { it.url })
        assertEquals(Json.parseToJsonElement("{\"speaker\":\"tts.other.BV050_streaming\",\"language\":\"zh\",\"text\":\"你好 & 世界\"}"),
            Json.parseToJsonElement(requests.first().body!!))
        body = "[]"
        try {
            OnlineSpeechClient(metadata("huoshan", "voice"), execute)
                .synthesize("正文", 1f, 1f, null)
            fail("Malformed service response")
        }
        catch (error: HttpSpeechException) { assertEquals(HttpSpeechError.InvalidAudio, error.error) }
    }

    private fun metadata(engine: String, code: String, parameter: String = "", engineParameters: String = "") = buildJsonObject {
        put("engine", buildJsonObject { put("code", engine); put("param", engineParameters) })
        put("voice", buildJsonObject { put("code", code); put("param", parameter); put("locale", "zh-CN"); put("sampleRate", 48000) })
    }

    private fun response(text: String) = BrokerResponse(200, "https://speech.example", emptyMap(), text.toByteArray(), "UTF-8", 0)
}
