package indi.renakoni.nextvol.sourceexecution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.ExecutionWire
import hnovel.execution.ExecutionPayload
import hnovel.execution.FailureCode
import hnovel.execution.SourceExecutionBroker
import hnovel.imports.*
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import hnovel.network.NetworkGrant
import hnovel.network.BrokerLimits
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/UID tests. A Robolectric service is not a substitute for this suite. */
@RunWith(AndroidJUnit4::class)
class IsolatedExecutionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun imageBytesRoundTripThroughTheIsolatedPacketWithoutTextFieldLimits() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("image-bytes", "legado", "1")
        val bytes = ByteArray(288102).also { java.util.Random(956).nextBytes(it) }
        val task = ExecutionTask.Rule("@js:result", hnovel.rules.RuleValue.Empty, hnovel.rules.OutputKind.Element,
            hnovel.rules.RuleLocation("ruleContent.imageDecode"), imageInput = java.util.Base64.getEncoder().encodeToString(bytes))
        try {
            val result = executor.execute(id, task, ExecutionLimits(timeoutMillis = 30000, maxOutputBytes = 16 * 1024 * 1024))
            assertTrue(result.toString(), result is ExecutionResult.Success)
            val value = Json.decodeFromString(hnovel.execution.ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value
            assertArrayEquals(bytes, java.util.Base64.getDecoder().decode((value as hnovel.rules.RuleValue.Text).value))
        } finally { executor.close() }
    }

    @Test fun unchangedInputIsEncodedOnceAndResolvedLibrariesReceiveANewEnvelope() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("encoding", "legado", "1", "fixture")
        val text = "chapter text ".repeat(100000)
        val limits = ExecutionLimits(timeoutMillis = 15000, maxDataBytes = 16 * 1024 * 1024)
        val root = java.io.File(context.cacheDir, "encoding-${System.nanoTime()}")
        try {
            MockWebServer().use { server ->
                server.start()
                SourceBroker(root.toPath()).use { sessions ->
                    val session = sessions.open(SourceScope("fixture", "encoding", "legado"),
                        listOf(NetworkGrant(server.url("/").toString(), true)))
                    for (remoteLibrary in listOf(false, true)) {
                        var visits = 0
                        val inputs = object : AbstractList<hnovel.rules.RuleValue>() {
                            override val size = 1
                            override fun get(index: Int): hnovel.rules.RuleValue {
                                visits++
                                return hnovel.rules.RuleValue.Text(text)
                            }
                        }
                        val library = if (remoteLibrary) buildJsonObject { put("lib", server.url("/lib").toString()) }.toString() else null
                        if (remoteLibrary) server.enqueue(MockResponse().setBody("var extra=7;"))
                        val task = ExecutionTask.Rule("@js:result[0].length" + if (remoteLibrary) "+extra" else "",
                            hnovel.rules.RuleValue.Items(inputs), hnovel.rules.OutputKind.Text, hnovel.rules.RuleLocation("ruleContent.content"),
                            libraryCode = library)
                        SourceExecutionBroker(id, authority, session, limits).use { broker ->
                            val result = executor.execute(id, task, limits, broker)
                            assertTrue(result.toString(), result is ExecutionResult.Success)
                            val value = Json.decodeFromString(hnovel.execution.ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value
                            assertEquals(hnovel.rules.RuleValue.Text((text.length + if (remoteLibrary) 7 else 0).toString()), value)
                            assertEquals(if (remoteLibrary) 2 else 1, visits)
                        }
                    }
                }
            }
        } finally { executor.close(); root.deleteRecursively() }
    }

    @Test fun largeWorkerResultsUseAPipeAndStillEnforceTheOutputBudget() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("large-result", "legado", "1")
        val random = java.util.Random(721)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val text = CharArray(512 * 1024) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
        val task = ExecutionTask.Echo(text)
        val limits = ExecutionLimits(timeoutMillis = 15000, maxOutputBytes = text.length)
        assertNotNull(ExecutionPayload.pack(ExecutionWire.encode(id, task, limits), ExecutionWire.MAX_INPUT_PACKET_BYTES))
        assertNull(ExecutionPayload.pack(ExecutionWire.encodeResult(ExecutionResult.Success(text)), IsolatedExecutionService.MAX_IPC_BYTES))
        try {
            val result = executor.execute(id, task, limits)
            assertTrue("Expected a pipe result, got ${(result as? ExecutionResult.Failure)?.code}", result is ExecutionResult.Success)
            assertEquals(text, (result as ExecutionResult.Success).output)
            assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit),
                executor.execute(id, task, limits.copy(maxOutputBytes = text.length - 1)))
            assertEquals(ExecutionResult.Success("next"), executor.execute(id, ExecutionTask.Echo("next"), limits))
        } finally { executor.close() }
    }

    @Test fun stalledResultPipeCanBeCancelledAndClosesItsDescriptor() = runBlocking {
        val ends = ParcelFileDescriptor.createPipe()
        try {
            val reading = async(Dispatchers.IO) { readExecutionResultPacket(ends[0]) }
            delay(150)
            reading.cancel()
            withTimeout(2000) { reading.join() }
            assertFalse(ends[0].fileDescriptor.valid())
        } finally { ends.forEach { runCatching { it.close() } } }
    }

    @Test fun coldStartupIsBoundedSeparatelyAndSuccessfulCallsReuseTheWorker() = runBlocking {
        val binds = java.util.concurrent.atomic.AtomicInteger()
        val connections = java.util.concurrent.ConcurrentHashMap<ServiceConnection, ServiceConnection>()
        val host = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                val first = binds.incrementAndGet() == 1
                val delayed = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (first) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            { connection.onServiceConnected(name, binder) }, 3000)
                        else connection.onServiceConnected(name, binder)
                    }
                    override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
                    override fun onBindingDied(name: ComponentName) = connection.onBindingDied(name)
                    override fun onNullBinding(name: ComponentName) = connection.onNullBinding(name)
                }
                connections[connection] = delayed
                return super.bindService(intent, delayed, flags)
            }
            override fun unbindService(connection: ServiceConnection) = super.unbindService(connections.remove(connection)!!)
        }
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(host, authority)
        val first = authority.issue("first", "legado", "1")
        try {
            assertEquals(ExecutionResult.Success("ready"), executor.execute(first, ExecutionTask.Echo("ready"), ExecutionLimits(timeoutMillis = 2000)))
            val limits = ExecutionLimits(timeoutMillis = 15000)
            assertEquals(ExecutionResult.Success("1"), executor.execute(first, ExecutionTask.Script("var localOnly=1;localOnly"), limits))
            assertEquals(ExecutionResult.Success("\"undefined\""), executor.execute(first, ExecutionTask.Script("typeof localOnly"), limits))
            assertEquals(1, binds.get())
            authority.revoke(first)
            val second = authority.issue("second", "legado", "1")
            assertEquals(ExecutionResult.Success("next"), executor.execute(second, ExecutionTask.Echo("next"), limits))
            assertEquals(2, binds.get())
            assertEquals(ExecutionResult.Failure(FailureCode.ScriptRuntime), executor.execute(second, ExecutionTask.Script("throw new Error('fail')"), limits))
            assertEquals(ExecutionResult.Success("recovered"), executor.execute(second, ExecutionTask.Echo("recovered"), limits))
            assertEquals(3, binds.get())
        } finally { executor.close() }
    }

    @Test fun importedRulePipelineReachesReadingAcrossRealBinder() = runBlocking {
        hnovel.content.RuleSourceFixture().use { fixture ->
            val registry = indi.renakoni.nextvol.data.web.WebSourceRegistry(fixture.authority)
            val accounts = indi.renakoni.nextvol.data.web.SourceSessionManager(fixture.authority)
            val executor = AndroidIsolatedExecutor(context, fixture.authority)
            val runner = indi.renakoni.nextvol.di.WebDataSourceModule.provideRuleTaskRunner(executor)
            val root = java.io.File(context.cacheDir, "pipeline-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val host = object : android.content.ContextWrapper(context) { override fun getFilesDir() = root }
            val service = indi.renakoni.nextvol.data.web.rules.ImportedRuleSources(host, registry, fixture.authority, accounts, runner)
            try {
                val preview = service.importer.preview(fixture.raw().toString())
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                assertNull(service.importer.commit(preview, listOf(hnovel.imports.ImportSelection(0, hnovel.imports.ImportDecision.Add))).error)
                val definition = service.definitions.list().single()
                val id = service.activate(definition.reference(), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val runtime = (registry.resolve(id) as indi.renakoni.nextvol.data.web.SourceResolution.Ready).runtime
                val remoteId = fixture.server.url("/book/one").toString()
                val info = runtime.getBookInformation(remoteId)
                assertTrue(info.toString(), info.isOk)
                val directory = runtime.getBookVolumes(remoteId).component1()!!
                assertEquals(2, directory.volumes.single().chapters.size)
                val first = directory.volumes.single().chapters.first()
                val content = runtime.getChapterContent(first.id, remoteId).component1()!!
                assertEquals(first.id, content.id)
                assertTrue(content.content.toString(), content.content.toString().contains("A first"))
                assertTrue(content.content.toString().contains("last replaced"))
                assertArrayEquals(byteArrayOf(3, 2, 1), runtime.imageBytes(remoteId, fixture.server.url("/cover.png").toString(), true).component1())
            } finally { service.stop(); executor.close(); root.deleteRecursively() }
        }
    }

    @Test fun nativeArchivesFontsConversionAndMetadataRunInIsolatedProcess() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("native-tools", "legado", "1")
        val limits = ExecutionLimits(timeoutMillis = 30000)
        fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name").use { it.readBytes() }
        for ((extension, method) in listOf("zip" to "Zip", "rar" to "Rar", "7z" to "7z")) {
            val hex = fixture("chapter.$extension").joinToString("") { "%02x".format(it.toInt() and 255) }
            val result = executor.execute(id, ExecutionTask.Script("java.get${method}StringContent('$hex','chapter.txt')"), limits)
            assertEquals(extension, ExecutionResult.Success("\"synthetic chapter\""), result)
        }
        val good = java.util.Base64.getEncoder().encodeToString(fixture("plain.ttf"))
        val bad = java.util.Base64.getEncoder().encodeToString(fixture("obfuscated.ttf"))
        val supplementary = java.util.Base64.getEncoder().encodeToString(fixture("supplementary.ttf"))
        assertEquals(ExecutionResult.Success("\"A\""), executor.execute(id, ExecutionTask.Script(
            "java.replaceFont(String.fromCodePoint(0x100000),java.queryTTF('$supplementary'),java.queryTTF('$good'))"),limits))
        val task = ExecutionTask.Script("""
            var good=java.queryTTF('$good');var bad=java.queryTTF('$bad');
            [java.replaceFont('\uE000',bad,good),java.t2s('龍與書'),java.s2t('龙与书'),book.name,chapter.title]
        """, book=buildJsonObject { put("name", "Book") }, chapter=buildJsonObject { put("title", "Chapter") })
        assertEquals(ExecutionResult.Success("[\"A\",\"龙与书\",\"龍與書\",\"Book\",\"Chapter\"]"), executor.execute(id, task, limits))
    }

    @Test fun allocationFailureKillsWorkerAndAnotherSourceCanRun() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            val id = authority.issue("allocator", "legado", "1")
            // Keep the allocation reachable. A transient buffer may be collected before the
            // next sample; the documented monitor is an allocated-memory budget, not RSS.
            val result = executor.execute(id, ExecutionTask.Script("holder.bytes=new ArrayBuffer(192*1024*1024);holder.bytes.byteLength",
                libraryCode = "var holder={};"),
                ExecutionLimits(timeoutMillis = 15000))
            assertEquals(ExecutionResult.Failure(FailureCode.ProcessExited), result)
            val next = authority.issue("other", "legado", "1")
            assertEquals(ExecutionResult.Success("42"), executor.execute(next, ExecutionTask.Script("21*2"),
                ExecutionLimits(timeoutMillis = 15000)))
        } finally { executor.close() }
    }

    @Test fun legacyCipherHelpersMatchAndroidProvidersInsideTheIsolatedWorker() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("crypto", "legado", "1")
        val expressions = mutableListOf<String>()
        val expected = mutableListOf<JsonElement>()
        for ((algorithm, key) in listOf("AES" to "0123456789abcdef", "DES" to "01234567", "DESede" to "0123456789abcdefABCDEFGH")) {
            val cipher = javax.crypto.Cipher.getInstance("$algorithm/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.toByteArray(), algorithm))
            val encoded = android.util.Base64.encodeToString(cipher.doFinal("chapter".toByteArray()), android.util.Base64.NO_WRAP)
            val encrypt = when (algorithm) {
                "AES" -> "java.aesEncodeToBase64String('chapter','$key','AES/ECB/PKCS5Padding','')"
                "DES" -> "java.desEncodeToBase64String('chapter','$key','DES/ECB/PKCS5Padding','')"
                else -> "java.tripleDESEncodeBase64Str('chapter','$key','ECB','PKCS5Padding','')"
            }
            val decrypt = when (algorithm) {
                "AES" -> "java.aesEncodeToString('$encoded','$key','AES/ECB/PKCS5Padding','')"
                "DES" -> "java.desDecodeToString('$encoded','$key','DES/ECB/PKCS5Padding','')"
                else -> "java.tripleDESDecodeStr('$encoded','$key','ECB','PKCS5Padding','')"
            }
            expressions += encrypt
            expected += JsonPrimitive(encoded)
            expressions += decrypt
            expected += JsonPrimitive("chapter")
        }
        try {
            val result = executor.execute(id, ExecutionTask.Script(expressions.joinToString(",", "[", "]")), ExecutionLimits(timeoutMillis = 15000))
            assertTrue(result.toString(), result is ExecutionResult.Success)
            assertEquals(JsonArray(expected), Json.parseToJsonElement((result as ExecutionResult.Success).output))
        } finally { executor.close() }
    }

    @Test fun isolatedToolsMatchAndroidBase64AndPreserveNativeByteData() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-tools", "legado", "1")
        val expressions = mutableListOf<String>()
        val expected = mutableListOf<JsonElement>()
        for (text in listOf("", "a", "ab", "abc", "\uFFFF", "a".repeat(58), "\u4E2D".repeat(60))) {
            val literal = JsonPrimitive(text)
            for (flags in 0..31) {
                val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), flags)
                expressions += "java.base64Encode($literal,$flags)"
                expected += JsonPrimitive(encoded)
                expressions += "java.base64Decode(${JsonPrimitive(encoded)},$flags)"
                expected += JsonPrimitive(String(android.util.Base64.decode(encoded, flags), Charsets.UTF_8))
            }
        }
        for (encoded in listOf("YQ", " YQ==\n", "a", "YQ=", "Y!Q==", "YQ==YQ==", "77-_", "77+/", "")) {
            for (flags in listOf(0, 8)) {
                expressions += "(function(){try{return java.base64Decode(${JsonPrimitive(encoded)},$flags)}catch(e){return 'invalid'}})()"
                expected += JsonPrimitive(try { String(android.util.Base64.decode(encoded, flags), Charsets.UTF_8) }
                    catch (_: IllegalArgumentException) { "invalid" })
            }
        }
        expressions += "java.strToBytes('\u4E2D','GBK')"
        expected.add(JsonArray(listOf(JsonPrimitive(-42), JsonPrimitive(-48))))
        expressions += "java.bytesToStr(java.strToBytes('\u4E2D'))"
        expected += JsonPrimitive("\u4E2D")
        expressions += "java.md5Encode('abc')"
        expected += JsonPrimitive("900150983cd24fb0d6963f7d28e17f72")
        expressions += "java.HMacHex('data','HmacSHA256','key')"
        expected += JsonPrimitive("5031fe3d989c6d1537a013fa6e739da23463fdaec3b70137d828e36ace221bd0")
        val result = executor.execute(id, ExecutionTask.Script(expressions.joinToString(",", "[", "]")),
            ExecutionLimits(timeoutMillis = 15000))
        assertTrue(result.toString(), result is ExecutionResult.Success)
        assertEquals(JsonArray(expected), Json.parseToJsonElement((result as ExecutionResult.Success).output))
    }

    @Test fun cancellingAjaxReleasesBrokerPermitForTheNextInvocation() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-a", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "cancel-${java.util.UUID.randomUUID()}").toPath()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(root, limits = BrokerLimits(concurrency = 1)).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "source-a", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val broker = SourceExecutionBroker(id, authority, session, limits, server.url("/").toString())
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async { executor.execute(id, ExecutionTask.Script("java.ajax('/slow')", baseUrl = server.url("/").toString()), limits, broker) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                pending.cancel()
                withTimeout(5000) { pending.join() }
                server.enqueue(MockResponse().setBody("next"))
                SourceExecutionBroker(id, authority, session, limits, server.url("/").toString()).use { next ->
                    assertEquals(ExecutionResult.Success("\"next\""), executor.execute(id,
                        ExecutionTask.Script("java.ajax('/next')", baseUrl = server.url("/").toString()), limits, next))
                }
                assertEquals("/next", server.takeRequest(3, TimeUnit.SECONDS)?.path)
            }
        }
    }

    @Test fun remoteBinderUsesIsolatedUidAndEnforcesWireLimits() = runBlocking {
        val connected = CompletableDeferred<IIsolatedExecutionService>()
        val death = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                binder.linkToDeath({ death.complete(Unit) }, 0)
                connected.complete(IIsolatedExecutionService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, IsolatedExecutionService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val service = withTimeout(15000) { connected.await() }
            assertNotEquals(Process.myUid(), service.workerUid())
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkPermission(android.Manifest.permission.INTERNET, -1, service.workerUid()))
            assertNull(service.asBinder().queryLocalInterface(IIsolatedExecutionService.Stub.DESCRIPTOR))
            assertForeignUidRejected(service.asBinder())
            val result = CompletableDeferred<ExecutionResult>()
            service.execute(ByteArray(ExecutionWire.MAX_INPUT_PACKET_BYTES + 1), object : IExecutionCallback.Stub() {
                override fun onResult(bytes: ByteArray) { result.complete(ExecutionWire.decodeResult(bytes)) }
                override fun onResultFile(pipe: ParcelFileDescriptor) {
                    pipe.close()
                    result.completeExceptionally(AssertionError("Input rejection must use the small result callback"))
                }
            }, null)
            assertEquals(ExecutionResult.Failure(FailureCode.InputLimit), withTimeout(5000) { result.await() })
            service.terminate()
            withTimeout(5000) { death.await() }
        } finally { context.unbindService(connection) }
    }

    private suspend fun assertForeignUidRejected(service: IBinder) {
        val connected = CompletableDeferred<IForeignExecutionProbe>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IForeignExecutionProbe.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, ForeignExecutionProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(withTimeout(15000) { connected.await() }.isRejected(service)) }
        finally { context.unbindService(connection) }
    }

    @Test fun timeoutKillsWorkerAndAnotherSourceCanStart() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), executor.execute(a,
            ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 4000)))
        val b = authority.issue("source-b", "legado", "1")
        assertEquals(ExecutionResult.Success("new process"), executor.execute(b,
            ExecutionTask.Echo("new process"), ExecutionLimits(timeoutMillis = 15000)))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidIdentity), executor.execute(
            b.copy(sourceId = "source-a"), ExecutionTask.Echo("forged")))
    }

    @Test fun revokeAndCancellationRetireWorkers() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        val pending = async { executor.execute(a, ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 15000)) }
        delay(1500)
        authority.revoke(a)
        assertEquals(ExecutionResult.Failure(FailureCode.Revoked), withTimeout(5000) { pending.await() })
        val b = authority.issue("source-b", "legado", "1")
        val cancelled = async { executor.execute(b, ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 15000)) }
        delay(1500)
        cancelled.cancel()
        withTimeout(5000) { cancelled.join() }
        assertEquals(ExecutionResult.Success("after cancellation"), executor.execute(b,
            ExecutionTask.Echo("after cancellation"), ExecutionLimits(timeoutMillis = 15000)))
    }
}
