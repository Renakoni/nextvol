package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FilmoraSpeechTest {
    private val metadata = buildJsonObject { putJsonObject("voice") { put("code", "z2iy9RdwUddxTyIYePq7") } }
    private val audio = "ID3audio".toByteArray()

    private fun response(body: String) = BrokerResponse(200, FilmoraSpeechClient.ENDPOINT,
        emptyMap(), body.toByteArray(), "UTF-8", 0)

    @Test fun failedTaskRotatesTokenThenSubmitsPollsAndDownloadsUsingOriginalProtocol() = runBlocking {
        val requests = mutableListOf<BrokerRequest>()
        val responses = ArrayDeque(listOf(response("""{"data":{"task_id":"failed"}}"""),
            response("""{"data":{"status":4}}"""), response("""{"data":{"task_id":"task-1"}}"""),
            response("""{"data":{"status":2}}"""),
            response("""{"data":{"status":3,"list":[{"download_url":"https://audio.example/result.mp3"}]}}"""),
            BrokerResponse(200, "https://audio.example/result.mp3", emptyMap(), audio, "UTF-8", 0)))
        val client = FilmoraSpeechClient(metadata) { requests += it; responses.removeFirst() }
        try { client.synthesize("first", 1f, 1f); fail("Failed task must not retry") }
        catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.InvalidAudio, failure.error) }
        assertEquals(2, requests.size)
        val failedAuthorization = requests.first().headers["authorization"]
        requests.clear()
        val text = "你好，\"Filmora\"。\nA real sentence."
        assertArrayEquals(audio, client.synthesize(text, 1.5f, 0.8f))
        val body = Json.parseToJsonElement(requests.first().body!!).jsonObject
        assertEquals(text, body.getValue("task_contents").jsonArray.single().jsonObject.getValue("sentence").jsonPrimitive.content)
        assertEquals(3, body.getValue("speech_rate").jsonPrimitive.int)
        assertEquals(-1, body.getValue("volume").jsonPrimitive.int)
        assertEquals("z2iy9RdwUddxTyIYePq7", body.getValue("voice_code").jsonPrimitive.content)
        assertEquals("eleven_multilingual_v2", body.getValue("model_id").jsonPrimitive.content)
        val authorization = requests.first().headers.getValue("authorization")
        assertNotEquals(failedAuthorization, authorization)
        assertTrue(authorization.matches(Regex("Bearer [0-9a-zA-Z]{23}")))
        assertTrue(requests.all { it.headers["authorization"] == authorization })
        assertEquals("1734030144540", requests[0].headers["x-timestamp"])
        assertEquals("1734030147020", requests[1].headers["x-timestamp"])
        assertEquals(requests[1].body, requests[2].body)
        assertEquals("GET", requests.last().method)
        assertNull(requests.last().headers["x-signature"])
        assertTrue(responses.isEmpty())
    }
}
