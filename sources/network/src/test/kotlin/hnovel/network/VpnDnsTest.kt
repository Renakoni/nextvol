package hnovel.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class VpnDnsTest {
    private fun address(value: String) = InetAddress.getByName(value)

    @Test fun failedPrimaryResolverFallsBackWithoutReturningTheVpnAlias() {
        MockWebServer().use { server ->
            server.start()
            val primary = server.url("/resolve").newBuilder().host("primary.example").build().toString()
            val fallback = server.url("/resolve").newBuilder().host("fallback.example").build().toString()
            val resolver = PublicDns(linkedMapOf(primary to listOf("127.0.0.1"), fallback to listOf("127.0.0.1")))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"Answer":[{"type":1,"data":"93.184.216.34"}]}"""))
            assertEquals(listOf(address("93.184.216.34")), resolver.lookup("novel.example"))
            assertTrue(server.takeRequest().getHeader("Host")!!.startsWith("primary.example:"))
            assertTrue(server.takeRequest().getHeader("Host")!!.startsWith("fallback.example:"))
        }
    }

    @Test fun fakeIpUsesPublicDestinationWhileOrdinaryDnsStaysOnTheSystem() {
        var lookups = 0
        val public = Dns { assertEquals("novel.example", it); lookups++; listOf(address("93.184.216.34")) }
        for (alias in listOf("198.18.0.8", "198.19.255.254")) {
            val resolver = VpnDns(Dns { listOf(address(alias)) }, public)
            assertEquals(listOf(address("93.184.216.34")), resolver.lookup("novel.example"))
            assertEquals(listOf(address(alias)), resolver.lookup(alias))
        }
        assertEquals(2, lookups)
        for (literal in listOf("93.184.216.34", "192.168.1.1", "127.0.0.1", "::1")) {
            assertEquals(listOf(address(literal)), VpnDns(Dns { listOf(address(literal)) }, public).lookup("novel.example"))
        }
        assertEquals(2, lookups)
    }

    @Test fun publicLookupCannotAuthorizePrivateTargetsOrReplaceDnsFailureWithAnAlias() {
        val url = "https://novel.example/".toHttpUrl()
        for (literal in listOf("127.0.0.1", "169.254.169.254", "198.18.0.8")) {
            val resolver = VpnDns(Dns { listOf(address("198.18.0.8")) }, Dns { listOf(address(literal)) })
            val policy = NetworkPolicy(listOf(NetworkGrant(url.toString())), resolver)
            assertEquals(FailureCode.AddressDenied, assertThrows(BrokerFailure::class.java) { policy.dns(url).lookup(url.host) }.code)
        }
        val resolver = VpnDns(Dns { listOf(address("198.18.0.8")) }, Dns { throw UnknownHostException() })
        assertThrows(UnknownHostException::class.java) { resolver.lookup("novel.example") }
    }
}
