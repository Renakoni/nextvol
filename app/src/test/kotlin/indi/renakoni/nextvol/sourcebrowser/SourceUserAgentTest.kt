package indi.renakoni.nextvol.sourcebrowser

import hnovel.network.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SourceUserAgentTest {
    @Test fun providerEvidenceIsBoundedAndItsFailureDoesNotSuppressUserAgentEvidence() {
        val environment = WebViewEnvironmentDiagnostic("com.google.android.webview", "124.0.6367.219", true, false)
        val settings = mockk<android.webkit.WebSettings>()
        val host = mockk<IBrowserHost>()
        every { settings.userAgentString } returns DESKTOP_USER_AGENT
        every { host.call(any(), any()) } throws IllegalStateException("diagnostic pipe")
        reportSourceUserAgent(host, settings, DESKTOP_USER_AGENT, UserAgentMetadataStatus.Applied) { environment }
        verify(exactly = 1) { host.call("userAgentDiagnostic", match {
            Json.decodeFromString<WebViewUserAgentDiagnostic>(it).environment == environment
        }) }
        reportSourceUserAgent(host, settings, DESKTOP_USER_AGENT, UserAgentMetadataStatus.Applied) { error("private-provider-error") }
        verify(exactly = 1) { host.call("userAgentDiagnostic", match {
            val value = Json.decodeFromString<WebViewUserAgentDiagnostic>(it)
            value.environment == null && value.matchesRequested == true && !it.contains("private-provider-error")
        }) }
        assertThrows(IllegalArgumentException::class.java) { environment.copy(packageName = "https://private.test/token") }
        assertThrows(IllegalArgumentException::class.java) { environment.copy(versionName = "124.0 private-token") }
        assertThrows(IllegalArgumentException::class.java) { environment.copy(packageName = "a." + "x".repeat(160)) }
    }

    @Test fun failedSettingsReadbackOrHostCallbackDoesNotEscapeDiagnosticReporting() {
        val settings = mockk<android.webkit.WebSettings>()
        val host = mockk<IBrowserHost>()
        every { settings.userAgentString } returns "private-custom-agent"
        every { host.call(any(), any()) } throws IllegalStateException("dead diagnostic pipe")
        reportSourceUserAgent(host, settings, "private-custom-agent", UserAgentMetadataStatus.Unsupported)
        verify(exactly = 1) { host.call("userAgentDiagnostic", match { !it.contains("private-custom-agent") }) }
        every { settings.userAgentString } throws IllegalStateException("readback failed")
        reportSourceUserAgent(host, settings, null, UserAgentMetadataStatus.ProviderDefault)
    }

    @Test fun providersRejectingAdvertisedSupportReturnAStatusInsteadOfThrowing() {
        assertEquals(UserAgentMetadataStatus.Rejected, sourceUserAgentMetadataStatus(DESKTOP_USER_AGENT, true) { _, _ ->
            throw UnsupportedOperationException("provider-private-message")
        })
    }

    @Test fun browserDiagnosticMessagesOnlyContainTypedSummaries() {
        val value = WebViewUserAgentDiagnostic(UserAgentMetadataStatus.Applied, UserAgentSummary.from(DESKTOP_USER_AGENT), true)
        val encoded = Json.encodeToString(value)
        assertEquals(value, Json.decodeFromString<WebViewUserAgentDiagnostic>(encoded))
        assertFalse(encoded.contains(DESKTOP_USER_AGENT))
        val observation: RequestObservation? = null
        observation.recordWebViewUserAgent(encoded)
        assertThrows(IllegalArgumentException::class.java) {
            observation.recordWebViewUserAgent(Json.encodeToString(value.copy(userAgent = value.userAgent.copy(majorVersion = 10000))))
        }
        assertThrows(IllegalArgumentException::class.java) { observation.recordWebViewUserAgent("x".repeat(2049)) }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            observation.recordWebViewUserAgent(encoded.dropLast(1) + ",\"rawUa\":\"private\"}")
        }
    }

}
