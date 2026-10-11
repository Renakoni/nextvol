package hnovel.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import hnovel.rhino.HostBridge
import hnovel.rhino.RhinoScriptEngine
import hnovel.rhino.ScriptFrame
import hnovel.rhino.ScriptLimits
import hnovel.rhino.ScriptResult
import hnovel.rhino.ScriptLibrary
import hnovel.rules.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private class WorkerOutputLimit : RuntimeException()

@Serializable data class ExecutionIdentity(val sourceId: String, val profile: String, val revision: String, val nonce: String,
 val namespace: String = "default", val accountGeneration: Long = 0) {
 init { require(sourceId.isNotBlank() && profile.isNotBlank() && revision.isNotBlank() && nonce.isNotBlank() && namespace.isNotBlank() && accountGeneration >= 0) }
}
@Serializable data class ExecutionLimits(val timeoutMillis: Long = 5000, val maxOutputBytes: Int = 65536, val maxRequests: Int = 16,
 val maxDataBytes: Int? = null) {
 init { require(timeoutMillis in 1..300000 && maxOutputBytes in 1..16 * 1024 * 1024 && maxRequests in 0..1024 &&
  (maxDataBytes == null || maxDataBytes in 1..BridgeWire.MAX_REPLY_BYTES)) }
}

// Callers can grant a larger input-data budget independently of the small final rule output.
// Without an explicit grant, retain the existing native-method and response-read limits.
internal val ExecutionLimits.scriptDataLimit: Int get() = maxDataBytes ?: maxOutputBytes.coerceIn(ScriptLimits.DEFAULT_BRIDGE_CHARS, 192 * 1024)
@Serializable sealed interface ExecutionTask {
 @Serializable data class Echo(val value: String): ExecutionTask
 @Serializable data class Sleep(val millis: Long): ExecutionTask
 @Serializable data class ContentMarkup(val html: String,
  val location: RuleLocation = RuleLocation("ruleContent.parts"), val formatted: Boolean = false) : ExecutionTask
 @Serializable data class DiscoveryReadPlan(val urls: List<String>, val header: String, val rules: List<String>) : ExecutionTask
 @Serializable data class SearchBooks(val inputs: List<RuleValue>, val rules: Map<String, String>, val baseUrl: String,
  val fallbackTitle: String = "") : ExecutionTask {
  companion object {
   const val MAX_ROWS = 8
   val FIELDS = listOf("name", "author", "kind", "wordCount", "lastChapter", "intro", "coverUrl", "updateTime", "bookUrl")
  }
 }
 @Serializable data class BookOverviews(val inputs: List<RuleValue>, val nameRule: String, val urlRule: String,
  val baseUrl: String, val fallbackTitle: String = "", val field: String = "ruleExplore",
  val coverRule: String = "") : ExecutionTask {
  companion object {
   const val MAX_ROWS = 8
   fun supports(rule: String) = listOf("@js:", "<js>", "{{", "@put:", "@get:").none { rule.contains(it, ignoreCase = true) }
  }
 }
 @Serializable data class Script(val code: String, val result: JsonElement = JsonNull, val bookId: String? = null,
  val chapterId: String? = null, val key: String = "", val page: Int = 1, val baseUrl: String = "",
  val libraryCode: String? = null, val book: JsonObject = JsonObject(emptyMap()),
  val chapter: JsonObject = JsonObject(emptyMap()), val chineseConverter: Int = 0, val sourceLoginUrl: String = "",
  val sourceComment: String? = null, val nextChapterUrl: String? = null,
  val sourceHeaderRule: String = "", val speakText: String? = null, val speakSpeed: Int = 10) : ExecutionTask
 @Serializable data class Rule(val rule: String, val input: RuleValue, val output: OutputKind = OutputKind.TextList,
  val location: RuleLocation = RuleLocation("rule"), val bookId: String? = null, val chapterId: String? = null,
  val key: String = "", val page: Int = 1, val baseUrl: String = "", val libraryCode: String? = null,
  val sourceVariables: Map<String, String> = emptyMap(), val bookVariables: Map<String, String> = emptyMap(),
  val chapterVariables: Map<String, String> = emptyMap(), val book: JsonObject = JsonObject(emptyMap()),
  val chapter: JsonObject = JsonObject(emptyMap()), val bookBigVariables: Map<String, String> = emptyMap(),
  val chapterBigVariables: Map<String, String> = emptyMap(), val chineseConverter: Int = 0,
  val unescapeHtml: Boolean = true, val sourceHeaderRule: String = "", val discovery: JsonObject? = null,
  val sourceLoginUrl: String = "", val sourceComment: String? = null, val nextChapterUrl: String? = null,
  val scriptTemplates: Boolean = true, val readOnly: Boolean = false) : ExecutionTask
}

