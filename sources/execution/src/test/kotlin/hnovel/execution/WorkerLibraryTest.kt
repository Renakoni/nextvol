package hnovel.execution

import org.junit.Assert.*
import org.junit.Test

class WorkerLibraryTest {
    private val authority = ExecutionAuthority()
    private val library = "var state={n:0};function next(){return ++state.n;}"
    private fun run(runtime: WorkerRuntime, identity: ExecutionIdentity, script: String = "next()", code: String = library): ExecutionResult {
        val wire = ExecutionWire.encode(identity, ExecutionTask.Script(script, libraryCode = code), ExecutionLimits())
        return ExecutionWire.decodeResult(runtime.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
    }

    @Test fun librariesFollowSourceProfileRevisionAndAccountRatherThanBookOrTicket() {
        val a = authority.issue("a", "legado", "1", "fixture", 1)
        WorkerRuntime().use { runtime ->
            assertEquals(ExecutionResult.Success("1"), run(runtime, a))
            val nextTicket = authority.issue("a", "legado", "1", "fixture", 1)
            assertEquals(ExecutionResult.Success("2"), run(runtime, nextTicket))
            for (other in listOf(a.copy(sourceId = "b"), a.copy(profile = "other"), a.copy(namespace = "other"),
                a.copy(revision = "2"), a.copy(accountGeneration = 2))) {
                assertEquals(ExecutionResult.Success("1"), run(runtime, other))
            }
            assertEquals(ExecutionResult.Success("3"), run(runtime, a))
        }
    }

    @Test fun changingLibraryCodeOrRebuildingWorkerCreatesFreshClosures() {
        val id = authority.issue("a", "legado", "1")
        WorkerRuntime().use { runtime ->
            assertEquals(ExecutionResult.Success("1"), run(runtime, id))
            assertEquals(ExecutionResult.Success("41"), run(runtime, id, code = library.replace("n:0", "n:40")))
            assertEquals(ExecutionResult.Success("1"), run(runtime, id))
        }
        WorkerRuntime().use { runtime -> assertEquals(ExecutionResult.Success("1"), run(runtime, id)) }
    }

}
