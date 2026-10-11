package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.*
import hnovel.imports.LEGADO_PROFILE
import hnovel.rules.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptFailureInstrumentedTest {
    @Test fun binderRetainsCallShapeWithoutPrivateArgumentsAndRecoversForTheNextTask() = runBlocking {
        val authority = ExecutionAuthority()
        val identity = authority.issue("fixture", LEGADO_PROFILE, "1")
        val executor = AndroidIsolatedExecutor(InstrumentationRegistry.getInstrumentation().targetContext, authority)
        try {
            val failure = executor.execute(identity, ExecutionTask.Rule(
                "@js:host.call('cookie.getKey','PRIVATE_SITE','PRIVATE_TOKEN')", RuleValue.Empty,
                location = RuleLocation("exploreUrl"))) as ExecutionResult.Failure
            assertEquals(FailureCode.BridgeDenied, failure.code)
            assertEquals("exploreUrl", failure.ruleError!!.location.field)
            assertEquals(ScriptHostCall("cookie.getKey", 2, List(2) { ScriptArgumentType.String }), failure.hostCall)
            assertFalse(Json.encodeToString(ExecutionResult.serializer(), failure).contains("PRIVATE"))
            val unknown = executor.execute(identity, ExecutionTask.Script("throw new Error('cookie.getKey PRIVATE_TOKEN')")) as ExecutionResult.Failure
            assertNull(unknown.hostCall)
            assertEquals(ExecutionResult.Success("42"), executor.execute(identity, ExecutionTask.Script("42")))
        } finally { executor.close() }
    }
}
