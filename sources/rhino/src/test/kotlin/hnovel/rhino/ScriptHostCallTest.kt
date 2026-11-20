package hnovel.rhino

import hnovel.rules.*
import org.junit.Assert.*
import org.junit.Test

class ScriptHostCallTest {
    private val frame = ScriptFrame("fixture", "legado")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("PRIVATE_HOST_MESSAGE") })
    private fun failure(script: String) = engine.evaluate(script, frame) as ScriptResult.Failure

    @Test fun registeredMethodsReportOnlyCountAndTopLevelTypes() {
        val failure = failure("host.call('cookie.getKey','PRIVATE_URL','PRIVATE_COOKIE')")
        assertEquals(FailureCode.BridgeDenied, failure.code)
        assertEquals(ScriptHostCall("cookie.getKey", 2, List(2) { ScriptArgumentType.String }), failure.hostCall)
        assertFalse(failure.toString().contains("PRIVATE"))
        val storage = failure("source.putLoginInfo({nested:{token:'PRIVATE_TOKEN'}})")
        assertEquals(ScriptHostCall("source.putLoginInfo", 1, listOf(ScriptArgumentType.Object)), storage.hostCall)
        assertFalse(storage.toString().contains("PRIVATE"))
    }

    @Test fun argumentSerializationFailureStillIdentifiesTheRegisteredCall() {
        val failure = failure("var data={}; data.self=data; source.putLoginInfo(data)")
        assertEquals(FailureCode.UnsupportedResult, failure.code)
        assertEquals(ScriptHostCall("source.putLoginInfo", 1, listOf(ScriptArgumentType.Object)), failure.hostCall)
    }

    @Test fun unknownCallsAndSourceErrorsNeverInheritOrGuessAHostMethod() {
        assertNull(failure("host.call('PRIVATE_UNKNOWN_METHOD','PRIVATE')").hostCall)
        val caught = failure("try { cookie.getKey('PRIVATE','PRIVATE') } catch(e) {} throw new Error('cookie.getKey PRIVATE')")
        assertEquals(FailureCode.Runtime, caught.code)
        assertNull(caught.hostCall)
        assertFalse(caught.toString().contains("PRIVATE"))
    }

    @Test fun onlyTheRealMissingGlobalSleepBindingIsClassified() {
        assertEquals(ScriptDependency.Sleep, failure("sleep(1)").dependency)
        assertNull(failure("throw new ReferenceError('sleep is not defined')").dependency)
        assertEquals(ScriptResult.Success("42"), engine.evaluate("Packages.java.lang.Thread.sleep(0);42", frame))
    }
}
