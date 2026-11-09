package indi.renakoni.nextvol.sourceexecution

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import hnovel.network.SourceNetworkMode
import hnovel.network.SourceNetworkRoute
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageRetryEvents
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.ui.components.BookCoverImage
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SourceImageRecoveryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()

    @Test fun invalidImagesStayOutOfCacheAndVisibleCoversRecoverOnceWithoutLeavingThePage() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
        }
        bitmap.recycle()
        // A signature alone does not establish a decodable image.
        val broken = png.copyOf(8)
        val healthy = AtomicBoolean(false)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(if (healthy.get()) png else broken))
            }
            server.start()
            val source = Wenku8Api { SourceNetworkRoute(SourceNetworkMode.SystemDefault, okhttp3.Dns.SYSTEM) }.use { it.id }
            val loader = SingletonImageLoader.get(compose.activity)
            val request = ImageRequest.Builder(compose.activity)
                .data(SourceImage(SourceBookId(source, "recovery-${System.nanoTime()}"),
                    server.url("/cover").toString(), cover = true, preferDownloaded = false))
                .size(4, 4).memoryCachePolicy(CachePolicy.DISABLED).build()
            val fallback = AtomicReference<Boolean?>(null)
            compose.setContent { MaterialTheme {
                BookCoverImage(request, "fixture", 72.dp, 108.dp, "Fixture",
                    onFallbackChanged = { fallback.set(it) }, imageLoader = loader)
            } }
            compose.waitUntil(15_000) { fallback.get() == true }
            compose.waitForIdle()
            assertEquals(1, server.requestCount)
            // Invalid bytes must not become a disk hit on the next request.
            val rejected = runBlocking { loader.execute(request) }
            assertTrue(rejected.toString(), rejected is ErrorResult)
            assertEquals(2, server.requestCount)
            compose.runOnIdle { SourceImageRetryEvents.request(Identifier("fixture", "unrelated")) }
            compose.waitForIdle()
            assertEquals(2, server.requestCount)
            healthy.set(true)
            compose.runOnIdle { SourceImageRetryEvents.request(source) }
            compose.waitUntil(15_000) { fallback.get() == false }
            compose.waitForIdle()
            assertEquals(3, server.requestCount)
            val rendered = compose.onNodeWithContentDescription(compose.activity.getString(R.string.cover_description, "Fixture"))
                .captureToImage().asAndroidBitmap()
            assertEquals(Color.BLUE, rendered.getPixel(rendered.width / 2, rendered.height / 2))
            if (InstrumentationRegistry.getArguments().getString("sourceImageScreenshots") == "true") {
                File(compose.activity.getExternalFilesDir(null), "cover-recovery-verified.png").outputStream().use {
                    rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val repaired = runBlocking { loader.execute(request) }
            assertTrue(repaired.toString(), repaired is SuccessResult)
            assertEquals(3, server.requestCount)
            compose.runOnIdle { SourceImageRetryEvents.request(source) }
            compose.waitForIdle()
            assertEquals(3, server.requestCount)
        }
    }
}
