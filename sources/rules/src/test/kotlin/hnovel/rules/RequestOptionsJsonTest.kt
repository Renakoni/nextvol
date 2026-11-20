package hnovel.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RequestOptionsJsonTest {
    @Test fun legadoLenientRequestDataDoesNotRequireJavaScriptEvaluation() {
        val value = RequestOptionsJson.parse("{method:'POST';header:{os:'pc'},body:'first\nsecond',value:unquoted}").jsonObject
        assertEquals(JsonPrimitive("POST"), value["method"])
        assertEquals(JsonPrimitive("pc"), value.getValue("header").jsonObject["os"])
        assertEquals(JsonPrimitive("first\nsecond"), value["body"])
        assertEquals(JsonPrimitive("unquoted"), value["value"])
    }
    @Test fun quotedBracketsAndEscapedQuotesAreStringData() {
        val text = "'" + "[{".repeat(100) + "\\\\\\'\\\"\\n\\u4e2d'"
        assertEquals(JsonPrimitive("[{".repeat(100) + "\\'\"\n中"), RequestOptionsJson.parse(text))
        assertEquals(JsonPrimitive("a'b"), RequestOptionsJson.parse("\"a'b\""))
    }

    @Test fun bothQuoteStylesPreserveTheDepthAndCharacterBounds() {
        for (quote in listOf("'", "\"")) {
            val nested = "[".repeat(63) + "{$quote" + "key$quote:0}" + "]".repeat(63)
            assertTrue(RequestOptionsJson.parse(nested) is JsonArray)
            assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.parse("[$nested]") }
        }
        assertEquals(JsonPrimitive("x".repeat(65534)), RequestOptionsJson.parse("'" + "x".repeat(65534) + "'"))
        assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.parse("'" + "x".repeat(65535) + "'") }
        assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.parse("'" + "\"".repeat(32768) + "'") }
    }

    @Test fun nestedHeaderAndBodyStringsCannotBypassTheDepthLimit() {
        val nested = "[".repeat(2000) + "0" + "]".repeat(2000)
        for (field in listOf("body", "header", "headers")) {
            val options = buildJsonObject { put(field, nested) }.toString()
            assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.options(options) }
        }
    }

    @Test fun malformedDataHasOnlyARedactedDiagnostic() {
        for (text in listOf("{'secret':'unterminated}", "{'secret':'\\x41'}", "{'secret':1,}", "{'secret':function(){}}",
            "{'secret':1}\u0001", "{'secret':1} trailing")) {
            val error = assertThrows(text, RequestOptionsException::class.java) { RequestOptionsJson.parse(text) }
            assertEquals("Invalid request options", error.message)
            assertNull(error.cause)
        }
    }
}
