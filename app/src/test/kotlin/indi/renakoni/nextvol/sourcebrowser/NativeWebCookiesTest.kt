package indi.renakoni.nextvol.sourcebrowser

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NativeWebCookiesTest {
    private val url = "https://www.example.org/account/page"

    @Test fun partitionedCookiesStayExcludedWithOrWithoutDiagnostics() {
        val values = listOf("private=token; Path=/; Secure; SameSite=None; Partitioned",
            "ordinary=secret; HttpOnly; SameSite=Lax", "literal=Partitioned; Path=/")
        val plain = nativeCookieSnapshot(url, values, true)
        val observed = nativeCookieSnapshot(url, values, true, observe = true)
        assertEquals(values.drop(1), plain.cookies)
        assertEquals(plain.cookies, observed.cookies)
        assertNull(plain.partitionedExcluded)
        assertEquals(1, observed.partitionedExcluded)
        assertFalse(Json.encodeToString(plain).contains("partitionedExcluded"))
        assertEquals(observed, Json.decodeFromString<NativeCookieSnapshot>(Json.encodeToString(observed)))
    }

    @Test fun missingMetadataCannotClaimZeroPartitionedCookiesAndSnapshotLimitsStayEnforced() {
        val old = Json.decodeFromString<NativeCookieSnapshot>("""{"url":"$url","cookies":[],"completeMetadata":false}""")
        assertNull(old.partitionedExcluded)
        assertNull(nativeCookieSnapshot(url, listOf("unknown=value"), false, observe = true).partitionedExcluded)
        assertEquals(0, nativeCookieSnapshot(url, emptyList(), true, observe = true).partitionedExcluded)
        assertThrows(IllegalArgumentException::class.java) { nativeCookieSnapshot(url, List(257) { "a=b" }, true) }
        assertThrows(IllegalArgumentException::class.java) { nativeCookieSnapshot(url, listOf("a=" + "x".repeat(65536)), false) }
    }

    @Test fun payloadExactlyAtLimitKeepsOptionalCounts() {
        val snapshots = boundarySnapshots(observe = true, extra = -24).toMutableList()
        val padding = NATIVE_COOKIE_PAYLOAD_LIMIT - Json.encodeToString(snapshots).length
        val first = snapshots.first()
        val values = first.cookies.toMutableList()
        values[0] = values[0].replaceFirst("=", "=" + "a".repeat(padding))
        snapshots[0] = nativeCookieSnapshot(first.url, values, true, observe = true)
        val encoded = Json.encodeToString(snapshots)
        assertEquals(NATIVE_COOKIE_PAYLOAD_LIMIT, encoded.length)
        assertEquals(encoded, nativeCookieSnapshotPayload(snapshots))
        assertTrue(encoded.contains("partitionedExcluded"))
    }

    @Test fun oversizedCookieDataIsNotTruncatedToFitThePayloadLimit() {
        val plain = boundarySnapshots(observe = false, extra = 32)
        val original = Json.encodeToString(plain)
        assertTrue(original.length > NATIVE_COOKIE_PAYLOAD_LIMIT)
        assertEquals(original, nativeCookieSnapshotPayload(plain))
        assertEquals(original, nativeCookieSnapshotPayload(boundarySnapshots(observe = true, extra = 32)))
    }

    @Test fun explicitValuesPreserveBrowserAttributesAndNewCookiesStayHostOnly() {
        val snapshot = NativeCookieSnapshot(url, listOf(
            "sid=old; Domain=example.org; Path=/account; Secure; HttpOnly; SameSite=Strict; Expires=Wed, 21 Oct 2037 07:28:00 GMT",
            "host=old; Path=/; SameSite=Lax",
            "untouched=keep; Path=/; HttpOnly"
        ), true)
        val updates = nativeWebCookieUpdates(url, "sid=new=token; host=new; extra=one", snapshot)
        assertEquals(listOf(snapshot.cookies[0].replace("sid=old", "sid=new=token"),
            snapshot.cookies[1].replace("host=old", "host=new"), "extra=one; path=/"), updates)
        assertFalse(updates.last().contains("domain", true))
        assertFalse(updates.any { it.startsWith("untouched=") })
        assertTrue(nativeWebCookieUpdates(url, "sid=old", snapshot).isEmpty())
    }

    @Test fun incompleteMetadataCannotBeUsedToOverwriteBrowserAttributes() {
        val snapshot = NativeCookieSnapshot(url, listOf("sid=old; path=/; httponly"), false)
        assertTrue(nativeWebCookieUpdates(url, "sid=old", snapshot).isEmpty())
        assertEquals(listOf("new=value; path=/"), nativeWebCookieUpdates(url, "new=value", snapshot))
        assertThrows(IllegalStateException::class.java) { nativeWebCookieUpdates(url, "sid=changed", snapshot) }
    }

    @Test fun malformedOrOversizedHeadersFailBeforeProducingAnyUpdates() {
        val snapshot = NativeCookieSnapshot(url, emptyList(), true)
        for (header in listOf("good=one; broken", "bad name=value", "a=b\r\nInjected: bad", "a=" + "x".repeat(65536),
            (0..256).joinToString(";") { "k$it=value" })) {
            assertTrue(header.take(20), runCatching { nativeWebCookieUpdates(url, header, snapshot) }.isFailure)
        }
        assertTrue(nativeWebCookieUpdates(url, "", snapshot).isEmpty())
    }

    private fun boundarySnapshots(observe: Boolean, extra: Int = 0): List<NativeCookieSnapshot> {
        val suffix = "; Path=/; Secure; SameSite=Lax"
        val values = (1..16).map {
            val prefix = "k$it="
            prefix + "a".repeat(3900 - prefix.length - suffix.length) + suffix
        } + ("last=" + "a".repeat(2974 + extra) + suffix)
        return listOf("https://one.example/", "https://one.example/page", "https://two.example/", "https://two.example/page")
            .map { nativeCookieSnapshot(it, values, true, observe) }
    }
}
