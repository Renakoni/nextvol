package indi.renakoni.nextvol.tts.sovits

import android.app.Application
import indi.renakoni.nextvol.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CheckpointImporterTest {
    @get:Rule val files = TemporaryFolder()
    private fun fixture(name: String) = File("src/test/resources/sovits/$name.ptfixture").inputStream()
    private val graph = "graph fixture".toByteArray()
    private val manifest get() = """{
        "groups":[
          {"file":"gpt_fp16.bin","precision":"fp16","tensors":[{"checkpoint":"gpt","key":"test","shape":[2],"elements":2}]},
          {"file":"sovits_fp32.bin","precision":"fp32","tensors":[{"checkpoint":"sovits","key":"test","shape":[2],"elements":2}]}
        ],
        "files":{"graph.onnx":"fixture"}
    }"""

    @Test fun convertsBothV2VariantsToTheRuntimeLayout() {
        val expected = byteArrayOf(0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0xc0.toByte())
        for ((modelFixture, version) in listOf("v2pro" to "v2Pro", "v2proplus" to "v2ProPlus")) {
            val root = files.newFolder()
            val importer = CheckpointImporter(root) { path ->
                when (path) {
                    "gpt-sovits/checkpoint-${version.lowercase()}/manifest.json" -> manifest.byteInputStream()
                    "gpt-sovits/graphs/graph.onnx" -> graph.inputStream()
                    else -> error("Unexpected asset $path")
                }
            }
            val result = importer.convert({ fixture("gpt") }, { fixture(modelFixture) }, {}, {})
            assertEquals(version, result.architecture)
            val models = File(result.character, "tts_models")
            assertArrayEquals(expected, File(models, "gpt_fp32.bin").readBytes())
            assertArrayEquals(expected, File(models, "sovits_fp32.bin").readBytes())
            assertEquals(setOf("graph.onnx", "gpt_fp32.bin", "sovits_fp32.bin"), models.list()!!.toSet())
            assertEquals(listOf("CharacterModels"), root.list()!!.toList())
        }
    }

    @Test fun refusesV5BeforeOpeningAnyTemplate() {
        val importer = CheckpointImporter(files.root) { error("Unexpected template access") }
        val failure = assertThrows(SovitsImportException::class.java) {
            importer.convert({ fixture("gpt") }, { fixture("v5") }, {}, {})
        }
        assertEquals(R.string.sovits_v2_variant_unsupported, failure.text.resource)
    }
}