fun ExecutionTask.libraryCode(): String? = when (this) {
 is ExecutionTask.Script -> libraryCode
 is ExecutionTask.Rule -> libraryCode
 else -> null
}
@Serializable sealed interface ExecutionResult {
 @Serializable data class Success(val output: String): ExecutionResult
 @Serializable data class Failure(val code: FailureCode, val ruleError: RuleError? = null,
  val dependency: ScriptDependency? = null, val hostCall: ScriptHostCall? = null): ExecutionResult
}
@Serializable enum class FailureCode { Timeout, ProcessExited, InvalidIdentity, OutputLimit, InvalidTask, Cancelled, Revoked, Busy, InputLimit, ScriptSyntax, ScriptRuntime, BridgeDenied, RuleRuntime, RequestSyntax, UnsupportedDependency }

/** Host authority for source identities. The worker never gets a method to issue or change a ticket. */
class ExecutionAuthority {
 private val active = ConcurrentHashMap<String, ExecutionIdentity>()
 private val sessions = ConcurrentHashMap<String, hnovel.network.SourceSession>()
 @Synchronized fun issue(sourceId: String, profile: String, revision: String, namespace: String = "default", accountGeneration: Long = 0): ExecutionIdentity =
  ExecutionIdentity(sourceId, profile, revision, UUID.randomUUID().toString(), namespace, accountGeneration).also { active[it.nonce] = it }
 @Synchronized fun revoke(identity: ExecutionIdentity) {
  if (active.remove(identity.nonce, identity)) sessions.remove(identity.nonce)
 }
 @Synchronized fun revokeSource(sourceId: String, namespace: String) {
  active.values.filter { it.sourceId == sourceId && it.namespace == namespace }.forEach(::revoke)
 }
 /** Atomically retain the host-prepared replacement ticket while retiring the old registration. */
 @Synchronized fun replaceSource(replacement: ExecutionIdentity, commit: () -> Unit) {
  check(accepts(replacement))
  commit()
  active.values.filter { it.sourceId == replacement.sourceId && it.namespace == replacement.namespace && it != replacement }.forEach(::revoke)
 }
 @Synchronized internal fun bindSession(identity: ExecutionIdentity, session: hnovel.network.SourceSession) {
  check(accepts(identity) && !session.closed)
  val previous = sessions.putIfAbsent(identity.nonce, session)
  check(previous == null || previous === session) { "Execution ticket already belongs to another session" }
 }
 @Synchronized fun accepts(identity: ExecutionIdentity): Boolean {
  if (active[identity.nonce] != identity) return false
  if (sessions[identity.nonce]?.closed == true) { revoke(identity); return false }
  return true
 }
 /** Storage/result commits and revocation share this lock; no new commit starts after revocation. */
 @Synchronized fun <T> authorized(identity: ExecutionIdentity, block: () -> T): T {
  check(accepts(identity)) { "Execution revoked" }
  return block()
 }
}

