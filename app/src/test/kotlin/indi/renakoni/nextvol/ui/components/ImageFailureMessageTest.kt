package indi.renakoni.nextvol.ui.components

import android.app.Application
import indi.renakoni.nextvol.data.image.SourceImageHttpException
import indi.renakoni.nextvol.data.image.SourceImageRequestException
import io.nightfish.lightnovelreader.api.error.WebRequestError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
class ImageFailureMessageTest {
    @Test fun imageFailuresShowTheHttpReasonWithoutPrivateSourceText() {
        val context = RuntimeEnvironment.getApplication()
        val failure = SourceImageRequestException(WebRequestError("private-url", "private-token", SourceImageHttpException(404)))
        assertEquals("The image server returned HTTP 404.", imageFailureMessage(context, failure))
        assertEquals("The response could not be decoded as an image.", imageFailureMessage(context, IllegalStateException("private-url")))
    }
}
