package hnovel.network

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HttpRetryPolicyTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
    private val policy = HttpRetryPolicy({ now }, { it })
    private fun response(header: String, status: Int = 503) = BrokerResponse(status, "https://example.test/",
        mapOf("retry-after" to listOf(header)), byteArrayOf(), "UTF-8", 0)

    @Test fun malformedHeadersBackOffButLongOrOverflowingDeadlinesDoNotRetryEarly() {
        for (header in listOf("", "later", "-1", "1.5")) assertEquals(header, 500L, policy.delayMillis(0, response(header)))
        for (header in listOf("6", Long.MAX_VALUE.toString(), "9999999999999999999999999",
            "Tue, 29 Sep 2026 00:00:00 GMT")) assertNull(header, policy.delayMillis(0, response(header)))
    }

    @Test fun duplicateHeadersCannotShortenACooldown() {
        val repeated = response("0").copy(headers = mapOf("Retry-After" to listOf("0", "3", "invalid")))
        assertEquals(3000L, policy.delayMillis(0, repeated))
        assertNull(policy.delayMillis(0, repeated.copy(headers = mapOf("Retry-After" to listOf("0", "60")))))
    }

}
