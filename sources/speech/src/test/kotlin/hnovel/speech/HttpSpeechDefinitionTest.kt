package hnovel.speech

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HttpSpeechDefinitionTest {
    @Test fun preservesOriginalFieldsAndUnknownDependenciesWithoutEvaluatingThem() {
        val raw = """{"id":9007199254740993,"name":"朗读 Voice","url":"@js:throw new Error('must not run')",
            "header":{"Authorization":"private"},"loginUi":[{"name":"Voice"}],"speed":5,
            "vendorExtension":{"nested":[1,true,"中文"]}}"""
        val preview = previewHttpSpeech(raw)
        assertTrue(preview.issues.isEmpty())
        val source = preview.sources.single()
        assertEquals("9007199254740993", source.id)
        assertEquals(Json.parseToJsonElement(raw), source.raw)
        assertEquals(setOf("vendorExtension"), source.unknownFields)
        assertTrue(source.hasScripts)
        assertTrue(source.hasConfiguration)
        assertFalse(source.toString().contains("private"))
    }

    @Test fun independentInvalidEntriesAndDuplicatesCannotReplaceTheFirstSource() {
        val preview = previewHttpSpeech("""[
            {"id":1,"name":"First","url":"https://example.com"},
            {"id":1,"name":"Replacement","url":"https://other.example"},
            {"id":2,"name":"Bad speed","url":"https://example.com","speed":81},
            false,
            {"id":3,"name":"Last","url":"https://example.com","speed":null}
        ]""")
        assertEquals(listOf("First", "Last"), preview.sources.map { it.name })
        assertEquals(listOf(1, 2, 3), preview.issues.map { it.index })
        assertEquals(listOf(SpeechImportError.DuplicateId, SpeechImportError.InvalidEntry, SpeechImportError.InvalidEntry), preview.issues.map { it.error })
        assertEquals("speed", preview.issues[1].field)
    }

    @Test fun generatedIdentityIsStableAcrossKeyOrderingAndRepeatedImport() {
        val first = previewHttpSpeech("""{"name":"Voice","url":"https://example.com","extra":{"b":2,"a":1}}""").sources.single()
        val second = previewHttpSpeech("""{"extra":{"a":1,"b":2},"url":"https://example.com","name":"Voice"}""").sources.single()
        assertEquals(first.id, second.id)
        assertEquals(first.revision, second.revision)
    }

    @Test fun sourceSpeedAndFloatRateMapToTheReferenceScriptBinding() {
        fun source(speed: String = "null") = previewHttpSpeech("""{"name":"Voice","url":"https://example.com","speed":$speed}""").sources.single()
        assertEquals(10, source().scriptSpeed())
        assertEquals(10, source("5").scriptSpeed())
        assertEquals(5, source("0").scriptSpeed())
        assertEquals(85, source("80").scriptSpeed())
        assertEquals(10, source("80").scriptSpeed(1f))
        assertEquals(0, source().scriptSpeed(0f))
        assertEquals(20, source().scriptSpeed(2f))
        assertThrows(IllegalArgumentException::class.java) { source().scriptSpeed(Float.NaN) }
    }

    @Test fun malformedAndOversizedInputsHaveBoundedNonSensitiveErrors() {
        assertEquals(SpeechImportError.InvalidJson, previewHttpSpeech("[".repeat(20000) + "0" + "]".repeat(20000)).issues.single().error)
        val quotedBraces = """{"name":"Voice","url":"https://example.com","extra":"[[[{{{\\\""}"""
        assertTrue(previewHttpSpeech(quotedBraces).issues.isEmpty())
        assertEquals(SpeechImportError.InvalidJson, previewHttpSpeech("{secret").issues.single().error)
        assertEquals(SpeechImportError.TooLarge, previewHttpSpeech("x".repeat(4 * 1024 * 1024 + 1)).issues.single().error)
        val catalog = List(1024) { """{"id":$it,"name":"Voice","url":"https://example.com"}""" }.joinToString(",", "[", "]")
        assertEquals(1024, previewHttpSpeech(catalog).sources.size)
        assertEquals(SpeechImportError.TooLarge, previewHttpSpeech(List(1025) { "{}" }.joinToString(",", "[", "]")).issues.single().error)
        val preview = previewHttpSpeech("""{"name":"Voice","url":42,"header":"private"}""")
        assertEquals("url", preview.issues.single().field)
        assertFalse(preview.issues.toString().contains("private"))
    }
}
