package hnovel.speech

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class MicrosoftSpeechPackageTest {
    private fun definition() = previewHttpSpeech(buildJsonObject {
        put("name", "Japanese voice"); put("url", MicrosoftSpeechClient.AUTH_URL)
        put("multiTts", buildJsonObject {
            put("engine", buildJsonObject { put("code", "mstrans"); put("type", "inner")
                put("param", "{\"authHost\":\"auth.example.org\",\"audioFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}") })
            put("voice", buildJsonObject { put("code", "local-copy"); put("locale", "ja-JP")
                put("param", "ja-JP-NanamiNeural,Default,@style,1.5,@language")
                put("extendUI", "[{'code':'style','name':'Style','value':'cheerful','candidate':{'cheerful':'Cheerful'}}," +
                    "{'code':'language','name':'Language','value':'de-DE','candidate':{'de-DE':'German'}}]") })
        })
    }.toString()).sources.single()

    @Test fun dedicatedPackageUsesOriginalParametersAndAllLanguagesWithoutScriptRecognition() = runBlocking {
        val source = definition()
        assertTrue(source.usesMicrosoftTranslator)
        assertFalse(source.hasScripts)
        val configuration = source.microsoft!!
        assertEquals("ja-JP-NanamiNeural", configuration.voice)
        val sent = mutableListOf<BrokerRequest>()
        val audio = "RIFFxxxxWAVEpayload".toByteArray()
        val client = MicrosoftSpeechClient(configuration, { request ->
            sent += request
            BrokerResponse(200, request.url, emptyMap(), if (sent.size == 1)
                "{\"r\":\"eastus\",\"t\":\"test-token\"}".toByteArray() else audio, "UTF-8", 0)
        })
        val text = "日本語 & <voice>\"' {{literal}}"
        assertArrayEquals(audio, client.synthesize(text, 1f, 1f))
        assertEquals("https://auth.example.org/apps/endpoint?api-version=1.0", sent.first().url)
        assertEquals("audio-24khz-48kbitrate-mono-mp3", sent.last().headers["X-Microsoft-OutputFormat"])
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(sent.last().body!!.byteInputStream())
        assertEquals(text, xml.documentElement.textContent)
        assertEquals("ja-JP", xml.documentElement.getAttribute("xml:lang"))
        val voice = xml.getElementsByTagName("voice").item(0)
        assertEquals(configuration.voice, voice.attributes.getNamedItem("name").nodeValue)
        val prosody = xml.getElementsByTagName("prosody").item(0).attributes
        assertEquals("40%", prosody.getNamedItem("rate").nodeValue)
        assertEquals("50", prosody.getNamedItem("volume").nodeValue)
        assertEquals("0%", prosody.getNamedItem("pitch").nodeValue)
        assertEquals("cheerful", xml.getElementsByTagName("mstts:express-as").item(0).attributes.getNamedItem("style").nodeValue)
        assertEquals("de-DE", xml.getElementsByTagName("lang").item(0).attributes.getNamedItem("xml:lang").nodeValue)
        val extensions = source.multiTts!!.getValue("voice").jsonObject.string("extendUI")
        assertEquals("cheerful", speechExtensionControls(extensions).first().value.content)
        assertEquals("de-DE", speechExtensionControls(updateSpeechExtension(extensions, "language", "de-DE")).last().value.content)
    }
}
