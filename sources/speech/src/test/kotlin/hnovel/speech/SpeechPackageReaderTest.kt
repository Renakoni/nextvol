package hnovel.speech

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpeechPackageReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun yaml(text: String) = temporary.newFile().also { it.writeText(text) }

    @Test fun taggedHttpPackageRetainsSourceDataAndOnlyReportsAdapterFoundation() {
        constructions = 0
        val file = archive(
            "engines.yaml" to """!!org.nobody.multitts.tts.engine.EngineConfig
                engines:
                - code: http_test
                  name: "null"
                  extra: !!hnovel.speech.SpeechPackageReaderTest${'$'}ExecutableTag {command: ignored}
                  type: http
                  url: "@js:throw new Error('must not execute')"
                  header: "private-header"
            """.trimIndent(),
            "config.yaml" to """http_test:
                - !!org.nobody.multitts.tts.speaker.Speaker
                  code: voice
                  name: Voice
                  param: null
                  speed: 1.3
                  volume: 2.0
                  pitch: 1.0
                  sampleRate: 16000
            """.trimIndent(),
        )
        val preview = previewSpeechPackage(file)
        assertNull(preview.error)
        val engine = preview.engines.single()
        assertEquals(0, constructions)
        assertEquals("null", engine.name)
        assertEquals(SpeechPackageSupport.HttpAdapter, engine.support)
        assertEquals(listOf(SpeechPackageNotice.ImportPending), engine.notices)
        assertTrue(engine.raw!!.values["url"].text()!!.contains("must not execute"))
        assertTrue(preview.documents.first().value.tag!!.endsWith("EngineConfig"))
        assertEquals("1.3", engine.voices.single().raw.values["speed"].text())
        assertEquals("16000", engine.voices.single().raw.values["sampleRate"].text())
        assertFalse(preview.toString().contains("private-header"))
        assertFalse(engine.raw.toString().contains("private-header"))
    }

    @Test fun sherpaUsesDeclaredModelDirectoryAndLeavesNestedConfigurationAsAResource() {
        val file = archive(
            "engines.yaml" to "engines: [{code: sherpa, name: Sherpa, type: inner}]",
            "config.yaml" to """sherpa:
                - code: speaker-code
                  name: Model voice
                  param: '{"modelDir":"actual-model","speakerId":0}'
                  type: 1
            """.trimIndent(),
            "sherpa/actual-model/model.onnx" to "model bytes are not loaded",
            "sherpa/actual-model/config.yaml" to "not: [valid YAML",
            "sherpa/actual-model/dictionary.so" to "resource, not an ELF library",
        )
        val preview = previewSpeechPackage(file, "online-model.zip")
        assertNull(preview.error)
        val engine = preview.engines.single()
        assertEquals(SpeechPackageSupport.Unsupported, engine.support)
        assertEquals(SpeechPackageResourceReference("sherpa/actual-model", true), engine.requiredResources.single())
        assertEquals(3, preview.resources.size)
        assertEquals(2, preview.documents.size)
        assertEquals("1", engine.voices.single().raw.values["type"].text())
    }

    @Test fun nullModelDirectoryFallsBackToCodeAndVoicesAreNotDeduplicatedByModelCode() {
        val preview = previewSpeechPackage(archive(
            "engines.yaml" to "engines: [{code: sherpa, type: inner}]",
            "config.yaml" to """sherpa:
                - {code: shared, name: First, param: '{"modelDir":null,"speakerId":0}'}
                - {code: shared, name: Second, param: '{"speakerId":1}'}
            """.trimIndent(),
            "sherpa/shared" to "a file cannot satisfy a model directory",
        ))
        assertNull(preview.error)
        assertEquals(2, preview.engines.single().voices.size)
        assertEquals(SpeechPackageResourceReference("sherpa/shared", false), preview.engines.single().requiredResources.single())
        assertFalse(SpeechPackageNotice.ConfigurationRequired in preview.engines.single().notices)
    }

    @Test fun rolesOnlyPackageIsRecognizedWithoutInventingVoices() {
        val preview = previewSpeechPackage(archive("roles.yaml" to """!!org.nobody.multitts.tts.role.RoleConfig
            roles:
            - {name: Narrator, voice: narrator}
        """.trimIndent()))
        assertNull(preview.error)
        assertEquals(setOf(SpeechPackageKind.Roles), preview.kinds)
        assertTrue(preview.engines.isEmpty())
    }

    @Test fun missingDefinitionAndNullVoiceListsAreConfigurationGapsRatherThanBrokenYaml() {
        val missingEngine = previewSpeechPackage(yaml("unknown: [{code: voice, name: Voice}]"), "config.yaml")
        assertNull(missingEngine.error)
        assertEquals(SpeechPackageSupport.NeedsConfiguration, missingEngine.engines.single().support)
        assertTrue(SpeechPackageNotice.MissingEngine in missingEngine.engines.single().notices)
        assertNull(missingEngine.engines.single().type)
        val empty = previewSpeechPackage(archive(
            "engines.yaml" to "engines: [{code: http_test, type: http, url: null}]",
            "config.yaml" to "http_test: null",
        ))
        assertNull(empty.error)
        assertEquals(setOf(SpeechPackageNotice.MissingVoices, SpeechPackageNotice.MissingRequest, SpeechPackageNotice.ImportPending), empty.engines.single().notices.toSet())
    }

    @Test fun deeplyNestedDataDoesNotExhaustTheJvmStack() {
        val nested = "[".repeat(2048) + "null" + "]".repeat(2048)
        val preview = previewSpeechPackage(yaml("engines: [{code: nested, extra: $nested}]"), "engines.yaml")
        assertNull(preview.error)
        assertTrue(preview.engines.single().raw!!.values["extra"] is SpeechPackageValue.Sequence)
    }

    @Test fun ambiguousYamlAndAliasesReturnNonSensitiveParseResults() {
        assertEquals(SpeechPackageError.DuplicateKey,
            previewSpeechPackage(yaml("http_test: []\nhttp_test: []"), "config.yaml").error)
        assertEquals(SpeechPackageError.YamlAlias,
            previewSpeechPackage(yaml("http_test: &voices []\nother: *voices"), "config.yaml").error)
        val result = previewSpeechPackage(yaml("private-value: [broken"), "config.yaml")
        assertEquals(SpeechPackageError.InvalidYaml, result.error)
        assertFalse(result.toString().contains("private-value"))
        assertEquals(SpeechPackageError.InvalidYaml,
            previewSpeechPackage(yaml("private\u0000value"), "config.yaml").error)
    }

    @Test fun cancellationClosesTheArchiveAndDoesNotPublishAPartialPreview() {
        val file = archive("config.yaml" to "http_test: [{code: voice}]")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            previewSpeechPackage(file) { if (++checks == 3) throw CancellationException() }
        }
        assertTrue(file.delete())
    }

    class ExecutableTag {
        init { constructions++ }
    }
    companion object { private var constructions = 0 }
}
