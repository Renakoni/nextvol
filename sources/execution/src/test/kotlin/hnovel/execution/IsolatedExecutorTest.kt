package hnovel.execution
import org.junit.Assert.*
import org.junit.Test
class IsolatedExecutorTest {
 @Test fun cryptoAndNestedRulesLoadInsideTheChildClasspath() {
  val task=ExecutionTask.Script("var c=java.createSymmetricCrypto('AES/ECB/PKCS5Padding','0123456789abcdef');java.getString('@js:c.decryptStr(c.encrypt(\"chapter\"))')")
  assertEquals(ExecutionResult.Success("\"chapter\""),IsolatedExecutor().execute(id,task,ExecutionLimits(timeoutMillis=15000)))
 }
 @Test fun pipeSizedRepliesDoNotDeadlockAndBothWireDirectionsAreBounded() {
  val executor = IsolatedExecutor()
  val text = "x".repeat(100_000)
  val limits = ExecutionLimits(timeoutMillis = 15000, maxOutputBytes = 400_000)
  assertEquals(ExecutionResult.Success(text), executor.execute(id, ExecutionTask.Echo(text), limits))
  assertEquals(ExecutionResult.Failure(FailureCode.InputLimit), executor.execute(id, ExecutionTask.Echo("x".repeat(ExecutionWire.MAX_INPUT_BYTES)), limits))
  assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), executor.execute(id, ExecutionTask.Script("'x'.repeat(500000)"), limits))
 }
 @Test fun allocationFailureStaysInChildAndTheNextInvocationSucceeds() {
  val executor = IsolatedExecutor()
  assertEquals(ExecutionResult.Failure(FailureCode.ProcessExited), executor.execute(id,
   ExecutionTask.Script("new ArrayBuffer(128*1024*1024).byteLength"), ExecutionLimits(timeoutMillis = 15000)))
  assertEquals(ExecutionResult.Success("42"), executor.execute(id.copy(sourceId = "other"), ExecutionTask.Script("21*2")))
 }
 @Test fun hostIssuedIdentityRejectsForgeryAndRevocationKillsLateWorker() {
  val authority = ExecutionAuthority(); val issued = authority.issue("source-a", "legado", "r1")
  val executor = IsolatedExecutor(authority = authority)
  assertEquals(FailureCode.InvalidIdentity, (executor.execute(issued.copy(sourceId = "source-b"), ExecutionTask.Echo("x")) as ExecutionResult.Failure).code)
  val thread = kotlin.concurrent.thread { Thread.sleep(40); authority.revoke(issued) }
  assertEquals(FailureCode.Revoked, (executor.execute(issued, ExecutionTask.Sleep(10000), ExecutionLimits(1000,1000,1)) as ExecutionResult.Failure).code)
  thread.join()
 }
 @Test fun workerDoesNotDependOnTheHostsBootstrapClasspath() {
  val original = System.getProperty("java.class.path")
  try {
   System.setProperty("java.class.path", "host-bootstrap-without-worker.jar")
   assertEquals(ExecutionResult.Success("loaded from actual code sources"),
    IsolatedExecutor().execute(id, ExecutionTask.Echo("loaded from actual code sources")))
  } finally { if (original == null) System.clearProperty("java.class.path") else System.setProperty("java.class.path", original) }
 }
 private val id=ExecutionIdentity("source-a","legado","r1","nonce")
 @Test fun hungWorkerIsKilledByHostDeadline() { assertEquals(ExecutionResult.Failure(FailureCode.Timeout), IsolatedExecutor().execute(id,ExecutionTask.Sleep(10000),ExecutionLimits(100,1000,1))) }
}
