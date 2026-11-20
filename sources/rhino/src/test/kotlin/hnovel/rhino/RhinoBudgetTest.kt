package hnovel.rhino

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

class RhinoBudgetTest {
    private val bridge = HostBridge { _, _ -> error("unexpected bridge") }
    private val frame = ScriptFrame("fixture", "legado")

    @Test(timeout = 5000) fun deadlinesStopLoopsGettersAndRegexBacktrackingWithoutATotalInstructionCap() {
        val engine = RhinoScriptEngine(bridge, ScriptLimits(timeoutMillis = 50))
        for (script in listOf("try { while(true) {} } catch(e) { 'caught'; }",
            "({get value() {while(true) {}}})", "/(a+)+$/.test('a'.repeat(1000)+'!')")) {
            assertEquals(FailureCode.Timeout, (engine.evaluate(script, frame) as ScriptResult.Failure).code)
            assertNull(Context.getCurrentContext())
        }
        ScriptLibrary(frame.sourceId, frame.profile, "while(true){}").use { library ->
            assertEquals(FailureCode.Timeout, (engine.evaluate("1", frame, library) as ScriptResult.Failure).code)
            assertNull(library.scope)
        }
    }

    @Test(timeout = 5000) fun infiniteLoopConsumesBudgetEvenIfScriptAttemptsToCatchIt() {
        val engine = RhinoScriptEngine(bridge, ScriptLimits(instructionLimit = 3000))
        for (script in listOf("while(true) {}", "try { while(true) {} } catch(e) { 'caught'; }")) {
            assertEquals(FailureCode.Timeout, (engine.evaluate(script, frame) as ScriptResult.Failure).code)
            assertNull(Context.getCurrentContext())
            assertEquals("42", (engine.evaluate("21*2", frame) as ScriptResult.Success).json)
        }
    }

    @Test fun cancellationAndForeignContextCannotBypassOwnedObserver() {
        val engine = RhinoScriptEngine(bridge)
        Thread.currentThread().interrupt()
        try { assertEquals(FailureCode.Cancelled, (engine.evaluate("1", frame) as ScriptResult.Failure).code) }
        finally { Thread.interrupted() }
        Context.enter()
        try { assertEquals(FailureCode.Runtime, (engine.evaluate("1", frame) as ScriptResult.Failure).code) }
        finally { Context.exit() }
        assertEquals("1", (engine.evaluate("1", frame) as ScriptResult.Success).json)
    }

    @Test fun deeplyContainedLargeValueCannotHideBehindObjectDisplayString() {
        val engine = RhinoScriptEngine(bridge, ScriptLimits(maxResultChars = 128))
        for (script in listOf("({nested: {value: 'x'.repeat(1000)}})", "[1, {x:'a'.repeat(1000)}]", "'a'.repeat(1000)")) {
            assertEquals(FailureCode.ResultTooLarge, (engine.evaluate(script, frame) as ScriptResult.Failure).code)
        }
        assertEquals("{\"ok\":1}", (engine.evaluate("({ok:1})", frame) as ScriptResult.Success).json)
    }

    @Test fun interruptionAfterEvaluationIsCancelledBeforeSerialization() {
        val engine = RhinoScriptEngine(object : HostBridge {
            override fun call(name: String, args: List<JsonElement>): JsonElement {
                Thread.currentThread().interrupt()
                return JsonPrimitive("ok")
            }
        })
        try { assertEquals(FailureCode.Cancelled, (engine.evaluate("host.call('interrupt')", frame) as ScriptResult.Failure).code) }
        finally { Thread.interrupted() }
    }

    @Test fun limitCountsEscapesKeysAndPunctuationAndReturnsOnlySerializedData() {
        val script = "({key:'\\n\\\\\\\"', list:[1,true,null]})"
        val roomy = RhinoScriptEngine(bridge).evaluate(script, frame) as ScriptResult.Success
        Json.parseToJsonElement(roomy.json)
        val exact = RhinoScriptEngine(bridge, ScriptLimits(maxResultChars = roomy.json.length)).evaluate(script, frame)
        assertEquals(roomy, exact)
        val small = RhinoScriptEngine(bridge, ScriptLimits(maxResultChars = roomy.json.length - 1)).evaluate(script, frame)
        assertEquals(FailureCode.ResultTooLarge, (small as ScriptResult.Failure).code)
        assertFalse(roomy.toString().contains("list"))
    }

    @Test fun cyclesAndHostObjectsAreRejectedWithoutInvokingToString() {
        val engine = RhinoScriptEngine(bridge)
        assertEquals(FailureCode.UnsupportedResult, (engine.evaluate("var x={}; x.self=x; x", frame) as ScriptResult.Failure).code)
        assertEquals("{\"ok\":1}", (engine.evaluate("({ok:1,toString:function(){throw 'not a serializer';}})", frame) as ScriptResult.Success).json)
    }

    @Test fun productionEngineVersionAndJsonDataMatchThePinnedCompatibilityEngine() {
        val versionContext = Context.enter()
        try { assertTrue(versionContext.implementationVersion.contains("1.8.1")) }
        finally { Context.exit() }
        val scripts = listOf("({answer:21*2,list:[1,true,null,'text']})", "(() => 'ab'.repeat(3))()", "[1,,undefined,NaN]")
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
            }
        }
        for (script in scripts) {
            val expected = factory.call { cx -> Context.toString(cx.evaluateString(cx.initSafeStandardObjects(), "JSON.stringify($script)", "oracle", 1, null)) }
            val actual = (RhinoScriptEngine(bridge).evaluate(script, frame) as ScriptResult.Success).json
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
        }
    }
}
