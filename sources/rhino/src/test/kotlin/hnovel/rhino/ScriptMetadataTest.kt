package hnovel.rhino

import hnovel.rules.RuleContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptMetadataTest {
    @Test fun metadataMethodsPreserveTypesAndSeparateVariableScopes() {
        val context = RuleContext("a", chapterVariables = mapOf("token" to "old"))
        val frame = ScriptFrame("a", "legado", bookId = "book-a", ruleContext = context,
            book = buildJsonObject { put("kind", "novel,fiction"); put("wordCount", "10k") },
            chapter = buildJsonObject { put("url", "one,{\"method\":\"POST\"}"); put("baseUrl", "https://fixture.invalid/toc/") })
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        for ((code, expected) in listOf(
            "book.putVariable('token','book')" to "true",
            "chapter.putVariable('token',null)" to "true",
            "book.getKindList().join('|')" to "\"10k|novel|fiction\"",
            "chapter.getAbsoluteURL()" to "\"https://fixture.invalid/toc/one,{\\\"method\\\":\\\"POST\\\"}\"",
            "java.get('token')" to "\"book\""
        )) {
            val result = engine.evaluate("try{$code}catch(e){e.message}", frame)
            assertTrue(code + result, result is ScriptResult.Success)
            assertEquals(code, expected, (result as ScriptResult.Success).json)
        }
    }
}