/** Host-side boundary. Each invocation receives a fresh process and a host-issued identity. */
class IsolatedExecutor(private val javaCommand: String = javaHome(), private val classPath: String = workerClassPath(),
 private val authority: ExecutionAuthority? = null) {
 fun execute(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits = ExecutionLimits()): ExecutionResult {
  if (identity.sourceId.isBlank() || (authority != null && !authority.accepts(identity))) return ExecutionResult.Failure(FailureCode.InvalidIdentity)
  val input = ExecutionWire.encode(identity, task, limits)
  if (input.size > ExecutionWire.MAX_INPUT_BYTES) return ExecutionResult.Failure(FailureCode.InputLimit)
  val deadline = System.nanoTime() + limits.timeoutMillis * 1_000_000
  val process = try { ProcessBuilder(javaCommand, "-Xmx64m", "-Xss1m", "-XX:+ExitOnOutOfMemoryError",
   "-cp", classPath, WorkerMain::class.java.name).start() }
    catch (_: Exception) { return ExecutionResult.Failure(FailureCode.ProcessExited) }
  val pipes = Executors.newFixedThreadPool(2) { Thread(it, "source-worker-io").apply { isDaemon = true } }
  try {
   val writing = pipes.submit { process.outputStream.use { it.write(input); it.write('\n'.code); it.flush() } }
   // Drain while the child runs: waiting for exit first deadlocks when a valid result fills
   // the OS pipe. Bound bytes before JSON decoding, as on Android's Binder boundary.
   val reading = pipes.submit<ByteArray> {
    process.inputStream.use { stream ->
     val output = java.io.ByteArrayOutputStream()
     val buffer = ByteArray(8192)
     while (true) {
      val count = stream.read(buffer)
      if (count < 0) break
      if (output.size() + count > ExecutionWire.MAX_RESULT_BYTES) throw WorkerOutputLimit()
      output.write(buffer, 0, count)
     }
     output.toByteArray()
    }
   }
   while (process.isAlive && System.nanoTime() < deadline) {
    if (authority != null && !authority.accepts(identity)) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Revoked) }
    if (writing.isDone) writing.get()
    if (reading.isDone) reading.get()
    Thread.sleep(5)
   }
   // Startup/pipe scheduling also consumes the deadline; revocation during startup must
   // retain its identity failure even if the child was not ready before that deadline.
   if (authority != null && !authority.accepts(identity)) return ExecutionResult.Failure(FailureCode.Revoked)
   if (process.isAlive) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Timeout) }
   val bytes = reading.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
   if (authority != null && !authority.accepts(identity)) return ExecutionResult.Failure(FailureCode.Revoked)
   return ExecutionWire.decodeResult(bytes)
  } catch (_: InterruptedException) { process.destroyForcibly(); Thread.currentThread().interrupt(); return ExecutionResult.Failure(FailureCode.Cancelled) }
    catch (failure: ExecutionException) { return ExecutionResult.Failure(if (failure.cause is WorkerOutputLimit) FailureCode.OutputLimit else FailureCode.ProcessExited) }
    catch (_: TimeoutException) { return ExecutionResult.Failure(FailureCode.Timeout) }
    catch (_: Exception) { process.destroyForcibly(); return ExecutionResult.Failure(FailureCode.ProcessExited) }
  finally { if (process.isAlive) process.destroyForcibly(); pipes.shutdownNow() }
 }
 companion object {
  private fun javaHome() = java.nio.file.Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java").toString()

  // Gradle/plugin hosts can load these classes outside java.class.path. Locate the actual worker
  // and its runtime dependencies; callers with non-file classloaders must pass a packaged classpath.
  private fun workerClassPath(): String = (listOf(WorkerMain::class.java, Unit::class.java,
   kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
   RhinoScriptEngine::class.java, org.mozilla.javascript.Context::class.java, RuleEvaluator::class.java) +
   listOf("org.jsoup.Jsoup", "com.jayway.jsonpath.JsonPath", "net.minidev.json.JSONValue", "net.minidev.asm.BeansAccess",
    "org.objectweb.asm.ClassReader", "org.slf4j.LoggerFactory", "org.seimicrawler.xpath.JXDocument",
    "org.apache.commons.lang3.StringUtils", "org.apache.commons.text.StringEscapeUtils", "org.antlr.v4.runtime.Parser", "com.google.gson.Gson",
    "cn.hutool.crypto.KeyUtil", "cn.hutool.core.util.HexUtil", "com.github.liuyueyi.quick.transfer.ChineseUtils",
    "hnovel.rhino.charset.CharsetDetector", "hnovel.rhino.font.QueryTTF", "okhttp3.Response", "okio.Buffer").map { Class.forName(it) })
   .map { type ->
    val location = requireNotNull(type.protectionDomain?.codeSource?.location) { "Supply a worker runtime classpath" }
    require(location.protocol == "file") { "Supply a packaged worker runtime classpath" }
    java.nio.file.Paths.get(location.toURI()).toString()
   }.distinct().joinToString(java.io.File.pathSeparator)
 }
}
@Serializable private data class Wire(val identity: ExecutionIdentity, val task: ExecutionTask, val limits: ExecutionLimits,
 val libraryScripts: List<String>? = null)

