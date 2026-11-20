package indi.renakoni.nextvol.tts.sovits

import android.app.Application
import indi.renakoni.nextvol.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CheckpointImporterTest {
    @get:Rule val files = TemporaryFolder()
    private fun fixture(name: String) = File("src/test/resources/sovits/$name.ptfixture").inputStream()
    private fun reference() = CheckpointImporter.Reference(files.newFile("reference.wav").apply { writeText("audio fixture") }, "参考原文", "zh")
    private val graph = "graph fixture".toByteArray()
    private val manifest get() = """{
        "groups":[
          {"file":"gpt.bin","precision":"fp16","tensors":[{"checkpoint":"gpt","key":"test","shape":[2],"elements":2}]},
          {"file":"sovits.bin","precision":"fp32","tensors":[{"checkpoint":"sovits","key":"test","shape":[2],"elements":2}]}
        ],
        "files":{"graph.onnx":"${CheckpointImporter.hex(MessageDigest.getInstance("SHA-256").digest(graph))}"}
    }"""

    @Test fun packagedTemplateBytesMatchTheConversionManifests() {
        val assets = RuntimeEnvironment.getApplication().assets
        for (variant in listOf("v2pro", "v2proplus")) {
            val folder = "gpt-sovits/checkpoint-$variant"
            val files = assets.open("$folder/manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("files") }
            for (name in files.keys()) {
                val hash = assets.open("$folder/$name").use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
                assertEquals("$folder/$name", files.getString(name), CheckpointImporter.hex(hash))
            }
        }
    }

    @Test fun convertsBothV2VariantsAndSavesReferenceBeforePublishing() {
        val reference = reference()
        for ((modelFixture, version) in listOf("v2pro" to "v2Pro", "v2proplus" to "v2ProPlus")) {
            val root = files.newFolder()
            val stages = mutableListOf<Int>()
            val importer = CheckpointImporter(root) { path ->
                assertTrue(path.startsWith("gpt-sovits/checkpoint-${version.lowercase()}/"))
                if (path.endsWith("manifest.json")) manifest.byteInputStream() else graph.inputStream()
            }
            val result = importer.import("Voice", { fixture("gpt") }, { fixture(modelFixture) },
                progress = { stages += it.resource }, reference = reference)
            assertEquals(version, result.architecture)
            val character = File(root, "CharacterModels/$version/${result.characterId}")
            assertArrayEquals(byteArrayOf(0, 0x3c, 0, 0xc0.toByte()), File(character, "tts_models/gpt.bin").readBytes())
            assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0xc0.toByte()), File(character, "tts_models/sovits.bin").readBytes())
            assertArrayEquals(graph, File(character, "tts_models/graph.onnx").readBytes())
            assertEquals("参考原文", JSONObject(File(character, "prompt_wav.json").readText()).getJSONObject("Normal").getString("text"))
            assertEquals("audio fixture", File(character, "prompt_wav/reference.wav").readText())
            assertTrue(R.string.sovits_converting_weights in stages)
            assertEquals(listOf("CharacterModels"), root.list()!!.toList())
        }
    }

    @Test fun refusesV5BeforeOpeningAnyTemplate() {
        val root = files.newFolder()
        val importer = CheckpointImporter(root) { error("Unsupported weights must not reach templates") }
        val reference = reference()
        val failure = assertThrows(SovitsImportException::class.java) {
            importer.import("V5", { fixture("gpt") }, { fixture("v5") }, reference = reference)
        }
        assertEquals(R.string.sovits_v2_variant_unsupported, failure.text.resource)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun cancellationDuringConversionAndCorruptTemplateLeaveNoPartialVoice() {
        val root = files.newFolder()
        val reference = reference()
        val existing = File(root, "CharacterModels/kept").apply { parentFile!!.mkdirs(); writeText("keep") }
        var cancel = false
        val importer = CheckpointImporter(root) { path ->
            if (path.endsWith("manifest.json")) manifest.byteInputStream() else "corrupt".byteInputStream()
        }
        assertThrows(CancellationException::class.java) {
            importer.import("Cancelled", { fixture("gpt") }, { fixture("v2pro") },
                progress = { if (it.resource == R.string.sovits_converting_weights) cancel = true },
                checkCancelled = { if (cancel) throw CancellationException() }, reference = reference)
        }
        val failure = assertThrows(SovitsImportException::class.java) {
            importer.import("Corrupt", { fixture("gpt") }, { fixture("v2pro") }, reference = reference)
        }
        assertEquals(R.string.sovits_model_template_corrupt, failure.text.resource)
        assertEquals("keep", existing.readText())
        assertEquals(listOf("CharacterModels"), root.list()!!.toList())
        assertEquals(listOf("kept"), existing.parentFile!!.list()!!.toList())
    }
}
