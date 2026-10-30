package hnovel.speech

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MatchaPackageTest {
    @get:Rule val files = TemporaryFolder()

    private fun preview(param: String = "null", extra: String = "", code: String = "baker"): SpeechPackagePreview {
        val yaml = files.newFile().apply { writeText("""
            sherpa:
            - code: $code
              name: Baker
              locale: zh-CN
              param: $param
              $extra
        """.trimIndent()) }
        val voices = previewSpeechPackage(yaml, "config.yaml").engines.single().voices
        val engine = SpeechPackageEngine("sherpa", "Sherpa", "inner", null, voices, SpeechPackageSupport.Unsupported, emptyList(), emptyList())
        return SpeechPackagePreview("sample", engines = listOf(engine), resources = ChineseMatchaProfile.files.keys.map {
            SpeechPackageResource("sherpa/models/chinese/$it", 10, 123)
        })
    }

    private fun plan(value: SpeechPackagePreview) = previewMatchaVoice(value, value.engines.single(), value.engines.single().voices.single())

    @Test fun explicitDirectoryAndCodeFallbackResolveTheSameResourcesWithoutChangingVoiceIdentity() {
        val mapped = plan(preview("'{\"modelDir\":\"models/chinese\",\"speakerId\":0,\"provider\":\"cpu\"}'"))
        assertNotNull(mapped.plan)
        assertEquals("sherpa/models/chinese", mapped.plan!!.directory)
        assertEquals(ChineseMatchaProfile.files.size, mapped.plan.resources.size)
        assertNotNull(plan(preview(code = "models/chinese")).plan)
        assertNotNull(plan(preview("'{\"modelDir\":null}'", code = "models/chinese")).plan)
        val renamed = preview("'{\"modelDir\":\"models/chinese\"}'")
        assertEquals(mapped.plan.id, plan(renamed).plan!!.id)
    }

    @Test fun unsupportedParametersUnsafePathsAndAmbiguousModelsStayUninstallable() {
        assertEquals(MatchaPackageError.Parameters, plan(preview("'{\"modelDir\":\"models/chinese\",\"speakerId\":1}'")).error)
        assertEquals(MatchaPackageError.Parameters, plan(preview("'{\"provider\":\"cuda\"}'")).error)
        assertEquals(MatchaPackageError.UnsafePath, plan(preview("'{\"modelDir\":\"../outside\"}'")).error)
        val value = preview(code = "models/chinese")
        val other = SpeechPackagePreview("sample", engines = value.engines,
            resources = value.resources + SpeechPackageResource("sherpa/models/chinese/other.onnx", 10))
        assertEquals(MatchaPackageError.UnsupportedModel, plan(other).error)
        val missing = SpeechPackagePreview("sample", engines = value.engines, resources = value.resources.drop(1))
        assertEquals("model-steps-3.onnx", plan(missing).detail)
    }
}
