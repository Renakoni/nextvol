package hnovel.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MatchaPackageTest {
    @get:Rule val files = TemporaryFolder()

    private val chinese = listOf("model-steps-3.onnx", "hifigan_v2.onnx", "tokens.txt", "lexicon.txt",
        "dict/jieba.dict.utf8", "date.fst", "number.fst", "phone.fst")

    private fun preview(
        resources: List<String> = chinese,
        param: String = "null",
        extra: String = "",
        code: String = "voice",
        locale: String = "zh-CN",
        engineType: String? = "inner",
    ): SpeechPackagePreview {
        val yaml = files.newFile().apply { writeText(
            "sherpa:\n- code: $code\n  name: Voice\n  locale: $locale\n  param: $param\n" +
                extra.lineSequence().filter(String::isNotBlank).joinToString("\n") { "  $it" }
        ) }
        val voices = previewSpeechPackage(yaml, "config.yaml").engines.single().voices
        val engine = SpeechPackageEngine("sherpa", "Sherpa", engineType, null, voices, SpeechPackageSupport.Unsupported, emptyList(), emptyList())
        return SpeechPackagePreview("sample", engines = listOf(engine), resources = resources.map {
            SpeechPackageResource("sherpa/models/shared/$it", if (it.endsWith(".onnx")) 400_000_000L else 10L)
        })
    }

    private fun plan(value: SpeechPackagePreview) = previewMatchaVoice(value, value.engines.single(), value.engines.single().voices.single())

    @Test fun explicitDirectoryAndNullFallbackKeepLogicalVoiceIdentityWithoutPinningModelContents() {
        val mapped = plan(preview(param = """'{"modelDir":"models/shared","speakerId":3,"provider":"cpu"}'""")).plan!!
        assertEquals("sherpa/models/shared", mapped.directory)
        assertEquals("sherpa\u0000voice", mapped.id)
        assertEquals(3, mapped.configuration.speakerId)
        assertEquals(1f, mapped.configuration.noiseScale, 0f)
        assertEquals(chinese.size, mapped.resources.size)
        assertNotNull(plan(preview(code = "models/shared")).plan)
        assertNotNull(plan(preview(param = """'{"modelDir":null}'""", code = "models/shared")).plan)
        val renamed = preview(resources = chinese - "model-steps-3.onnx" + "another-acoustic.onnx",
            param = """'{"modelDir":"models/shared"}'""")
        assertEquals(mapped.id, plan(renamed).plan!!.id)
        assertEquals("another-acoustic.onnx", plan(renamed).plan!!.configuration.model)
    }

    @Test fun englishMatchaAndMultilingualKokoroDiscoverTheirDifferentFrontends() {
        val english = plan(preview(resources = listOf("acoustic.onnx", "hifigan_new.onnx", "tokens.txt", "espeak-ng-data/en_dict"),
            code = "models/shared", locale = "en-US")).plan!!.configuration
        assertEquals("auto", english.modelType)
        assertEquals("hifigan_new.onnx", english.vocoder)
        assertEquals("espeak-ng-data", english.dataDir)
        assertTrue(english.lexicons.isEmpty())
        val kokoro = plan(preview(resources = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/en_dict",
            "lexicon-zh.txt", "lexicon-us-en.txt", "dict/jieba.dict.utf8", "number-zh.fst", "date-zh.fst"),
            code = "models/shared", locale = "ja-JP")).plan!!.configuration
        assertEquals("voices.bin", kokoro.voices)
        assertEquals(listOf("lexicon-us-en.txt", "lexicon-zh.txt"), kokoro.lexicons)
        assertEquals(listOf("date-zh.fst", "number-zh.fst"), kokoro.ruleFsts)
        assertEquals("", kokoro.lang) // Locale must not override the model's own frontend.
    }

    @Test fun vitsUsesStaticControlDefaultsAndDeterministicFloatModelSelection() {
        val names = listOf("theresa.int8.onnx", "tokens.txt", "theresa.onnx", "lexicon.txt")
        fun configured(resources: List<String>) = plan(preview(resources = resources, engineType = null,
            param = """'{"modelDir":"models/shared","speakerId":"@sid","threadNumber":6,"noiseScale":0.5,"noiseScaleW":0.7,"lengthScale":1.2}'""",
            extra = """extendUI: '[{"code":"sid","value":"17","candidate":{"0":"First","17":"Selected"}}]'
                |speed: 0.8
                |pitch: 1.1
                |volume: 3.0
                |sampleRate: 44100
            """.trimMargin())).plan!!.configuration
        val config = configured(names)
        assertEquals(config, configured(names.reversed()))
        assertEquals("theresa.onnx", config.model)
        assertEquals(17, config.speakerId)
        assertEquals(6, config.numThreads)
        assertEquals(.5f, config.noiseScale, 0f)
        assertEquals(.7f, config.noiseScaleW, 0f)
        assertEquals(1.2f, config.lengthScale, 0f)
        assertEquals(.8f, config.speed, 0f)
        assertEquals(1.1f, config.pitch, 0f)
        assertEquals(3f, config.volume, 0f)
        val explicit = discoverSherpaVoiceConfig(names, Json.parseToJsonElement("""{"model":"theresa.int8.onnx"}""") as JsonObject)
        assertEquals("theresa.int8.onnx", explicit.model)
    }

    @Test fun invalidPathsMissingResourcesAndUnresolvedControlsRemainActionableFailures() {
        assertEquals(MatchaPackageError.UnsafePath,
            plan(preview(param = """'{"modelDir":"../outside"}'""")).error)
        assertEquals(MatchaPackageError.UnsafePath,
            plan(preview(resources = chinese + "../outside", code = "models/shared")).error)
        assertEquals(MatchaPackageError.Ambiguous,
            plan(preview(resources = chinese + "unrelated.onnx", code = "models/shared")).error)
        val missing = plan(preview(resources = chinese - "tokens.txt", code = "models/shared"))
        assertEquals(MatchaPackageError.MissingResource, missing.error)
        assertEquals("tokens", missing.detail)
        val unresolved = plan(preview(param = """'{"modelDir":"models/shared","threadNumber":"@missing"}'"""))
        assertEquals(MatchaPackageError.Parameters, unresolved.error)
        assertEquals("threadNumber", unresolved.detail)
        val duplicate = plan(preview(resources = chinese + "tokens.txt", code = "models/shared"))
        assertEquals(MatchaPackageError.Ambiguous, duplicate.error)
    }

    @Test fun missingSpeakerControlUsesDefaultSpeakerUntilTheModelIsLoaded() {
        val mapped = plan(preview(resources = listOf("voice.int8.onnx", "tokens.txt"),
            param = """'{"modelDir":"models/shared","speakerId":"@sid"}'""", extra = "extendUI: null"))
        assertEquals(0, mapped.plan!!.configuration.speakerId)
        assertEquals("voice.int8.onnx", mapped.plan!!.configuration.model)
    }
}
