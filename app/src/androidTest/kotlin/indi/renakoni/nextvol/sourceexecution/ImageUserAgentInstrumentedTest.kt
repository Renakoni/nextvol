package indi.renakoni.nextvol.sourceexecution

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import hnovel.network.*
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import indi.renakoni.nextvol.defaultplugin.wenku8.WENKU8_USER_AGENT
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageUserAgentInstrumentedTest {
    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    fun supportedAvifIsCachedRetainedAndExportedWithoutDecodingPixelsForDownload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Generated 32x24 AVIF, outside the former hand-written format list.
        val bytes = instrumentation.context.assets.open("images/supported.avif").use { it.readBytes() }
        val originalLoader = SingletonImageLoader.get(context)
        var displayDecodes = 0
        val loader = originalLoader.newBuilder().eventListener(object : coil3.EventListener() {
            override fun decodeEnd(request: ImageRequest, decoder: coil3.decode.Decoder,
                options: coil3.request.Options, result: coil3.decode.DecodeResult?) {
                if (result?.image is coil3.BitmapImage) displayDecodes++
            }
        }).build()
        val root = File(context.cacheDir, "original-avif-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = root }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context,
            indi.renakoni.nextvol.data.local.room.NextVolDatabase::class.java).build()
        val store = indi.renakoni.nextvol.data.download.BookDownloadStore(isolated, db,
            indi.renakoni.nextvol.data.content.ContentJsonDecoder(indi.renakoni.nextvol.data.content.ContentComponentRegistry()))
        SingletonImageLoader.setUnsafe(loader)
        try { MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val source = Wenku8Api { SourceNetworkRoute(SourceNetworkMode.SystemDefault, okhttp3.Dns.SYSTEM) }.use { it.id }
            val book = SourceBookId(source, root.name)
            val image = SourceImage(book, server.url("/original.avif").toString(), preferDownloaded = false)
            val raw = indi.renakoni.nextvol.data.image.originalSourceImage(context, image, false)
            assertArrayEquals(bytes, raw)
            assertEquals(0, displayDecodes)
            assertArrayEquals(bytes, indi.renakoni.nextvol.data.image.originalSourceImage(context, image, false))
            val shown = loader.execute(ImageRequest.Builder(context).data(image).build())
            assertTrue(shown.toString(), shown is SuccessResult)
            assertEquals(32, (shown as SuccessResult).image.width)
            assertEquals(1, displayDecodes)
            val attempt = store.begin(book, 0, "retain")
            store.saveImage(attempt, image.uri, false, raw)
            val retained = store.image(image)!!
            assertArrayEquals(bytes, retained.readBytes())
            val exported = File(root, "export.jpg")
            assertTrue(indi.renakoni.nextvol.data.export.EpubImagePreparer(context, book, listOf(
                indi.renakoni.nextvol.data.export.EpubImagePreparer.Task(exported, android.net.Uri.parse(image.uri),
                    sourceFile = retained))) { _, _ -> }.run())
            assertNotNull(android.graphics.BitmapFactory.decodeFile(exported.path))
            assertEquals(1, server.requestCount)
        } } finally {
            SingletonImageLoader.setUnsafe(originalLoader)
            loader.shutdown(); db.close(); root.deleteRecursively()
        }
    }

    @Test fun directCoilBuiltinProviderAndRuleHttpReachTheImageWithTheirResolvedIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val ua = request.getHeader("User-Agent").orEmpty()
                    if (ua.isBlank() || ua.startsWith("okhttp/")) return MockResponse().setResponseCode(403).setBody("fixture rejects library identity")
                    return MockResponse().setHeader("Content-Type", "image/png").setHeader("Cache-Control", "no-store").setBody(Buffer().write(png))
                }
            }
            server.start()
            val client = OkHttpClient()
            try { client.newCall(Request.Builder().url(server.url("/control")).build()).execute().use { assertEquals(403, it.code) } }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            server.takeRequest()
            val loader = SingletonImageLoader.get(context)
            for (ua in listOf(null, "Source-Explicit-UA/1.0")) {
                val request = ImageRequest.Builder(context).data(server.url("/direct").toString()).size(4, 4)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
                ua?.let { request.httpHeaders(NetworkHeaders.Builder().add("user-agent", it).build()) }
                val result = loader.execute(request.build())
                assertTrue(result.toString(), result is SuccessResult)
                assertEquals(ua ?: DESKTOP_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            }
            // Exercise the production SourceImage -> registered built-in provider -> raw OkHttp path.
            val source = Wenku8Api { SourceNetworkRoute(SourceNetworkMode.SystemDefault, okhttp3.Dns.SYSTEM) }.use { it.id }
            val native = loader.execute(ImageRequest.Builder(context)
                .data(SourceImage(SourceBookId(source, "ua-fixture"), server.url("/native").toString(), cover = true, preferDownloaded = false))
                .size(4, 4).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(native.toString(), native is SuccessResult)
            assertEquals(WENKU8_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            val root = File(context.cacheDir, "rule-image-ua-${System.nanoTime()}")
            try { SourceBroker(root.toPath()).use { broker ->
                val base = server.url("/").toString()
                val session = broker.open(SourceScope("image-ua", root.name, "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT)
                val image = session.execute(BrokerRequest("rule", server.url("/rule").toString(), kind = ResourceKind.Image))
                assertTrue(image.toString(), image is BrokerResult.Success)
                assertArrayEquals(png, (image as BrokerResult.Success).response.body)
                assertEquals(DESKTOP_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            } } finally { root.deleteRecursively() }
        }
    }
}
