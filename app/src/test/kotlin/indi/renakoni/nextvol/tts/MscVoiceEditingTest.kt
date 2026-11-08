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
import indi.renakoni.nextvol.ui.tts.VoiceDraft
import indi.renakoni.nextvol.ui.tts.applyTo
import indi.renakoni.nextvol.ui.tts.from
import indi.renakoni.nextvol.ui.tts.speakerFields
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
        val draft = VoiceDraft.from(original).copy(name = "Edited", type = "1", speed = "1.4")
        MscVoiceEditing(online, offline, settings, pairs).toOnline(original, draft.fields().applyTo(original), copy = false, creating = false)
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
        val offlineFields = VoiceDraft.from(source, source.definition.id).copy(type = "0").fields()
        MscVoiceEditing(online, offline, settings, pairs).toOffline(source, offlineFields.speakerFields(), offlineFields.enabled, copy = true)
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
        val renamed = offline.save(reimported.copy(configuration = reimported.configuration!!.copy(code = "renamed")))
        settings.update { it.copy(dialogueVoices = listOf(SpeechVoiceRef(offlineVoice = renamed.reference, externalId = renamed.packageVoiceId()))) }
        val editor = MscVoiceEditing(online, offline, settings, pairs)
        editor.toOffline(online.sources().single { !it.isBuiltIn },
            offlineFields.speakerFields(), offlineFields.enabled, copy = false)
        val restored = OfflineSpeechRepository(root, engines).voices().single()
        assertEquals(reimported.reference.id, restored.reference.id)
        assertNotEquals(reimported.reference.revision, restored.reference.revision)
        assertEquals(reimported.resourceReference, restored.resourceReference)
        assertEquals(reimported.sourceKey, restored.sourceKey)
        assertEquals(reimported.defaults, restored.defaults)
        assertEquals("Edited", restored.name)
        assertEquals(1.4f, restored.configuration!!.speed)
        assertEquals(restored.reference, settings.get().offlineVoice)
        assertEquals(restored.reference, pairs.pairs().single().value.offlineVoice)
        assertEquals(restored.reference, settings.get().roleRules.single().speaker!!.offlineVoice)
        assertEquals(restored.reference, settings.get().voiceItems.single().value.offlineVoice)
        assertEquals(settings.get().narrationVoices, settings.get().dialogueVoices)
        assertTrue(online.sources().all { it.isBuiltIn })
        assertEquals(restored.reference, speechPackageReferences(online.sources(), offline.voices()).getValue("${code}_voice").offlineVoice)
        assertFalse(model.exists())
        val restoredModel = File(offline.directory(restored.resourceReference), modelPath)
        assertEquals("voice", restoredModel.readText())
        val target = editHttpVoice(source, buildJsonObject { put("code", "renamed") }, true, false)
            .copy(origins = listOf("https://speech.example:443"),
                offlineResources = OfflineSpeechResources(original.resourceReference, mapOf("unrelated.dat" to 1L)))
        online.saveVoice(target)
        settings.update { it.copy(dialogueVoices = listOf(SpeechVoiceRef(httpSource = target.definition.id, externalId = target.packageVoiceId()))) }
        val lease = offline.acquire(restored.reference)
        editor.toOnline(restored, restored.copy(configuration = restored.configuration.copy(type = 1)), copy = false, creating = false)
        val overwritten = online.sources().single { !it.isBuiltIn }
        assertEquals(target.definition.id, overwritten.definition.id)
        assertEquals(target.origins, overwritten.origins)
        assertEquals(target.speakerDefaults, overwritten.speakerDefaults)
        assertEquals(restored.resourceReference, overwritten.offlineResources!!.reference)
        assertTrue(offline.voices().isEmpty())
        assertEquals(target.definition.id, settings.get().httpSource)
        assertEquals(settings.get().narrationVoices, settings.get().dialogueVoices)
        online.delete(overwritten.definition.id)
        assertTrue(restoredModel.exists())
        lease.close()
        assertFalse(restoredModel.exists())
    }
}
