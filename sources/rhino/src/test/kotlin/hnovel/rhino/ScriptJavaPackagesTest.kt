package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ScriptJavaPackagesTest {
    private val frame = ScriptFrame("java-packages", "legado")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Data operations need no host authority") })

    @Test fun invalidWaitAndClockArgumentsAreCatchableScriptErrors() {
        for (arguments in listOf("", "-1", "0.5", "NaN", "Infinity", "null", "'1'", "true", "{}", "1, 2", "1e30")) {
            assertEquals(arguments, ScriptResult.Success("\"invalid\""), engine.evaluate(
                "try { Packages.java.lang.Thread.sleep($arguments); 'accepted'; } catch (e) { 'invalid'; }", frame))
        }
        assertEquals(ScriptResult.Success("\"invalid\""), engine.evaluate(
            "try { Packages.java.lang.System.currentTimeMillis(1); } catch (e) { 'invalid'; }", frame))
    }

    @Test(timeout = 5000) fun waitsHonorDeadlinesEvenWhenRetainedOrCaught() {
        val small = RhinoScriptEngine(HostBridge.None, ScriptLimits(timeoutMillis = 100))
        assertEquals(FailureCode.Timeout, (small.evaluate(
            "try { Packages.java.lang.Thread.sleep(1000000); } catch(e) { 'caught'; }", frame) as ScriptResult.Failure).code)
        ScriptLibrary(frame.sourceId, frame.profile, "var saved = {};").use { library ->
            assertEquals(ScriptResult.Success("1"), engine.evaluate("saved.sleep = Packages.java.lang.Thread.sleep; 1", frame, library))
            assertEquals(FailureCode.Timeout, (small.evaluate("saved.sleep(1000000)", frame, library) as ScriptResult.Failure).code)
        }
    }

    @Test(timeout = 5000) fun interruptEndsAnActiveWaitAndTheEngineRemainsUsable() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val waitingEngine = RhinoScriptEngine(HostBridge { _, _ -> entered.countDown(); JsonPrimitive(true) })
        val result = java.util.concurrent.atomic.AtomicReference<ScriptResult>()
        val thread = Thread { result.set(waitingEngine.evaluate(
            "host.call('entered'); Packages.java.lang.Thread.sleep(4000); 1", frame)) }
        thread.start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (thread.state != Thread.State.TIMED_WAITING && thread.isAlive && System.nanoTime() < until) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, thread.state)
            thread.interrupt()
            thread.join(1000)
            assertFalse(thread.isAlive)
            assertEquals(FailureCode.Cancelled, (result.get() as ScriptResult.Failure).code)
            assertEquals(ScriptResult.Success("42"), waitingEngine.evaluate("42", frame))
        } finally { thread.interrupt(); thread.join(1000) }
    }

    @Test fun importedJavaCryptoCanReadTheOriginalCallingConvention() {
        val text = "目录与正文保持原规则"
        val iv = "0123456789abcdef".toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("242ccb8230d709e1".toByteArray(), "AES"), IvParameterSpec(iv))
        }
        val encoded = Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
        val result = engine.evaluate("""
            var javaImport = new JavaImporter();
            javaImport.importPackage(Packages.java.lang, Packages.javax.crypto.spec,
                Packages.javax.crypto, Packages.java.util);
            with (javaImport) {
                function decode(content) {
                    var ivEncData = Base64.getDecoder().decode(String(content));
                    var key = SecretKeySpec(String("242ccb8230d709e1").getBytes(), "AES");
                    var iv = IvParameterSpec(Arrays.copyOfRange(ivEncData, 0, 16));
                    var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(2, key, iv);
                    return String(cipher.doFinal(Arrays.copyOfRange(ivEncData, 16, ivEncData.length)));
                }
                decode('$encoded');
            }
        """.trimIndent(), frame)
        assertEquals(ScriptResult.Success(JsonPrimitive(text).toString()), result)
    }

    @Test fun packageCallsPreserveJavaStringEncodingAndArrayPadding() {
        assertEquals(ScriptResult.Success("\"中文\""), engine.evaluate("""
            var s = new Packages.java.lang.String('中文');
            new Packages.java.lang.String(s.getBytes('UTF-16LE'), 'UTF-16LE');
        """.trimIndent(), frame))
        assertEquals(ScriptResult.Success("[2,3,0,0]"), engine.evaluate(
            "Packages.java.util.Arrays.copyOfRange([1,2,3], 1, 5)", frame))
        assertEquals(ScriptResult.Success("\"chapter\""), engine.evaluate("""
            new Packages.java.lang.String(Packages.java.util.Base64.getDecoder().decode('Y2hhcHRlcg=='));
        """.trimIndent(), frame))
        assertEquals(ScriptResult.Success("\"Y2hhcHRlcg==\""), engine.evaluate(
            "Packages.cn.hutool.core.codec.Base64.encode('chapter')", frame))
    }

    @Test fun importsAndDataObjectsDoNotExposeReflectionProvidersOrIo() {
        // An unavailable class is a NativeJavaPackage placeholder, not undefined.
        assertEquals(ScriptResult.Success("\"object/undefined\""), engine.evaluate(
            "typeof Packages.java.lang.Runtime + '/' + typeof java.getClass", frame))
        val result = engine.evaluate("""
            var j = new JavaImporter(Packages.java.lang, Packages.javax.crypto);
            with (j) {
                var text = String('chapter');
                var cipher = Cipher.getInstance('AES/CBC/PKCS5Padding');
                [typeof text.getClass, typeof cipher.getClass, typeof cipher.getProvider, typeof String.class];
            }
        """.trimIndent(), frame)
        assertEquals(ScriptResult.Success("[\"undefined\",\"undefined\",\"undefined\",\"undefined\"]"), result)
        for (script in listOf(
            "Packages.java.lang.Runtime.getRuntime()",
            "new JavaImporter(Packages.java.lang); Packages.java.lang.System.exit(0)",
            "new Packages.java.io.File('/unused')",
            "Packages.java.lang.Class.forName('java.lang.Runtime')",
            "new Packages.java.net.Socket('127.0.0.1', 1)",
        )) assertTrue(script, engine.evaluate(script, frame) is ScriptResult.Failure)
    }

    @Test fun byteArrayAllocationAndRetainedMethodsUseTheCurrentBudget() {
        val small = RhinoScriptEngine(HostBridge.None, ScriptLimits(maxBridgeChars = 64))
        assertEquals(FailureCode.ResultTooLarge, (small.evaluate(
            "Packages.java.util.Arrays.copyOfRange([1], 0, 1000000000)", frame) as ScriptResult.Failure).code)
        ScriptLibrary(frame.sourceId, frame.profile, "var retained={};").use { library ->
            assertTrue(engine.evaluate("""
                retained.decode = Packages.java.util.Base64.getDecoder().decode; 42;
            """.trimIndent(), frame, library) is ScriptResult.Success)
            val value = small.evaluate("retained.decode('YQ=='.repeat(100))", frame, library)
            assertEquals(FailureCode.ResultTooLarge, (value as ScriptResult.Failure).code)
        }
        assertEquals(JsonPrimitive("chapter"), Json.parseToJsonElement((engine.evaluate(
            "new Packages.java.lang.String([99,104,97,112,116,101,114])", frame) as ScriptResult.Success).json))
    }
}
