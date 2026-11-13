package hnovel.imports

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceDefinitionEditsTest {
    private val raw = buildJsonObject {
        put("bookSourceUrl", "https://old.example/")
        put("bookSourceName", "Original")
        put("searchUrl", "https://old.example/search?q={{key}}")
        put("header", "{\"Referer\":\"https://www.old.example/\"}")
        put("ruleToc", buildJsonObject { put("chapterList", "li"); put("chapterUrl", "@js:'https://old.example/'+result") })
        put("ruleContent", buildJsonObject { put("content", "article"); put("nextContentUrl", "https://old.example/content") })
    }
    private val definition = SourceDefinition("a".repeat(64), "legado-json", LEGADO_PROFILE, "https://old.example/",
        "Original", true, true, ImportOrigin(ImportOrigin.Kind.Url, "https://updates.example/source.json"),
        digest(canonical(raw).toString()), 4, canonical(raw).toString())

    @Test fun preparationKeepsImportIdentityAndLeavesRevisionAssignmentToCommit() {
        val edited = SourceDefinitionEdits.prepare(definition, raw.toString().replace("old.example", "new.example"))
        assertEquals(definition.sourceId, edited.sourceId)
        assertEquals(definition.importKey, edited.importKey)
        assertEquals(definition.origin, edited.origin)
        assertEquals(definition.profile, edited.profile)
        assertEquals(definition.revision, edited.revision)
        assertNotEquals(definition.contentDigest, edited.contentDigest)
        assertEquals(digest(edited.rawJson), edited.contentDigest)
        assertTrue(edited.rawJson.contains("new.example"))
        assertFalse(edited.rawJson.contains("old.example"))
        assertSame(definition, SourceDefinitionEdits.prepare(definition, SourceDefinitionEdits.format(definition.rawJson)))
    }

    @Test fun malformedRuleSectionsCannotBecomeRevisions() {
        val text = JsonObject(raw + ("ruleContent" to JsonPrimitive(false))).toString()
        val failure = runCatching { SourceDefinitionEdits.prepare(definition, text) }.exceptionOrNull() as SourceEditException
        assertEquals(ImportCode.InvalidField, failure.code)
        assertEquals("ruleContent", failure.field)
    }

    @Test fun syntaxValidationAcceptsObjectsWithoutExecutingOrRequiringSourceRules() {
        SourceDefinitionEdits.validateJson("{\"text\":\"中文\",\"items\":[true,null,42]}")
        val invalid = runCatching { SourceDefinitionEdits.validateJson("{\n  \"name\":\n}") }.exceptionOrNull() as SourceEditException
        assertEquals(ImportCode.InvalidJson, invalid.code)
        assertEquals(3, invalid.line)
        assertNotNull(invalid.column)
        val duplicate = runCatching { SourceDefinitionEdits.validateJson("{\n\"name\":1,\n\"name\":2\n}") }.exceptionOrNull() as SourceEditException
        assertEquals(ImportCode.DuplicateField, duplicate.code)
        assertEquals(3, duplicate.line)
        assertEquals(ImportCode.InvalidShape,
            (runCatching { SourceDefinitionEdits.validateJson("[]") }.exceptionOrNull() as SourceEditException).code)
        for (text in listOf("{\"x\":1,}", "{\"x\":NaN}", "{unquoted:1}", "{} {}"))
            assertTrue(runCatching { SourceDefinitionEdits.validateJson(text) }.exceptionOrNull() is SourceEditException)
    }

}
