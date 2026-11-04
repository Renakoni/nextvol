package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class XfPeiyinSpeechTest {
    @Test fun textSignatureAndExtendedVoiceDriveTheAudioRequest() = runBlocking {
        val metadata = buildJsonObject { putJsonObject("voice") {
            put("code", "xinya"); put("param", "@style")
            put("extendUI", """[{"name":"风格","code":"style","value":"533347185"}]""")
        } }
        val requests = mutableListOf<BrokerRequest>()
        val audio = "ID3fixture".toByteArray()
        val client = XfPeiyinSpeechClient(metadata) { request ->
            requests += request
            val body = if (request.url == XfPeiyinSpeechClient.SIGN_URL)
                """{"body":"bfk6kmJGhLzP+0pyysjYd0wR3ixqLwm3ZscQ2EOCE3NEfd65tdKCAWGgcobeySPDUoMFnHeLZmfdppj0FrxsYw=="}""".toByteArray()
            else audio
            BrokerResponse(200, request.url, emptyMap(), body, "UTF-8", 0)
        }
        val text = "你好，\"配音\"。\nA real sentence."
        assertArrayEquals(audio, client.synthesize(text, 1.5f, 0.8f))
        assertEquals(2, requests.size)
        assertEquals("POST", requests[0].method)
        // Independent AES fixture covers the exact UTF-8 MD5 and JSON signing envelope.
        assertEquals("D25Icx8hx6vd7W+f/qwl61va5kLfvSJVXE6algqb8uRyIHg+QTl+tC34EV5bJFrsPrK1vK3k3KXoSOXCwInjRQ==",
            Json.parseToJsonElement(requests[0].body!!).jsonObject.getValue("req").jsonPrimitive.content)
        val query = URI(requests[1].url).rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(mapOf("ts" to "1700000000", "sign" to "fixture-sign", "sid" to "", "vid" to "533347185",
            "volume" to "-4", "speed" to "100", "content" to text, "listen" to "0"), query)
        assertEquals(requests[0].headers["X-Forwarded-For"], requests[1].headers["X-Forwarded-For"])
    }
}
