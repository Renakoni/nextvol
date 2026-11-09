package hnovel.rhino

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ImageBytesTest {
    @Test fun imageArraysPreserveSignedInputUnsignedReturnsAndTheExistingOutputBoundary() {
        val frame = ScriptFrame("image", "legado", imageInput = byteArrayOf(-1, 0, 1))
        val engine = RhinoScriptEngine(HostBridge.None)
        val result = engine.evaluate("result[0] === -1 ? [255, result[1], result[2]] : []", frame) as ScriptResult.Success
        assertArrayEquals(byteArrayOf(-1, 0, 1), java.util.Base64.getDecoder().decode(Json.parseToJsonElement(result.json).jsonPrimitive.content))
        assertEquals(FailureCode.UnsupportedResult, (engine.evaluate("[256]", frame) as ScriptResult.Failure).code)
        val small = RhinoScriptEngine(HostBridge.None, ScriptLimits(maxResultChars = 4))
        assertEquals(FailureCode.ResultTooLarge, (small.evaluate("[0,1,2,3]", frame) as ScriptResult.Failure).code)
    }
}
