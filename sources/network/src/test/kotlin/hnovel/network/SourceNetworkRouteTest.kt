package hnovel.network

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class SourceNetworkRouteTest {
    @get:Rule val directory = TemporaryFolder()
    private class Sockets : SocketFactory() {
        val opened = AtomicInteger()
        override fun createSocket(): Socket { opened.incrementAndGet(); return Socket() }
        override fun createSocket(host: String, port: Int): Socket = error("DNS must run through the route")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket overload")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected socket overload")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket overload")
    }
    private fun route(mode: SourceNetworkMode, sockets: SocketFactory = Sockets(), lookups: AtomicInteger = AtomicInteger()) =
        SourceNetworkRoute(mode, Dns { lookups.incrementAndGet(); listOf(InetAddress.getByName("127.0.0.1")) }, sockets)
    private fun MockWebServer.url() = url("/").newBuilder().host("source.test").build().toString()
    private fun SourceBroker.session(url: String) = open(SourceScope("route-test", "source", "legado"), listOf(NetworkGrant(url, true)))
    private fun body(result: BrokerResult): String {
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response.text()
    }

    @Test fun nativeOwnersStopOnceAndDetachedOwnersAreNotNotified() {
        val route = route(SourceNetworkMode.BypassVpn)
        val calls = AtomicInteger()
        val action = { calls.incrementAndGet(); Unit }
        val first = route.onInvalidated(action)
        val second = route.onInvalidated(action)
        first.close(); first.close()
        assertEquals(0, calls.get())
        route.invalidate(); route.invalidate()
        assertEquals(1, calls.get())
        second.close()
        route.onInvalidated(action).close()
        assertEquals(2, calls.get())
    }

    @Test fun oneFailedNativeShutdownCannotKeepOtherOwnersOnARetiredRoute() {
        val route = route(SourceNetworkMode.SystemDefault)
        route.onInvalidated { error("Fixture shutdown failed") }
        var stopped = false
        route.onInvalidated { stopped = true; assertFalse(route.available) }
        route.invalidate()
        assertTrue(stopped)
    }

    @Test fun sameOriginSourcesUseTheirOwnDnsAndSocketsConcurrently() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("wire")) }
            val defaultSockets = Sockets(); val directSockets = Sockets()
            val defaultDns = AtomicInteger(); val directDns = AtomicInteger()
            val default = route(SourceNetworkMode.SystemDefault, defaultSockets, defaultDns)
            val bypass = route(SourceNetworkMode.BypassVpn, directSockets, directDns)
            SourceBroker(directory.newFolder().toPath(), route = SourceRouteProvider { default }).use { a ->
                SourceBroker(directory.newFolder().toPath(), dns = Dns { error("Global DNS must not run") },
                    route = SourceRouteProvider { bypass }).use { b ->
                    val results = listOf(a.session(server.url()), b.session(server.url())).map { session ->
                        async { session.execute(BrokerRequest("parallel", server.url())) }
                    }.awaitAll()
                    assertEquals(listOf("wire", "wire"), results.map(::body))
                    assertEquals(1, defaultSockets.opened.get()); assertEquals(1, directSockets.opened.get())
                    assertEquals(1, defaultDns.get()); assertEquals(1, directDns.get())
                }
            }
        }
    }

    @Test fun modeSwitchKeepsRedirectAndBrowserChildrenOnTheirAdmittedRoute() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val original = route(SourceNetworkMode.SystemDefault)
            val bypassSockets = Sockets()
            val bypass = route(SourceNetworkMode.BypassVpn, bypassSockets)
            var selected = original
            val browser = BrowserExecutor { session, request, _, guard, snapshot ->
                entered.complete(Unit); release.await()
                session.executeHttp(request, guard, snapshot)
            }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/child"))
            server.enqueue(MockResponse().setBody("old chain"))
            server.enqueue(MockResponse().setBody("new request"))
            SourceBroker(directory.root.toPath(), browser = browser, route = SourceRouteProvider { selected }).use { broker ->
                val session = broker.session(server.url())
                session.setCookie(server.url(), "account=fixture")
                val captured = requireNotNull(session.responseRoute())
                assertSame(captured, session.responseRoute())
                selected = bypass
                assertNotSame(captured, session.responseRoute())
                val pending = async { session.executeOnRoute(BrokerRequest("browser", server.url(), browser = BrowserOptions()),
                    RequestCommitGuard { it() }, captured) }
                withTimeout(3000) { entered.await() }
                release.complete(Unit)
                assertEquals("old chain", body(pending.await()))
                assertEquals(0, bypassSockets.opened.get())
                assertEquals("new request", body(session.execute(BrokerRequest("new", server.url()))))
                assertEquals(1, bypassSockets.opened.get())
                assertEquals("account=fixture", session.cookie(server.url()))
                assertEquals(0L, session.scope.accountGeneration)
                repeat(3) { assertEquals("account=fixture", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie")) }
                original.invalidate()
                assertFalse(captured.available)
                assertEquals(FailureCode.RouteUnavailable, (session.executeOnRoute(BrokerRequest("retired", server.url()),
                    RequestCommitGuard { it() }, captured) as BrokerResult.Failure).code)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun missingOrLostRouteNeverFallsBackAndCachedBodiesRemainReadable() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("saved"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = route(SourceNetworkMode.BypassVpn)
            SourceBroker(directory.root.toPath(), route = SourceRouteProvider { transport }).use { broker ->
                val session = broker.session(server.url())
                val request = BrokerRequest("cache", server.url(), cache = CacheMode.ReadThrough)
                assertEquals("saved", body(session.execute(request)))
                server.takeRequest(3, TimeUnit.SECONDS)
                val pending = async { session.execute(request.copy(cache = CacheMode.Disabled)) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                transport.invalidate()
                assertEquals(FailureCode.RouteUnavailable, (withTimeout(3000) { pending.await() } as BrokerResult.Failure).code)
                assertEquals("saved", body(session.execute(request.copy(cache = CacheMode.Only))))
                assertEquals(FailureCode.RouteUnavailable, (session.execute(request.copy(cache = CacheMode.Disabled)) as BrokerResult.Failure).code)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun bypassRetainsAddressValidationAndDoesNotRetryThroughDefaultDns() = runBlocking {
        val sockets = Sockets()
        val bypass = route(SourceNetworkMode.BypassVpn, sockets)
        SourceBroker(directory.root.toPath(), dns = Dns { error("Default resolver must not run") },
            route = SourceRouteProvider { bypass }).use { broker ->
            val session = broker.open(SourceScope("route-test", "source", "legado"), listOf(NetworkGrant("http://source.test/")))
            assertEquals(FailureCode.AddressDenied, (session.execute(BrokerRequest("private", "http://source.test/")) as BrokerResult.Failure).code)
            assertEquals(0, sockets.opened.get())
        }
    }
}