/** Shared Android/JVM wire encoding; the authority stays in the host. */
object ExecutionWire {
 // Logical JSON can contain a complete catalogue. Android compresses these values before Binder;
 // packets and reverse host calls retain their smaller transport limits.
 // Include JSON escaping around a response body and the nested result envelope.
 const val MAX_INPUT_BYTES = 32 * 1024 * 1024
 const val MAX_RESULT_BYTES = 32 * 1024 * 1024
 const val MAX_INPUT_PACKET_BYTES = 512 * 1024
 fun encode(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits, libraryScripts: List<String>? = null): ByteArray =
  kotlinx.serialization.json.Json.encodeToString(Wire.serializer(), Wire(identity, task, limits, libraryScripts)).toByteArray(Charsets.UTF_8)
 fun encodeResult(result: ExecutionResult): ByteArray =
  kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), result).toByteArray(Charsets.UTF_8)
 fun decodeResult(bytes: ByteArray): ExecutionResult =
  kotlinx.serialization.json.Json.decodeFromString(ExecutionResult.serializer(), BridgeWire.validate(bytes, MAX_RESULT_BYTES))
}

/** Untrusted-side worker. It receives only the bound DTO and has no host repository/client references. */
class WorkerRuntime(private val archives: hnovel.rhino.ArchiveDecoder = hnovel.rhino.ArchiveDecoder.Zip) : AutoCloseable {
 private data class LibraryOwner(val namespace: String, val source: String, val profile: String,
  val revision: String, val accountGeneration: Long)
 private data class LibraryEntry(val code: String, val scripts: List<String>, val library: ScriptLibrary)
 private val libraries = LinkedHashMap<LibraryOwner, LibraryEntry>(16, 0.75f, true)

 private fun library(identity: ExecutionIdentity, code: String?, resolved: List<String>?): ScriptLibrary? {
  if (code.isNullOrBlank()) return null
  val key = LibraryOwner(identity.namespace, identity.sourceId, identity.profile, identity.revision, identity.accountGeneration)
  val current = libraries[key]
  val scripts = resolved ?: listOf(code)
  if (current?.code == code && current.scripts == scripts) return current.library
  current?.library?.close()
  val entry = LibraryEntry(code, scripts, ScriptLibrary(identity.sourceId, identity.profile, scripts))
  libraries[key] = entry
  if (libraries.size > 16) {
   val oldest = libraries.entries.iterator()
   oldest.next().value.library.close()
   oldest.remove()
  }
  return entry.library
 }

 @Synchronized override fun close() {
  libraries.values.forEach { it.library.close() }
  libraries.clear()
 }

