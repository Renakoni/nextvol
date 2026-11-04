package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.BrokerWebSocketMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedWebSpeechTest {
    @Test fun guaguaUsesAccountLoginAndVoiceParameterThenRenewsAfterFailure() = runBlocking {
        val requests = mutableListOf<BrokerRequest>()
        var failAudio = true
        val audio = "ID3audio".toByteArray()
        val client = GuaguaSpeechClient(buildJsonObject {
            putJsonObject("engine") { put("param", "{\"wxToken\":\"test-session\",\"extra\":\"keep\"}") }
            putJsonObject("voice") { put("code", "display-code"); put("param", "robot-id") }
        }) { request ->
            requests += request
            val body = when {
                request.url.startsWith(GuaguaSpeechClient.PORTAL_URL) -> "{\"data\":{\"list\":[{\"id\":7}]}}".toByteArray()
                request.url == GuaguaSpeechClient.LOGIN_URL -> "{\"data\":{\"access_token\":\"access\"}}".toByteArray()
                else -> audio
            }
            val status = if (request.url == GuaguaSpeechClient.ENDPOINT && failAudio) 401 else 200
            BrokerResponse(status, request.url, emptyMap(), body, "UTF-8", 0)
        }
        try { client.synthesize("你好", 1f, 2f); fail("Expired access token") }
        catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.Authentication, failure.error) }
        failAudio = false
        assertArrayEquals(audio, client.synthesize("下一句", 1f, 2f))
        assertEquals(1, requests.count { it.url.startsWith(GuaguaSpeechClient.PORTAL_URL) })
        assertEquals(2, requests.count { it.url == GuaguaSpeechClient.LOGIN_URL })
        val request = requests.last()
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("robot-id", body.string("cvRobotId"))
        assertEquals(125, body.getValue("speedFactor").jsonPrimitive.int)
        assertEquals("下一句", body.string("ssml"))
        assertEquals("Bearer access", request.headers["Authorization"])
        assertEquals("wxToken=test-session; access_token=access", request.headers["Cookie"])
        assertEquals(Json.parseToJsonElement("{\"wxToken\":\"test-session\",\"extra\":\"keep\",\"userId\":\"7\"}"),
            Json.parseToJsonElement(requireNotNull(client.updatedEngineParameters)))
        assertEquals("agua_MK-III", guaguaVoiceCode("阿瓜MK-III"))
    }

    @Test fun doubaoKeepsBinaryAudioUntilFinishAndDoesNotDeliverBlockedResponseAsSuccess() {
        val audio = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 1, 2)
        val collector = DoubaoSpeechAudio()
        assertFalse(collector.receive(BrokerWebSocketMessage.Text("{\"event\":\"start\"}")))
        assertFalse(collector.receive(BrokerWebSocketMessage.Binary(audio)))
        assertTrue(collector.receive(BrokerWebSocketMessage.Text("{\"event\":\"finish\"}")))
        assertArrayEquals(audio, collector.bytes())
        try {
            DoubaoSpeechAudio().receive(BrokerWebSocketMessage.Text("{\"message\":\"block\"}"))
            fail("Blocked response")
        } catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.Authentication, failure.error) }
    }
}
