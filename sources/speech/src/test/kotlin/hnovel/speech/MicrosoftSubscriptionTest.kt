package hnovel.speech

import hnovel.execution.ExecutionAuthority
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class MicrosoftSubscriptionTest {
    @get:Rule val directory = TemporaryFolder()

    private fun mixedPackage(): SpeechPackageEngine {
        val voices = """
            microsoft:
              - {code: zh-CN-XiaoxiaoNeural, name: Offline, type: 0, param: 'Embedded SDK name'}
              - code: zh-CN-XiaoxiaoNeural
                name: Online
                group: microsoft
                type: 1
                locale: zh-CN
                param: "  Microsoft SDK voice  \n  Key:test-subscription  \n  westus  "
        """.trimIndent()
        val archive = directory.newFile("microsoft.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("engines.yaml" to "engines: [{code: microsoft, name: Microsoft, type: inner, param: unrelated-engine-data}]",
                "config.yaml" to voices).forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return previewSpeechPackage(archive).engines.single()
    }

    @Test fun mixedImportKeepsOriginalIdentityAndMapsSpeakerLinesToSubscription() {
        val engine = mixedPackage()
        assertFalse(engine.isOnlineVoice(engine.voices.first()))
        assertEquals(0, readOfflineVoiceDefinition(engine, engine.voices.first()).configuration.type)
        val resources = SpeechPackagePreview("mixed", resources = listOf(
            SpeechPackageResource("microsoft/zh-CN-XiaoxiaoNeural/model.dat", 1)))
        assertNotNull(previewOfflineVoice(resources, engine, engine.voices.first()).plan)
        val imported = previewHttpSpeechPackage(engine)
        assertNull(imported.error)
        val source = imported.sources.single()
        assertEquals("https://westus.tts.speech.microsoft.com/cognitiveservices/v1", source.url)
        val metadata = requireNotNull(source.multiTts)
        val voice = metadata.getValue("voice").jsonObject
        assertEquals("microsoft", voice.string("group"))
        assertEquals("zh-CN-XiaoxiaoNeural", voice.string("code"))
        assertEquals("1", voice.string("type"))
        assertTrue(voice.string("param").contains("Key:test-subscription"))
        val configuration = AzureSpeechConfiguration(metadata)
        assertEquals("Microsoft SDK voice", configuration.voiceName)
        assertEquals("test-subscription", configuration.apiKey)
        assertEquals("westus", configuration.region)
        assertTrue(microsoftSubscriptionPackageUrl("SDK name\nkey").startsWith("https://eastus."))
        val text = "他说：\"你好\" & <世界>\n第二行。"
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(configuration.ssml(text, 1.5f, 1f).byteInputStream())
        assertEquals(text, xml.documentElement.textContent)
        assertEquals("zh-CN-XiaoxiaoNeural", xml.getElementsByTagName("voice").item(0).attributes.getNamedItem("name").nodeValue)
        val prosody = xml.getElementsByTagName("prosody").item(0).attributes
        assertEquals("62%", prosody.getNamedItem("rate").nodeValue)
        assertEquals("25%", prosody.getNamedItem("volume").nodeValue)
        val http = SpeechPackageEngine(engine.code, engine.name, "http", SpeechPackageValue.Mapping(
            requireNotNull(engine.raw).values + mapOf(
                "type" to SpeechPackageValue.Scalar("http", null, false),
                "url" to SpeechPackageValue.Scalar("https://speech.example/tts", null, false)), null),
            engine.voices, engine.support, engine.notices, engine.requiredResources)
        val generic = previewHttpSpeechPackage(http)
        assertNull(generic.error)
        assertEquals(2, generic.sources.size)
        assertTrue(generic.sources.all { it.nativeEngine == null && it.url == "https://speech.example/tts" })
    }

    @Test fun sdkDispatchRequiresTheActualSpeakerRegionGrant(): Unit = runBlocking {
        val source = previewHttpSpeechPackage(mixedPackage()).sources.single()
        // A saved URL from before a region edit must not authorize the new SDK destination.
        val stale = HttpSpeechDefinition.parse(JsonObject(source.raw +
            ("url" to JsonPrimitive("https://eastus.tts.speech.microsoft.com/cognitiveservices/v1"))))
        var calls = 0
        SourceBroker(directory.newFolder("broker").toPath()).use { broker ->
            for (region in listOf("eastus", "westus")) {
                HttpSpeechClient(stale, broker, listOf(NetworkGrant("https://$region.tts.speech.microsoft.com")), ExecutionAuthority(),
                    createSdkBackend = { metadata, _, _ ->
                        assertEquals("microsoft", metadata.getValue("engine").jsonObject.string("code"))
                        object : OnlineSpeechBackend {
                            override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
                                calls++
                                return speechPcmWave(byteArrayOf(1, 2), 24000)
                            }
                        }
                    }, run = { _, _, _, _ -> error("Subscription voice must not execute a source script") }).use { client ->
                    if (region == "eastus") {
                        try { client.synthesize("正文"); fail("Wrong region was authorized") }
                        catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.PermissionDenied, failure.error) }
                        assertEquals(0, calls)
                    } else assertTrue(client.synthesize("正文").size > 44)
                }
            }
        }
        assertEquals(1, calls)
    }
}
