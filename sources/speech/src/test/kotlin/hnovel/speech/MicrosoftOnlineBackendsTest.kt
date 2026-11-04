package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class MicrosoftOnlineBackendsTest {
    private fun metadata(code: String, parameters: String, voiceParameters: String) = buildJsonObject {
        putJsonObject("engine") { put("code", code); put("param", parameters) }
        putJsonObject("voice") {
            put("locale", "zh-CN"); put("param", voiceParameters)
            put("extendUI", "[{\"code\":\"style\",\"name\":\"Style\",\"value\":\"cheerful\"}]")
        }
    }

    private fun response(request: BrokerRequest, body: String, status: Int = 200) =
        BrokerResponse(status, request.url, emptyMap(), body.toByteArray(), "UTF-8", 0)

    @Test fun clipchampRefreshesTheSessionAfterAuthenticationFailureAndKeepsVoiceControls() = runBlocking {
        val requests = mutableListOf<BrokerRequest>()
        var authentication = 0
        val client = ClipchampSpeechClient(metadata("clipchamp", "{token:'login',audioFormat:'riff-24khz-16bit-mono-pcm'}",
            "zh-CN-XiaoxiaoNeural,Default,@style,1.4")) { request ->
            requests += request
            when {
                request.url == ClipchampSpeechClient.AUTH_URL -> {
                    authentication++
                    response(request, "{\"region\":\"eastus\",\"access_token\":\"session-$authentication\"}")
                }
                authentication == 1 -> response(request, "", 401)
                request.url.endsWith("voices/list") -> response(request,
                    """[{"ShortName":"zh-CN-XiaoxiaoNeural","LocalName":"Xiaoxiao","Locale":"zh-CN","Gender":"Female","Name":"Microsoft (zh-CN, XiaoxiaoNeural)","StyleList":["cheerful"],"RolePlayList":["Girl"],"SecondaryLocaleList":["en-US"]}]""")
                else -> response(request, "audio")
            }
        }
        try { client.synthesize("你好", 1f, 2f); fail("Expired Azure session must fail") }
        catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.Authentication, failure.error) }
        assertArrayEquals("audio".toByteArray(), client.synthesize("你好 & <世界>", 1f, 2f))
        assertEquals(2, authentication)
        assertEquals("Bearer login", requests.first().headers["Authorization"])
        val audio = requests.last()
        assertEquals("Bearer session-2", audio.headers["Authorization"])
        assertEquals("riff-24khz-16bit-mono-pcm", audio.headers["X-Microsoft-OutputFormat"])
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(audio.body!!.byteInputStream())
        assertEquals("你好 & <世界>", xml.documentElement.textContent)
        assertEquals("cheerful", xml.getElementsByTagName("mstts:express-as").item(0).attributes.getNamedItem("style").nodeValue)
        val voice = client.voices().single()
        assertEquals("zh-CN-XiaoxiaoNeural,@role,@style,@styleDegree,@langSkill", voice.string("param"))
        assertEquals("2.0", voice["volume"]!!.jsonPrimitive.content)
        assertEquals(setOf("role", "style", "styleDegree", "langSkill"),
            speechExtensionControls(voice.string("extendUI")).map { it.code }.toSet())
    }

    @Test fun speechStudioUsesItsDistinctVoiceIdAndCurrentPcmDefault() = runBlocking {
        var sent: BrokerRequest? = null
        val client = SpeechStudioSpeechClient(metadata("msspeech", "{}",
            "zh-CN-XiaoxiaoNeural,00000000-0000-0000-0000-000000000001,Girl,@style,1.6")) {
            sent = it; response(it, "pcm!")
        }
        val audio = client.synthesize("真实文本 & <标记>", 1f, 2f)
        assertEquals("RIFF", audio.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertArrayEquals("pcm!".toByteArray(), audio.copyOfRange(44, audio.size))
        val request = sent!!
        assertEquals(SpeechStudioSpeechClient.ENDPOINT, request.url)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("raw-24khz-16bit-mono-pcm", body.string("ttsAudioFormat"))
        val ssml = body.string("ssml")
        val mapping = Json.parseToJsonElement(ssml.substringAfter("Version=1|").substringBefore("-->"))
            .jsonObject.getValue("VoiceNameToIdMapItems").jsonArray.single().jsonObject
        assertEquals("00000000-0000-0000-0000-000000000001", mapping.string("Id"))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ssml.byteInputStream())
        val expression = xml.getElementsByTagName("mstts:express-as").item(0).attributes
        assertEquals("Girl", expression.getNamedItem("role").nodeValue)
        assertEquals("cheerful", expression.getNamedItem("style").nodeValue)
        assertEquals("1.6", expression.getNamedItem("styledegree").nodeValue)
        assertEquals("真实文本 & <标记>", xml.documentElement.textContent)
    }

    @Test fun azureListAndSynthesisShareSubscriptionAndVoiceParameters(): Unit = runBlocking {
        val configuration = AzureSpeechConfiguration(metadata("azure", "{region:'westus',apiKey:'test-key'}",
            "zh-CN-XiaoxiaoNeural,Default,@style,1.2"))
        assertEquals("westus", configuration.region)
        assertEquals("test-key", configuration.apiKey)
        assertTrue(configuration.ssml("正文", 1.5f, 2f).contains("rate=\"60%\""))
        configuration.voices { request ->
            assertEquals("https://westus.tts.speech.microsoft.com/cognitiveservices/voices/list", request.url)
            assertEquals(configuration.apiKey, request.headers["Ocp-Apim-Subscription-Key"])
            response(request, "[]")
        }
    }
}
