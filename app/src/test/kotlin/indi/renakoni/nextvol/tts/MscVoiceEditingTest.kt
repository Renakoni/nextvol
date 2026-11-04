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

    @Test fun mixedTypeEditsKeepTheOnlyModelAndAssignmentsAcrossRestartAndReleaseItAfterOnlineDeletion() = runBlocking {
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
        val archive = files.newFile("msc.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("config.yaml" to "msctts: [{code: voice, name: Original, type: 0, param: 'app,region,voice'}]",
                "msctts/app/common.jet" to "common", "msctts/app/voice.jet" to "voice").forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val preview = previewSpeechPackage(archive)
        val engine = preview.engines.single()
        val original = offline.install(archive, previewOfflineVoice(preview, engine, engine.voices.single()).plan!!)
        val sharedEngine = online.engines().single()
        assertEquals(engines.get("msctts"), sharedEngine)
        val updatedEngine = sharedEngine.copy(parameters = "{\"region\":\"bj\"}")
        online.saveEngine(updatedEngine)
        assertEquals(updatedEngine.parameters, offline.configuration(original).engineParameters)
        online.registerEngine(sharedEngine)
        assertEquals(updatedEngine, online.engines().single())
        val reference = SpeechVoiceRef(offlineVoice = original.reference)
        settings.update { it.copy(offlineVoice = original.reference, narrationVoices = listOf(reference)) }
        pairs.setManual(SpeechVoicePair("book", "person", "group", reference))
        MscVoiceEditing(online, offline, settings, pairs).toOnline(original.copy(name = "Edited",
            configuration = original.configuration!!.copy(type = 1, speed = 1.4f)), copy = false, creating = false)
        assertTrue(offline.voices().isEmpty())
        val model = File(offline.directory(original.resourceReference), "app/voice.jet")
        assertEquals("voice", model.readText())
        offline = OfflineSpeechRepository(root, engines)
        online = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline, engines)
        assertTrue(offline.voices().isEmpty())
        val source = online.sources().single { !it.isBuiltIn }
        assertEquals(updatedEngine.parameters, source.httpEngine()!!.parameters)
        val ticket = authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        online.saveVoice(editHttpVoice(source, buildJsonObject { put("type", 2); put("sampleRate", 24000) }, true, false))
        assertFalse(authority.accepts(ticket))
        assertEquals(source.definition.id, settings.get().narrationVoices.single().httpSource)
        assertEquals(source.definition.id, pairs.pairs().single().value.httpSource)
        val reimported = offline.install(archive, previewOfflineVoice(preview, engine, engine.voices.single()).plan!!)
        val references = speechPackageReferences(online.sources(), offline.voices())
        assertEquals(reimported.reference, references["msctts_voice"]!!.offlineVoice)
        assertEquals(source.definition.id, references["msctts_voice_2"]!!.httpSource)
        offline.delete(reimported.reference)
        val editor = MscVoiceEditing(online, offline, settings, pairs)
        editor.toOffline(source, original.copy(name = "Edited", configuration = original.configuration.copy(speed = 1.4f)), copy = false)
        val restored = OfflineSpeechRepository(root, engines).voices().single()
        assertEquals(original.resourceReference, restored.resourceReference)
        assertEquals("Edited", restored.name)
        assertEquals(1.4f, restored.configuration!!.speed)
        assertEquals(restored.reference, settings.get().offlineVoice)
        assertEquals(restored.reference, pairs.pairs().single().value.offlineVoice)
        assertTrue(online.sources().all { it.isBuiltIn })
        val onlineDefaults = restored.defaults!!.let { it.copy(configuration = it.configuration.copy(type = 2)) }
        val reversed = speechPackageReferences(emptyList(), listOf(restored.copy(defaults = onlineDefaults)))
        assertEquals(setOf("msctts_voice"), reversed.keys)
        assertEquals(restored.reference, reversed.getValue("msctts_voice").offlineVoice)
        val lease = offline.acquire(restored.reference)
        editor.toOnline(restored.copy(configuration = restored.configuration.copy(type = 1)), copy = false, creating = false)
        online.delete(online.sources().single { !it.isBuiltIn }.definition.id)
        assertTrue(model.exists())
        lease.close()
        assertFalse(model.exists())
    }
}
