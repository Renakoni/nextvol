package indi.renakoni.nextvol.sourceexecution

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.room.Room
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.*
import coil3.size.Size
import coil3.transform.Transformation
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.image.*
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.SourceSessionManager
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.data.web.rules.SourceLoginService
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.ui.components.BookCoverImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** Real rule preparation/HTTP, Coil decoding, login event and rendered pixels on one page. */
class RuleImageRecoveryInstrumentedTest {
    // Queue background image completions instead of resuming UI frames on the completing thread.
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()

    @Test fun ruleFailuresRecoverAfterLoginWithoutEvictingSuccessfulImagesOrReplayingOffscreen() {
        val root = File(compose.activity.cacheDir, "rule-cover-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(compose.activity) { override fun getFilesDir() = root }
        val authority = ExecutionAuthority()
        val registry = WebSourceRegistry(authority)
        val accounts = SourceSessionManager(authority)
        val executor = AndroidIsolatedExecutor(context, authority)
        val runner = RuleTaskRunner { owner, task, limits, bridge -> executor.execute(owner, task, limits, bridge) }
        val sources = ImportedRuleSources(context, registry, authority, accounts, runner)
        val database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        val cache = DiskCache.Builder().directory(File(root, "images").path.toPath()).maxSizeBytes(1024 * 1024).build()
        val loadingGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val loadingStarts = AtomicInteger()
        val placeholderKey = AtomicReference<MemoryCache.Key?>(null)
        val loader = ImageLoader.Builder(context).diskCache(cache).components {
            add(Interceptor { chain ->
                loadingGate.get()?.let { gate ->
                    placeholderKey.set(chain.request.placeholderMemoryCacheKey)
                    loadingStarts.incrementAndGet()
                    gate.await()
                }
                chain.proceed()
            })
            add(SourceImageInterceptor(registry, context,
                BookDownloadStore(context, database, ContentJsonDecoder(ContentComponentRegistry()))))
            add(SourceImageFetcher.Factory())
        }.build()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val fallback = AtomicReference<Boolean?>(null)
        val failure = AtomicReference<Throwable?>(null)
        var shown by mutableStateOf<ImageRequest?>(null)
        compose.setContent { MaterialTheme {
            shown?.let { BookCoverImage(it, "fixture", 72.dp, 108.dp, "Rule fixture",
                onFallbackChanged = { value -> fallback.set(value) }, imageLoader = loader) }
        } }
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/slow") return MockResponse().setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(png)).setBodyDelay(30, TimeUnit.SECONDS)
                    if (request.getHeader("Cookie").orEmpty().contains("auth=fresh"))
                        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                    return when (request.path) {
                        "/403" -> MockResponse().setResponseCode(403).setBody("denied")
                        "/429" -> MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("limited")
                        "/html" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>verification</html>")
                        else -> MockResponse().setBody("x".repeat(1000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString()
            val raw = buildJsonObject {
                put("bookSourceUrl", base); put("bookSourceName", "Rule recovery fixture"); put("bookSourceType", 0)
                put("enabled", true); put("enabledCookieJar", true)
                put("loginUi", "[]")
                put("loginUrl", "function login(){source.putLoginHeader(JSON.stringify({Cookie:'auth=fresh'}));}")
            }
            val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
            assertNull(saved.error)
            val id = runBlocking { sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(base, true))) }
            val login = SourceLoginService(sources, accounts)
            for ((index, mode) in listOf("403", "429", "html", "disconnect").withIndex()) {
                runBlocking { login.logout(id) }
                fallback.set(null); failure.set(null)
                val request = ImageRequest.Builder(context)
                    .data(SourceImage(SourceBookId(id, base + "book"), server.url("/$mode").toString(), cover = true, preferDownloaded = false))
                    .size(4, 4).memoryCachePolicy(CachePolicy.DISABLED)
                    .listener(onError = { _, error -> failure.set(error.throwable) }).build()
                compose.runOnIdle { shown = request }
                compose.waitUntil(20_000) { fallback.get() == true }
                compose.waitForIdle()
                assertEquals("$mode initial requests", index * 2 + 1, server.requestCount)
                if (mode == "403" || mode == "429") {
                    val error = failure.get() as SourceImageRequestException
                    assertEquals(mode.toInt(), error.httpStatus)
                    assertFalse(error.networkFailure)
                    assertEquals(if (mode == "429") 3000L else null, error.retry?.retryAfterMillis)
                } else if (mode == "html") assertFalse(failure.get() is SourceImageRequestException)
                else assertEquals(hnovel.content.ContentError.Network, (failure.get() as SourceImageRequestException).contentError)
                runBlocking { login.submit(login.begin(id), emptyMap()) }
                compose.waitUntil(20_000) { fallback.get() == false }
                compose.waitForIdle()
                assertEquals("$mode recovery requests", index * 2 + 2, server.requestCount)
                val rendered = compose.onNodeWithContentDescription(compose.activity.getString(R.string.cover_description, "Rule fixture"))
                    .captureToImage().asAndroidBitmap()
                assertEquals(Color.BLUE, rendered.getPixel(rendered.width / 2, rendered.height / 2))
                assertTrue(runBlocking { loader.execute(request) } is SuccessResult)
                runBlocking { login.submit(login.begin(id), emptyMap()) }
                compose.waitForIdle()
                assertEquals("successful image must remain cached", index * 2 + 2, server.requestCount)
                compose.runOnIdle { shown = null }
                compose.waitForIdle()
                compose.runOnIdle { SourceImageRetryEvents.request(id) }
                compose.waitForIdle()
                assertEquals("disposed image must not retry", index * 2 + 2, server.requestCount)
            }
            val completed = AtomicReference<SuccessResult?>(null)
            val cover = ImageRequest.Builder(context)
                .data(SourceImage(SourceBookId(id, base + "book"), server.url("/cover").toString(), cover = true))
                .transformations(object : Transformation() {
                    override val cacheKey = "cover-fixture"
                    override suspend fun transform(input: Bitmap, size: Size) = input
                })
                .size(4, 4).listener(onSuccess = { _, result -> completed.set(result) }).build()
            compose.runOnIdle { fallback.set(null); shown = cover }
            compose.waitUntil(20_000) { fallback.get() == false }
            val cachedKey = checkNotNull(completed.get()?.memoryCacheKey)
            assertTrue("retain transformation extras", cachedKey.extras.isNotEmpty())
            val requestsWithCover = server.requestCount
            for ((index, next) in listOf(cover, cover.newBuilder().size(8, 8).build(), cover).withIndex()) {
                compose.runOnIdle { shown = null }
                compose.waitForIdle()
                if (index == 2) loader.memoryCache?.clear()
                val gate = CompletableDeferred<Unit>()
                loadingGate.set(gate)
                compose.runOnIdle { fallback.set(null); completed.set(null); shown = next }
                compose.waitUntil(10_000) { loadingStarts.get() == index + 1 }
                compose.waitForIdle()
                assertEquals(if (index == 2) null else cachedKey, placeholderKey.get())
                val loading = compose.onNodeWithContentDescription(compose.activity.getString(R.string.cover_description, "Rule fixture"))
                    .captureToImage().asAndroidBitmap()
                if (index < 2) assertEquals(Color.BLUE, loading.getPixel(loading.width / 2, loading.height / 2))
                else assertNotEquals(Color.BLUE, loading.getPixel(loading.width / 2, loading.height / 2))
                assertNull(fallback.get())
                loadingGate.set(null)
                gate.complete(Unit)
                compose.waitUntil(20_000) { fallback.get() == false }
                assertEquals(if (index == 0) DataSource.MEMORY_CACHE else DataSource.DISK, completed.get()?.dataSource)
                if (index == 1) assertTrue("larger target must decode enough pixels", completed.get()!!.image.width >= 8)
                assertEquals("reuse the original bytes", requestsWithCover, server.requestCount)
            }
            compose.runOnIdle { shown = null }
            compose.waitForIdle()
            val cancelled = AtomicBoolean(false)
            runBlocking { sources.loginTarget(id).session.traceRequests(hnovel.network.RequestTrace {
                if (it.evidence == hnovel.network.RequestEvidence.Cancelled) cancelled.set(true)
            }) }
            val slow = ImageRequest.Builder(context)
                .data(SourceImage(SourceBookId(id, base + "book"), server.url("/slow").toString(), cover = true, preferDownloaded = false))
                .size(4, 4).build()
            compose.runOnIdle { shown = slow }
            compose.waitUntil(10_000) { server.requestCount == requestsWithCover + 1 }
            compose.runOnIdle { shown = null }
            compose.waitUntil(10_000) { cancelled.get() }
            compose.runOnIdle { SourceImageRetryEvents.request(id) }
            compose.waitForIdle()
            assertEquals("leaving the page cancels the pending body and does not replay it", requestsWithCover + 1, server.requestCount)
        } } finally {
            compose.runOnIdle { shown = null }
            compose.waitForIdle()
            loader.shutdown(); cache.shutdown()
            runBlocking { sources.stop(); executor.close() }
            database.close(); root.deleteRecursively()
        }
    }
}
