package indi.renakoni.nextvol.sourcebrowser

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import hnovel.network.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLooper.idleMainLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class SourceBrowserBackTest {
    @Before fun enableSystemBack() {
        if (Build.VERSION.SDK_INT >= 33) ShadowApplication.setEnableOnBackInvokedCallback(true)
    }

    @After fun clearActiveBrowsers() {
        SourceBrowserService.active = null
        NativeSourceBrowserService.active = null
        unmockkObject(BrowserWire)
    }

    @Test fun nativeBackNavigatesHistoryBeforeCancelling() {
        val service = nativeService()
        val view = service.webView!!
        shadowOf(view).setCanGoBack(true)
        val controller = nativeActivity().setup()
        back(controller.get())
        assertEquals(1, shadowOf(view).goBackInvocations)
        assertFalse(controller.get().isFinishing)
        verify(exactly = 0) { service.cancel("native-job"); service.confirm("native-job") }
        shadowOf(view).setCanGoBack(false)
        back(controller.get())
        verify(exactly = 1) { service.cancel("native-job") }
        controller.pause().stop().destroy()
    }

    @Test fun loginBackNavigatesHistoryThenCancelsOnceEvenAfterDestruction() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results)
        val view = service.webView!!
        shadowOf(view).setCanGoBack(true)
        val controller = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        back(controller.get())
        assertEquals(1, shadowOf(view).goBackInvocations)
        assertNull(shadowOf(view).lastEvaluatedJavascript)
        assertTrue(results.isEmpty())
        assertFalse(controller.get().isFinishing)
        shadowOf(view).setCanGoBack(false)
        back(controller.get())
        assertNull(shadowOf(view).lastEvaluatedJavascript)
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
        service.fail()
        idleMainLooper()
        assertEquals(1, results.size)
        assertEquals(FailureCode.BrowserRequired, (results.single() as BrokerResult.Failure).code)
    }

    @Test fun verificationBackCancelsEvenWithHistoryAndCompletesOnce() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results, verification = true)
        val view = service.webView!!
        shadowOf(view).setCanGoBack(true)
        val controller = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        back(controller.get())
        controller.pause().stop().destroy()
        service.fail()
        idleMainLooper()
        assertEquals(0, shadowOf(view).goBackInvocations)
        assertNull(shadowOf(view).lastEvaluatedJavascript)
        assertEquals(1, results.size)
        assertEquals(FailureCode.BrowserRequired, (results.single() as BrokerResult.Failure).code)
    }

    @Test fun recreationKeepsLoginPendingAndReattachesItsWebView() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results)
        val controller = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        controller.recreate()
        assertSame(controller.get(), service.activity)
        assertNotNull(service.webView!!.parent)
        assertTrue(results.isEmpty())
        shadowOf(service.webView!!).setCanGoBack(true)
        back(controller.get())
        assertEquals(1, shadowOf(service.webView!!).goBackInvocations)
        assertTrue(results.isEmpty())
        controller.get().finish()
        controller.pause().stop().destroy()
        idleMainLooper()
        assertEquals(1, results.size)
        assertTrue(results.single() is BrokerResult.Failure)
    }

    @Test fun obsoleteLoginWindowCannotCancelTheCurrentWindow() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results)
        val old = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        old.pause().stop()
        val current = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        old.get().finish()
        old.destroy()
        idleMainLooper()
        assertSame(current.get(), service.activity)
        assertFalse(current.get().isFinishing)
        assertTrue(results.isEmpty())
        current.get().finish()
        current.pause().stop().destroy()
        idleMainLooper()
        assertEquals(1, results.size)
    }

    @Test fun nativeRecreationDoesNotCancelAndFinishingReleasesItsSession() {
        val service = nativeService()
        val controller = nativeActivity().setup()
        controller.recreate()
        assertSame(controller.get(), service.activity)
        verify(exactly = 0) { service.cancel("native-job") }
        shadowOf(service.webView!!).setCanGoBack(true)
        back(controller.get())
        assertEquals(1, shadowOf(service.webView!!).goBackInvocations)
        verify(exactly = 0) { service.cancel("native-job") }
        controller.get().finish()
        controller.pause().stop().destroy()
        assertNull(service.activity)
        verify(exactly = 1) { service.cancel("native-job") }
    }

    @Test fun aLateNativeWindowCannotAttachToAnotherJob() {
        val service = nativeService()
        val old = nativeActivity("retired-job").setup()
        assertTrue(old.get().isFinishing)
        assertNull(service.activity)
        old.pause().stop().destroy()
        verify(exactly = 0) { service.cancel(any()); service.confirm(any()) }
    }

    @Test @Config(sdk = [35]) fun unregisterRestoresThePreviousCallbackWhileTheWindowIsAlive() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val previous = systemCallback(activity)
        var calls = 0
        val back = SourceBrowserBack(activity) { calls++ }
        back.register()
        assertNotSame(previous, systemCallback(activity))
        systemCallback(activity)!!.onBackInvoked()
        assertEquals(1, calls)
        back.unregister()
        assertSame(previous, systemCallback(activity))
        back.register()
        systemCallback(activity)!!.onBackInvoked()
        assertEquals(2, calls)
        back.unregister()
        controller.pause().stop().destroy()
    }

    private fun systemCallback(activity: Activity): OnBackInvokedCallback? =
        ReflectionHelpers.callInstanceMethod(
            checkNotNull(activity.window.decorView.findOnBackInvokedDispatcher()), "getTopCallback")

    private fun back(activity: Activity) {
        idleMainLooper()
        if (Build.VERSION.SDK_INT >= 33) {
            // Read the window's registered callback so missing registration cannot pass via the legacy override.
            checkNotNull(systemCallback(activity)).onBackInvoked()
        } else {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        }
        idleMainLooper()
    }

    private fun nativeService(): NativeSourceBrowserService {
        val service = mockk<NativeSourceBrowserService>(relaxed = true)
        val view = WebView(RuntimeEnvironment.getApplication())
        var owner: NativeSourceBrowserActivity? = null
        every { service.webView } returns view
        every { service.title } returns "Website"
        every { service.interactiveJobId } returns "native-job"
        every { service.activity } answers { owner }
        every { service.activity = any() } answers { owner = firstArg() }
        NativeSourceBrowserService.active = service
        return service
    }

    private fun nativeActivity(jobId: String = "native-job") = Robolectric.buildActivity(NativeSourceBrowserActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), NativeSourceBrowserActivity::class.java).putExtra("jobId", jobId))

    private fun sourceService(results: MutableList<BrokerResult>, verification: Boolean = false): SourceBrowserService {
        // Attach the real service without starting its disposable Chromium process.
        val service = Robolectric.buildService(SourceBrowserService::class.java).get()
        val request = BrokerRequest("login", "https://source.invalid/")
        ReflectionHelpers.setField(service, "job", BrowserJob(request, BrowserOptions(interactive = true, verificationCode = verification)))
        ReflectionHelpers.setField(service, "mainUrl", request.url)
        ReflectionHelpers.setField(service, "mainResponse", BrokerResponse(200, request.url, emptyMap(), byteArrayOf(), "UTF-8", 0))
        // Robolectric's pipe is not a blocking OS pipe; Binder transport is covered on device.
        mockkObject(BrowserWire)
        val output = mockk<ParcelFileDescriptor>(relaxed = true)
        var payload = ""
        every { BrowserWire.pipe(any(), any()) } answers { payload = firstArg(); output }
        val host = mockk<IBrowserHost>(relaxed = true)
        every { host.complete(output) } answers {
            results += Json.decodeFromString<BrokerResult>(payload)
        }
        ReflectionHelpers.setField(service, "host", host)
        service.webView = WebView(service)
        SourceBrowserService.active = service
        return service
    }
}
