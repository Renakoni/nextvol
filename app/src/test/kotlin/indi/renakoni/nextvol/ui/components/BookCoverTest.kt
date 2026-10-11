package indi.renakoni.nextvol.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageRetryEvents
import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BookCoverTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun failedRemoteCoverCanRecoverOnTheSameAddressWithoutCachingItsSubstitute() {
        val calls = AtomicInteger()
        val error = IOException("fixture")
        val loader = ImageLoader.Builder(activity.get()).components {
            add(Interceptor { chain ->
                if (calls.incrementAndGet() == 1) ErrorResult(null, chain.request, error)
                else SuccessResult(createBitmap(60, 87).asImage(), chain.request, DataSource.MEMORY)
            })
        }.build()
        val source = Identifier("fixture", "cover")
        val request = ImageRequest.Builder(activity.get())
            .data(SourceImage(SourceBookId(source, "fixture"), "https://fixture.invalid/cover", cover = true))
            .size(60, 87).build()
        var fallback: Boolean? = null
        activity.get().setContent { MaterialTheme {
            BookCoverImage(request, "fixture", 72.dp, 108.dp, "A book", "An author",
                onFallbackChanged = { fallback = it }, imageLoader = loader)
        } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { fallback == true }
        compose.onAllNodesWithContentDescription("Cover of A book").onFirst().assertIsDisplayed()
        compose.runOnIdle { SourceImageRetryEvents.request(Identifier("fixture", "unrelated")) }
        compose.waitForIdle()
        assertEquals(1, calls.get())
        compose.runOnIdle { fallback = null; SourceImageRetryEvents.request(source) }
        compose.onAllNodesWithContentDescription("Cover of A book").onFirst().assertExists()
        compose.waitUntil(10_000) { fallback == false }
        assertEquals(2, calls.get())
        compose.runOnIdle { SourceImageRetryEvents.request(source) }
        compose.waitForIdle()
        assertEquals(2, calls.get())
        loader.shutdown()
    }

    @Test fun persistentFailureRetriesOnlyOncePerExplicitAction() {
        val calls = AtomicInteger()
        val failures = AtomicInteger()
        val loader = ImageLoader.Builder(activity.get()).components {
            add(Interceptor { chain ->
                calls.incrementAndGet()
                ErrorResult(null, chain.request, IOException("fixture"))
            })
        }.build()
        val source = Identifier("fixture", "persistent")
        val request = ImageRequest.Builder(activity.get())
            .data(SourceImage(SourceBookId(source, "fixture"), "https://fixture.invalid/cover", cover = true))
            .size(60, 87).build()
        activity.get().setContent { MaterialTheme {
            BookCoverImage(request, "fixture", 72.dp, 108.dp, "A book",
                onFallbackChanged = { if (it) failures.incrementAndGet() }, imageLoader = loader)
        } }
        compose.waitUntil(10_000) { failures.get() == 1 }
        for (expected in 2..3) {
            compose.waitForIdle()
            compose.runOnIdle { SourceImageRetryEvents.request(source) }
            compose.waitUntil(10_000) { failures.get() == expected }
            compose.waitForIdle()
            assertEquals(expected, calls.get())
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(3, calls.get())
        loader.shutdown()
    }
}
