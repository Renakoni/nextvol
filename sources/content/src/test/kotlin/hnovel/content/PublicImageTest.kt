package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import javax.net.SocketFactory

class PublicImageTest {
    @Test fun hostImagesFollowPublicCdnRedirectsWithoutSourceCredentialsOrScriptPermission() = runBlocking {
        var port = 0
        val publicAddress = InetAddress.getByName("93.184.216.34")
        // Route a simulated public peer to the fixture without granting that origin.
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) =
                    super.connect(InetSocketAddress("127.0.0.1", port), timeout)
                override fun getInetAddress(): InetAddress = publicAddress
            }
            override fun createSocket(host: String, port: Int): Socket = error("Unchecked DNS")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unchecked DNS")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected socket")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket")
        }
        val route = SourceNetworkRoute(SourceNetworkMode.SystemDefault, Dns { listOf(publicAddress) }, sockets)
        RuleSourceFixture(route = SourceRouteProvider { route }).use { fixture ->
            port = fixture.server.port
            fixture.server.dispatcher = okhttp3.mockwebserver.QueueDispatcher()
            fixture.source(customize = { raw -> JsonObject(raw + ("header" to JsonPrimitive(buildJsonObject {
                put("Authorization", "private-token"); put("Cookie", "account=private"); put("X-Secret", "private")
                put("Referer", fixture.server.url("/").toString())
            }.toString()))) }).use { source ->
                val image = "http://cdn.test:$port/image"
                for (cover in listOf(false, true)) {
                    fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://second-cdn.test:$port/image"))
                    fixture.server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
                    assertArrayEquals(byteArrayOf(3, 2, 1), source.image("/book", image, cover))
                    repeat(2) {
                        val request = fixture.server.takeRequest()
                        assertNull(request.getHeader("Authorization"))
                        assertNull(request.getHeader("Cookie"))
                        assertNull(request.getHeader("X-Secret"))
                    }
                }
                val definition = source.definition
                val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                    listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                assertTrue(session.deniedOrigins.isEmpty())
                assertEquals(FailureCode.OriginDenied, (session.execute(BrokerRequest("script", image,
                    kind = ResourceKind.Image)) as BrokerResult.Failure).code)
                val privateFailure = runCatching { source.image("/book", "http://192.168.1.1/image", false) }.exceptionOrNull()
                assertEquals(ContentError.AddressDenied, (privateFailure as SourceContentException).code)
            }
        }
    }
}