 @Synchronized fun executeSerialized(input: String, bridge: HostBridge = HostBridge.None): String {
  if (input.length > ExecutionWire.MAX_INPUT_BYTES || input.toByteArray(Charsets.UTF_8).size > ExecutionWire.MAX_INPUT_BYTES)
   return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.InputLimit))
  val wire = try { kotlinx.serialization.json.Json.decodeFromString(Wire.serializer(),
   BridgeWire.validateNesting(input)) }
    catch (_: Exception) { return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.InvalidTask)) }
  if (SourceLibraryDefinition.isUrlMap(wire.task.libraryCode()) && wire.libraryScripts == null)
   return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.BridgeDenied))
  if (wire.task is ExecutionTask.Rule && wire.task.readOnly &&
   (!wire.task.libraryCode.isNullOrBlank() || !WorkerDiscoveryReadPlan.isReadOnlyRule(wire.task.rule)))
   return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.InvalidTask))
  val result = when (val task = wire.task) {
   is ExecutionTask.ContentMarkup -> WorkerContentMarkup.evaluate(task, wire.limits)
   is ExecutionTask.DiscoveryReadPlan -> WorkerDiscoveryReadPlan.evaluate(task, wire.limits)
   is ExecutionTask.BookOverviews -> WorkerBookOverviews.evaluate(task, wire.identity, wire.limits)
   is ExecutionTask.SearchBooks -> WorkerSearchBooks.evaluate(task, wire.identity, wire.limits)
   is ExecutionTask.Rule -> WorkerRuleEvaluator.evaluate(task, wire.identity, wire.limits, bridge,
    library(wire.identity, task.libraryCode, wire.libraryScripts), archives)
   is ExecutionTask.Echo -> if (task.value.toByteArray().size > wire.limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit) else ExecutionResult.Success(task.value)
   is ExecutionTask.Sleep -> { Thread.sleep(task.millis); ExecutionResult.Success("slept") }
   is ExecutionTask.Script -> {
    val ruleContext = hnovel.rules.RuleContext(wire.identity.sourceId, task.bookId, task.chapterId, task.baseUrl)
    WorkerRuleEvaluator.bindSourceVariables(ruleContext, bridge)
    val frame = ScriptFrame(wire.identity.sourceId, wire.identity.profile, task.bookId, task.chapterId,
     mapOf("result" to task.result), task.key, task.page, task.baseUrl, book = task.book, chapter = task.chapter,
     ruleContext = ruleContext,
     chineseConverter = task.chineseConverter, sourceLoginUrl = task.sourceLoginUrl, sourceComment = task.sourceComment,
     nextChapterUrl = task.nextChapterUrl, sourceHeaderRule = task.sourceHeaderRule,
     speakText = task.speakText, speakSpeed = task.speakSpeed)
    when (val evaluated = RhinoScriptEngine(bridge, ScriptLimits(timeoutMillis = wire.limits.timeoutMillis, maxResultChars = wire.limits.maxOutputBytes,
     maxBridgeChars = wire.limits.scriptDataLimit), archives)
     .evaluate(task.code, frame, library(wire.identity, task.libraryCode, wire.libraryScripts))) {
     is ScriptResult.Success -> if (evaluated.json.toByteArray(Charsets.UTF_8).size > wire.limits.maxOutputBytes)
      ExecutionResult.Failure(FailureCode.OutputLimit) else ExecutionResult.Success(evaluated.json)
     is ScriptResult.Failure -> ExecutionResult.Failure(when (evaluated.code) {
      hnovel.rhino.FailureCode.Timeout -> FailureCode.Timeout
      hnovel.rhino.FailureCode.Cancelled -> FailureCode.Cancelled
      hnovel.rhino.FailureCode.Syntax -> FailureCode.ScriptSyntax
      hnovel.rhino.FailureCode.BridgeDenied -> FailureCode.BridgeDenied
      hnovel.rhino.FailureCode.RequestSyntax -> FailureCode.RequestSyntax
      hnovel.rhino.FailureCode.UnsupportedDependency -> FailureCode.UnsupportedDependency
      hnovel.rhino.FailureCode.ResultTooLarge -> FailureCode.OutputLimit
      else -> FailureCode.ScriptRuntime
     }, evaluated.dependency?.let { RuleError(RuleStage.Script,
      RuleLocation(if (evaluated.inLibrary) "jsLib" else "script"), "UnsupportedDependency.${it.name}") }, evaluated.dependency, evaluated.hostCall)
    }
   }
  }
  return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), result)
 }
}

object WorkerMain {
 fun executeSerialized(input: String, bridge: HostBridge = HostBridge.None): String =
  WorkerRuntime().use { it.executeSerialized(input, bridge) }

 @JvmStatic fun main(args: Array<String>) {
  val input = System.`in`.bufferedReader(Charsets.UTF_8).readLine() ?: return
  System.out.write(executeSerialized(input).toByteArray(Charsets.UTF_8))
  System.out.flush()
 }
}
