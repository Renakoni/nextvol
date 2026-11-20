package hnovel.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CookieDiagnosticTest {
    @get:Rule val directory = TemporaryFolder()
    private val url = "http://example.org/account/page".toHttpUrl()
    private fun jar() = SourceCookies(SourceStorage(directory.root.toPath(), listOf("fixture"), BrokerLimits()))
    private fun cookie(name: String, path: String = "/", domain: String = url.host) =
        Cookie.Builder().name(name).value("private-value").hostOnlyDomain(domain).path(path)

    @Test fun countsDescribeActualSelectionWithoutCookieNamesOrValues() {
        val cookies = jar()
        cookies.restoreMemory(listOf(
            cookie("private-sid").build(), cookie("private-sid", "/account").build(),
            cookie("private-keep").build(), cookie("private-path", "/elsewhere").build(),
            cookie("private-host", domain = "other.test").build(), cookie("private-secure").secure().build(),
            cookie("private-expired").expiresAt(1).build()
        ).map { url.toString() to it })
        var diagnostic: CookieDiagnostic? = null
        val header = cookies.header(url, "private-sid=manual; private-extra=extra") { diagnostic = it }
        assertEquals("private-sid=manual; private-extra=extra; private-keep=private-value", header)
        assertEquals(CookieDiagnostic(CookieStore.HttpJar, selected = 3, explicit = 2, expired = 1,
            unmatched = 3, overridden = 2), diagnostic)
        val encoded = Json.encodeToString(checkNotNull(diagnostic))
        assertFalse(encoded.contains("private-"))
        assertFalse(encoded.contains(url.toString()))
        assertEquals(diagnostic, Json.decodeFromString<CookieDiagnostic>(encoded))
    }

    @Test fun enabledDisabledAndFailingObserversKeepTheSameCookieHeader() {
        val cookies = jar()
        cookies.restoreMemory(listOf(cookie("sid").build(), cookie("sid", "/account").build())
            .map { url.toString() to it })
        val plain = cookies.header(url, null)
        var calls = 0
        var selected: Int? = null
        assertEquals(plain, cookies.header(url, null) { calls++; selected = it.selected })
        assertEquals(plain, cookies.header(url, null) { throw IllegalStateException("diagnostic only") })
        assertEquals(1, calls)
        assertEquals(2, selected)
        assertEquals("sid=private-value; sid=private-value", plain)
    }

}
