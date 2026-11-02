package hnovel.network

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class BrokerWebSocketTest {
    @get:Rule val directory = TemporaryFolder()
    private fun request(url: String, timeout: Long = 3000) = BrokerRequest("socket", url,
        kind = ResourceKind.Api, timeoutMillis = timeout, followRedirects = false)
    private fun SourceBroker.session(url: String, allowPrivate: Boolean = true) =
        open(SourceScope("socket-test", "voice", "speech"), listOf(NetworkGrant(checkNotNull(sourceOrigin(url)), allowPrivate)))
    private suspend fun failure(action: suspend () -> Unit): BrokerWebSocketException {
        try { action() } catch (failure: BrokerWebSocketException) { return failure }
        error("Expected a transport failure")
    }

    @Test fun ordinaryCallUpgradeExchangesMaskedTextAndBinaryWithProtocolCallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text == "speak") {
                        webSocket.send(byteArrayOf(1, 2, 3).toByteString())
                        webSocket.send("done")
                    }
                }
            }))
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val url = server.url("/edge").toString()
                val result = broker.session(url).webSocket(request(url)) { socket ->
                    socket.sendText("speak")
                    val audio = (socket.readMessage() as BrokerWebSocketMessage.Binary).bytes
                    assertEquals("done", (socket.readMessage() as BrokerWebSocketMessage.Text).text)
                    audio
                }
                assertArrayEquals(byteArrayOf(1, 2, 3), result)
                assertEquals("websocket", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Upgrade"))
            }
        }
    }

    @Test fun authorizationAndActualPeerChecksBothPrecedeTheHandshake() = runBlocking {
        SourceBroker(directory.newFolder().toPath(), Dns { error("Unauthorized request must not resolve") }).use { broker ->
            val session = broker.open(SourceScope("socket-test", "denied", "speech"), emptyList())
            assertEquals(FailureCode.OriginDenied, failure { session.webSocket(request("https://source.test/")) {} }.failure.code)
        }
        SourceBroker(directory.newFolder().toPath(), Dns { listOf(InetAddress.getByName("127.0.0.1")) }).use { broker ->
            val url = "https://source.test/"
            assertEquals(FailureCode.AddressDenied, failure { broker.session(url, false).webSocket(request(url)) {} }.failure.code)
        }
        MockWebServer().use { server ->
            server.start()
            val sockets = object : SocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) =
                        super.connect(InetSocketAddress("127.0.0.1", server.port), timeout)
                }
                override fun createSocket(host: String, port: Int): Socket = error("Unchecked DNS")
                override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unchecked DNS")
                override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected socket")
                override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket")
            }
            val route = SourceNetworkRoute(SourceNetworkMode.BypassVpn,
                Dns { listOf(InetAddress.getByName("93.184.216.34")) }, sockets)
            SourceBroker(directory.newFolder().toPath(), route = SourceRouteProvider { route }).use { broker ->
                val url = "http://source.test:${server.port}/"
                assertEquals(FailureCode.AddressDenied, failure { broker.session(url, false).webSocket(request(url)) {} }.failure.code)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun rejectedHandshakeDoesNotReadOrExposeItsBodyOrRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("private-service-detail"))
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val url = server.url("/").toString()
                val error = failure { broker.session(url).webSocket(request(url)) { error("Must not enter protocol callback") } }
                assertEquals(503, error.statusCode)
                assertFalse(error.toString().contains("private-service-detail"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun protocolExceptionsAreNotReclassifiedAsTransportErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val url = server.url("/").toString()
                val expected = IllegalArgumentException("Protocol failure")
                val actual = runCatching { broker.session(url).webSocket(request(url)) { throw expected } }.exceptionOrNull()
                assertSame(expected, actual)
            }
        }
    }

    @Test fun callerCancellationClosesTheUpgradedConnection() = runBlocking {
        MockWebServer().use { server ->
            val entered = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { entered.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.complete(Unit) }
            }))
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val url = server.url("/").toString()
                val work = launch { broker.session(url).webSocket(request(url)) { it.readMessage() } }
                withTimeout(3000) { entered.await() }
                work.cancelAndJoin()
                withTimeout(3000) { disconnected.await() }
            }
        }
    }

    @Test fun totalTimeoutStillAppliesAfterSuccessfulUpgrade() = runBlocking {
        MockWebServer().use { server ->
            val entered = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { entered.complete(Unit) }
            }))
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val url = server.url("/").toString()
                val error = failure { broker.session(url).webSocket(request(url, 1000)) { it.readMessage() } }
                assertTrue(entered.isCompleted)
                assertEquals(FailureCode.Timeout, error.failure.code)
            }
        }
    }

    @Test fun routeRetirementInterruptsReadsAndDoesNotPublishAnOldResult() = runBlocking {
        MockWebServer().use { server ->
            val entered = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { entered.complete(Unit) }
            }))
            server.start()
            val route = SourceNetworkRoute.systemDefault()
            SourceBroker(directory.root.toPath(), route = SourceRouteProvider { route }).use { broker ->
                val url = server.url("/").toString()
                val work = async { failure { broker.session(url).webSocket(request(url)) { it.readMessage() } } }
                withTimeout(3000) { entered.await() }
                route.invalidate()
                assertEquals(FailureCode.RouteUnavailable, withTimeout(3000) { work.await() }.failure.code)
            }
        }
    }

    private fun frames(input: Buffer, output: Buffer = Buffer(), limit: Int = 32) = BrokerWebSocket(object : okio.Socket {
        override val source = input
        override val sink = output
        override fun cancel() = Unit
    }, 1024, limit)

    @Test fun fragmentedMessagesAllowInterleavedPingAndProduceAMaskedPong() {
        val input = Buffer().writeByte(2).writeByte(2).write(byteArrayOf(1, 2))
            .writeByte(137).writeByte(1).writeByte(9)
            .writeByte(128).writeByte(1).writeByte(3)
        val output = Buffer()
        assertArrayEquals(byteArrayOf(1, 2, 3), (frames(input, output).readMessage() as BrokerWebSocketMessage.Binary).bytes)
        assertEquals(138, output.readByte().toInt() and 255)
        assertEquals(129, output.readByte().toInt() and 255)
        val mask = output.readByteArray(4)
        assertEquals(9, (output.readByte().toInt() xor mask[0].toInt()) and 255)
    }

    @Test fun oversizedFrameIsRejectedFromItsHeaderBeforeReadingThePayload() {
        val socket = frames(Buffer().writeByte(130).writeByte(127).writeLong(Int.MAX_VALUE.toLong()))
        val failure = assertThrows(BrokerWebSocketException::class.java) { socket.readMessage() }
        assertEquals(FailureCode.ResponseTooLarge, failure.failure.code)
    }

    @Test fun totalReceiveBudgetIncludesEarlierMessages() {
        val socket = frames(Buffer().writeByte(130).writeByte(2).write(byteArrayOf(1, 2))
            .writeByte(130).writeByte(2).write(byteArrayOf(3, 4)), limit = 3)
        assertArrayEquals(byteArrayOf(1, 2), (socket.readMessage() as BrokerWebSocketMessage.Binary).bytes)
        val failure = assertThrows(BrokerWebSocketException::class.java) { socket.readMessage() }
        assertEquals(FailureCode.ResponseTooLarge, failure.failure.code)
    }
}
