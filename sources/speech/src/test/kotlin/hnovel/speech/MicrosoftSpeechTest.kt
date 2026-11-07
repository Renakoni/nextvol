package hnovel.speech

import hnovel.execution.ExecutionAuthority
import hnovel.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory

class MicrosoftSpeechTest {
    @get:Rule val directory = TemporaryFolder()
    private val audio = "RIFFxxxxWAVEpayload".toByteArray()

    private fun definition(custom: Boolean = false, header: String = "", transform: (String) -> String = { it }): HttpSpeechDefinition {
        val fixture = if (custom) "header-voice.js" else "fixed-voice.js"
        val script = javaClass.getResource("/microsoft/$fixture")!!.readText().replace("\r\n", "\n")
        return previewHttpSpeech(buildJsonObject {
            put("name", "Imported Microsoft voice"); put("url", transform(script)); put("header", header)
        }.toString()).sources.single()
    }

    private fun response(status: Int = 200, body: ByteArray = audio) = BrokerResponse(status, "https://example.org/",
        emptyMap(), body, "UTF-8", 0)
    private fun token(region: String = "eastus", authorization: String = "test-token") = response(body =
        buildJsonObject { put("r", region); put("t", authorization) }.toString().toByteArray())

    @Test fun onlyAuditedRecipesAreAdaptedAndParametersRemainData() {
        assertTrue(definition().usesMicrosoftTranslator)
        assertTrue(definition(transform = { it.replace("\n", "\r\n") }).usesMicrosoftTranslator)
        val selected = definition(true, """{"voice":"en-US-JennyNeural"}""").microsoft!!
        assertEquals("en-US-JennyNeural", selected.voice)
        assertEquals("en-US", selected.language)
        assertFalse(definition(transform = { "$it; java.ajax('https://extra.example/')" }).usesMicrosoftTranslator)
        assertFalse(definition(transform = { it.replace("dev.microsofttranslator.com", "other.example") }).usesMicrosoftTranslator)
        assertFalse(definition(transform = { it.replace("zh-CN-XiaoxiaoNeural", "zh-CN-X' + java.ajax('x') + '") }).usesMicrosoftTranslator)
        assertFalse(definition(true, """{"voice":"zh-CN-\"><break/>"}""").usesMicrosoftTranslator)
        assertFalse(definition(header = "@js:java.ajax('https://extra.example/')").usesMicrosoftTranslator)
        assertFalse(definition(true, """{"voice":"ru-RU-VoiceNeural"}""").usesMicrosoftTranslator)
    }

    @Test fun signatureMatchesIndependentHmacVectorIncludingTheEnvelope() {
        assertEquals("MSTranslatorAndroidApp::LneUEsUqh6P1ZoJ0Vg9LwPXSTrMeNCMTk82X4S3SF60=::" +
            "thu, 01 jan 1970 00:00:00GMT::0123456789abcdef0123456789abcdef",
            MicrosoftSpeechClient.signature("test-signing-key".toByteArray(), Date(0), "0123456789abcdef0123456789abcdef"))
    }

    @Test fun xmlTextAndSpeedMatchTheApplicationWithoutExecutingMarkup() {
        val text = "你好 & <voice> ' \" {{40+2}} @js:literal / \\ Hello"
        for ((rate, expected) in listOf(0f to "-100%", 1f to "0%", 1.5f to "50%", 2f to "100%")) {
            val xml = MicrosoftSpeechClient.ssml("zh-CN-XiaoxiaoNeural", "zh-CN", text, rate)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            assertEquals(text, document.documentElement.textContent)
            assertEquals(1, document.getElementsByTagName("voice").length)
            assertEquals(expected, document.getElementsByTagName("prosody").item(0).attributes.getNamedItem("rate").nodeValue)
        }
    }

    @Test fun tokenIsReusedExpiresAndRefreshesOnceOn401() = runBlocking {
        var now = 0L
        val sent = mutableListOf<BrokerRequest>()
        val responses = ArrayDeque(listOf(token(), response(), response(), token(authorization = "renewed"), response(),
            response(401), token(authorization = "refreshed"), response(401)))
        val client = MicrosoftSpeechClient(definition().microsoft!!, { sent += it; responses.removeFirst() }, { now })
        assertArrayEquals(audio, client.synthesize("first", 1f))
        assertArrayEquals(audio, client.synthesize("second", 1f))
        now = 480_000
        assertArrayEquals(audio, client.synthesize("expired", 1f))
        val failure = try { client.synthesize("rejected", 1f); error("Expected rejection") } catch (e: HttpSpeechException) { e }
        assertEquals(401, failure.statusCode)
        assertEquals(3, sent.count { it.url == MicrosoftSpeechClient.AUTH_URL })
        assertEquals("renewed", sent[4].headers["Authorization"])
        assertEquals("refreshed", sent.last().headers["Authorization"])
        assertTrue(sent.all { !it.followRedirects && it.retry == 0 })
        assertTrue(responses.isEmpty())
    }

    @Test fun malformedRegionAndRateLimitsNeverSendAnotherRequestOrExposeTokens(): Unit = runBlocking {
        for (region in listOf("evil.example/path", "eastus@evil.example", "../", "", "eastus\r\nHost:evil")) {
            var calls = 0
            val client = MicrosoftSpeechClient(definition().microsoft!!, { calls++; token(region, "private-token") })
            val failure = try { client.synthesize("text", 1f); error("Expected rejection") } catch (e: HttpSpeechException) { e }
            assertEquals(1, calls)
            assertFalse(failure.toString().contains("private-token"))
        }
        var calls = 0
        val client = MicrosoftSpeechClient(definition().microsoft!!, { if (++calls == 1) token() else response(429) })
        val failure = try { client.synthesize("text", 1f); error("Expected rejection") } catch (e: HttpSpeechException) { e }
        assertEquals(429, failure.statusCode)
        assertEquals(2, calls)
        val cancelled = MicrosoftSpeechClient(definition().microsoft!!, { throw CancellationException() })
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.synthesize("text", 1f) } }
    }

    @Test fun missingAuthGrantIsDeniedBeforeAnyConnectionOrScriptExecution(): Unit = runBlocking {
        SourceBroker(directory.root.toPath(), okhttp3.Dns { error("Must not resolve DNS") }).use { owner ->
            HttpSpeechClient(definition(), owner, emptyList(), ExecutionAuthority()) { _, _, _, _ -> error("Must not run JavaScript") }.use { client ->
                val failure = try { client.synthesize("text"); error("Expected rejection") } catch (e: HttpSpeechException) { e }
                assertEquals(HttpSpeechError.PermissionDenied, failure.error)
            }
        }
    }
}
