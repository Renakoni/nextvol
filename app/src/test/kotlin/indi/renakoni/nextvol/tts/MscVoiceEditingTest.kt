package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.ContextWrapper
import hnovel.execution.ExecutionAuthority
import hnovel.network.StorageCipher
import hnovel.speech.*
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.tts.matcha.*
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import indi.renakoni.nextvol.ui.tts.OfflineVoiceDraft
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class MscVoiceEditingTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun mscTypeEditsRetainResourcesAndAssignments() = verifyTypeEdits("msctts", "app,region,voice",
        mapOf("app/common.jet" to "common", "app/voice.jet" to "voice"), "app/voice.jet")

    @Test fun microsoftTypeEditsRetainResourcesAndAssignments() = verifyTypeEdits("microsoft", "SDK Voice\\nsubscription\\nwestus",
        mapOf("voice/model.dat" to "voice"), "voice/model.dat")

    @Test fun newMicrosoftVoiceCanBeCopiedToOfflineWithoutRetiringTheOnlineSource() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = files.root }
        val engines = OfflineEngineRepository(context, StorageCipher.Plain)
        val offline = OfflineSpeechRepository(files.newFolder("offline"), engines)
        val authority = ExecutionAuthority()
        val online = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline, engines)
        val source = editHttpVoice(newHttpVoice(SpeechEngineConfig("microsoft", "Microsoft")), buildJsonObject {
            put("name", "Xiaoxiao"); put("code", "zh-CN-XiaoxiaoNeural"); put("param", "SDK Voice\nsubscription\nwestus")
        }, enabled = true, copy = false)
        online.saveVoice(source)
        val ticket = authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        val draft = InstalledOfflineVoice(OfflineVoiceRef("draft", "draft"), source.definition.name, emptyMap(),
            configuration = source.speakerData().offlineConfiguration("microsoft").copy(type = 0))
        MscVoiceEditing(online, offline, mockk(), mockk()).toOffline(source, draft, copy = true)
        val copied = offline.voices().single()
        assertTrue(copied.sizes.isEmpty())
        assertEquals("microsoft_zh-CN-XiaoxiaoNeural", copied.packageVoiceId())
        assertEquals("", copied.sourceKey)
        assertEquals(source.definition.id, online.sources().single { !it.isBuiltIn }.definition.id)
        assertTrue(authority.accepts(ticket))
        assertFalse(offline.directory(copied.resourceReference).exists())
    }

    private fun verifyTypeEdits(code: String, parameters: String, resources: Map<String, String>, modelPath: String) = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = files.root }
        val engines = OfflineEngineRepository(context, StorageCipher.Plain)
        val root = files.newFolder("offline")
        val authority = ExecutionAuthority()
        var offline = OfflineSpeechRepository(root, engines)
        var online = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline, engines)
        val stored = mutableMapOf<String, String?>()
        val dao = mockk<UserDataDao>()
        coEvery { dao.get(any()) } answers { stored[firstArg()] }
        every { dao.getFlow(any()) } returns MutableStateFlow<String?>(null)
        coEvery { dao.insert(any(), any(), "String", any()) } answers { stored[firstArg()] = arg(3) }
        val data = UserDataRepository(dao)
        val settings = SpeechSettingsRepository(data)
        val pairs = SpeechVoicePoolRepository(data)
        val archive = files.newFile("$code.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            (mapOf("config.yaml" to "$code: [{code: voice, name: Original, locale: zh-CN, type: 0, param: \"$parameters\"}]") +
                resources.mapKeys { "$code/${it.key}" }).forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val preview = previewSpeechPackage(archive)
        val engine = preview.engines.single()
        val original = offline.install(archive, previewOfflineVoice(preview, engine, engine.voices.single()).plan!!)
        val sharedEngine = online.engines().single()
        assertEquals(engines.get(code), sharedEngine)
        val updatedEngine = sharedEngine.copy(parameters = "{\"region\":\"bj\"}")
        online.saveEngine(updatedEngine)
        assertEquals(updatedEngine.parameters, offline.configuration(original).engineParameters)
        online.registerEngine(sharedEngine)
        assertEquals(updatedEngine, online.engines().single())
        val reference = SpeechVoiceRef(offlineVoice = original.reference, externalId = "${code}_voice")
        settings.update { it.copy(offlineVoice = original.reference, narrationVoices = listOf(reference),
            dialogueVoices = listOf(SpeechVoiceRef(externalId = "${code}_voice")),
            roleRules = listOf(SpeechRoleRule(name = "role", speaker = reference)),
            voiceItems = listOf(SpeechVoiceItem("pool", reference))) }
        pairs.setManual(SpeechVoicePair("book", "person", "group", reference))
        val draft = OfflineVoiceDraft.from(original).copy(name = "Edited", type = "1", speed = "1.4")
        MscVoiceEditing(online, offline, settings, pairs).toOnline(original, draft.applyTo(original), copy = false, creating = false)
        assertTrue(offline.voices().isEmpty())
        val model = File(offline.directory(original.resourceReference), modelPath)
        assertEquals("voice", model.readText())
        offline = OfflineSpeechRepository(root, engines)
        online = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline, engines)
        assertTrue(offline.voices().isEmpty())
        val source = online.sources().single { !it.isBuiltIn }
        assertEquals(updatedEngine.parameters, source.httpEngine()!!.parameters)
        if (code == "microsoft") {
            assertEquals("https://westus.tts.speech.microsoft.com/cognitiveservices/v1", source.definition.url)
            assertTrue(source.origins.isEmpty())
        }
        assertEquals("${code}_voice_1", settings.get().narrationVoices.single().externalId)
        assertEquals(settings.get().narrationVoices, settings.get().dialogueVoices)
        assertEquals("${code}_voice", pairs.pairs().single().value.externalId)
        assertEquals("${code}_voice", settings.get().roleRules.single().speaker!!.externalId)
        assertEquals(source.definition.id, settings.get().voiceItems.single().value.httpSource)
        assertNull(pairs.pairs().single().value.resolvePackageReference(speechPackageReferences(online.sources(), emptyList())).httpSource)
        val ticket = authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        MscVoiceEditing(online, offline, settings, pairs).toOffline(source, original, copy = true)
        val copied = offline.voices().single()
        assertEquals(original.resourceReference, copied.resourceReference)
        assertTrue(authority.accepts(ticket))
        offline.delete(copied.reference)
        assertTrue(model.exists())
        online.saveVoice(editHttpVoice(source, buildJsonObject { put("type", 2); put("sampleRate", 24000) }, true, false))
        assertFalse(authority.accepts(ticket))
        assertEquals(source.definition.id, settings.get().narrationVoices.single().httpSource)
        assertEquals(source.definition.id, pairs.pairs().single().value.httpSource)
        val reimported = offline.install(archive, previewOfflineVoice(preview, engine, engine.voices.single()).plan!!)
        val references = speechPackageReferences(online.sources(), offline.voices())
        assertEquals(reimported.reference, references["${code}_voice"]!!.offlineVoice)
        assertEquals(source.definition.id, references["${code}_voice_2"]!!.httpSource)
        assertEquals(reimported.reference, pairs.pairs().single().value.resolvePackageReference(references).offlineVoice)
        offline.delete(reimported.reference)
        val editor = MscVoiceEditing(online, offline, settings, pairs)
        editor.toOffline(source, original.copy(name = "Edited", configuration = original.configuration!!.copy(speed = 1.4f)), copy = false)
        val restored = OfflineSpeechRepository(root, engines).voices().single()
        assertEquals(original.resourceReference, restored.resourceReference)
        assertEquals("Edited", restored.name)
        assertEquals(1.4f, restored.configuration!!.speed)
        assertEquals(restored.reference, settings.get().offlineVoice)
        assertEquals(restored.reference, pairs.pairs().single().value.offlineVoice)
        assertEquals(restored.reference, settings.get().roleRules.single().speaker!!.offlineVoice)
        assertEquals(restored.reference, settings.get().voiceItems.single().value.offlineVoice)
        assertTrue(online.sources().all { it.isBuiltIn })
        val onlineDefaults = restored.defaults!!.let { it.copy(configuration = it.configuration.copy(type = 2)) }
        val reversed = speechPackageReferences(emptyList(), listOf(restored.copy(defaults = onlineDefaults)))
        assertEquals(setOf("${code}_voice"), reversed.keys)
        assertEquals(restored.reference, reversed.getValue("${code}_voice").offlineVoice)
        val lease = offline.acquire(restored.reference)
        editor.toOnline(restored, restored.copy(configuration = restored.configuration.copy(type = 1)), copy = false, creating = false)
        online.delete(online.sources().single { !it.isBuiltIn }.definition.id)
        assertTrue(model.exists())
        lease.close()
        assertFalse(model.exists())
    }
}
