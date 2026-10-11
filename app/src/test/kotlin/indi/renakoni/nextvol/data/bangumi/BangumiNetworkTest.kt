package indi.renakoni.nextvol.data.bangumi

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.xbill.DNS.*
import org.xbill.DNS.Record
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BangumiNetworkTest {
    private val server = MockWebServer()
    private val bootstrap = OkHttpClient()
    private val clients = mutableListOf<OkHttpClient>()
    private val queries = mutableListOf<RecordedRequest>()
    private var clock = 0L
    private var ttl = 5L
    private var ipv6 = false
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(queries) { queries += request }
                if (request.requestUrl!!.encodedPath == "/unavailable") return MockResponse().setResponseCode(503)
                val query = Message(request.requestUrl!!.queryParameter("dns")!!.decodeBase64()!!.toByteArray())
                val reply = response(query)
                val record = when (query.question.type) {
                    Type.HTTPS -> HTTPSRecord(query.question.name, DClass.IN, ttl, 1, Name.root,
                        listOf(SVCBBase.ParameterEch(byteArrayOf(1, 2, 3))))
                    Type.A -> ARecord(query.question.name, DClass.IN, ttl, InetAddress.getByName("192.0.2.1"))
                    Type.AAAA -> if (ipv6) AAAARecord(query.question.name, DClass.IN, ttl,
                        InetAddress.getByName("2001:db8::1")) else null
                    else -> null // An IPv4-only answer must still be cached.
                }
                record?.let { reply.addRecord(it, Section.ANSWER) }
                return MockResponse().setHeader("Content-Type", "application/dns-message").setBody(Buffer().write(reply.toWire()))
            }
        }
        server.start()
    }
    @After fun close() {
        (clients + bootstrap).forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
        server.shutdown()
    }
    private fun configQueries() = synchronized(queries) { queries.count { it.requestUrl!!.encodedPath == "/config" } }
    private fun network(client: OkHttpClient = bootstrap, fallback: Boolean = false,
        probe: (Request, Dns) -> Unit = { _, _ -> }) = BangumiNetwork(
        client, if (fallback) listOf(server.url("/unavailable"), server.url("/config")) else listOf(server.url("/config")),
        server.url("/dns"), { ech, dns ->
            assertArrayEquals(byteArrayOf(1, 2, 3), ech)
            OkHttpClient.Builder().dns(dns).addInterceptor { chain ->
                if (chain.request().url.host == BangumiNetwork.API_HOST && chain.request().method == "HEAD") {
                    probe(chain.request(), dns)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(okhttp3.ResponseBody.EMPTY).build()
                } else chain.proceed(chain.request())
            }.build().also { synchronized(clients) { clients += it } }
        }, { clock })

    @Test fun aUsableIpv4RouteDoesNotWaitForAStalledIpv6Query() = runBlocking {
        val normal = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val type = Message(request.requestUrl!!.queryParameter("dns")!!.decodeBase64()!!.toByteArray()).question.type
                return if (type == Type.AAAA) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else normal.dispatch(request)
            }
        }
        val probes = mutableListOf<Request>()
        val network = network(probe = { request, _ -> probes += request })
        val route = withTimeout(2_000) { network.client() }
        assertEquals("192.0.2.1", route.dns.lookup(BangumiNetwork.API_HOST).single().hostAddress)
        assertEquals(1, probes.size)
        assertNull(probes.single().header("Authorization"))
        assertNull(probes.single().header("Cookie"))
    }

    @Test fun anUnreachableIpv6AnswerCannotDefeatAWorkingIpv4Route() = runBlocking {
        ipv6 = true
        val normal = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val type = Message(request.requestUrl!!.queryParameter("dns")!!.decodeBase64()!!.toByteArray()).question.type
                return normal.dispatch(request).apply { if (type == Type.A) setBodyDelay(150, TimeUnit.MILLISECONDS) }
            }
        }
        val network = network(probe = { _, dns ->
            if (dns.lookup(BangumiNetwork.API_HOST).first() is Inet6Address) throw IOException("IPv6 unreachable")
        })
        val route = withTimeout(2_000) { network.client() }
        assertEquals("192.0.2.1", route.dns.lookup(BangumiNetwork.API_HOST).single().hostAddress)
    }

    @Test fun aUsableIpv6RouteDoesNotWaitForAStalledIpv4Query() = runBlocking {
        ipv6 = true
        val normal = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val type = Message(request.requestUrl!!.queryParameter("dns")!!.decodeBase64()!!.toByteArray()).question.type
                return if (type == Type.A) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else normal.dispatch(request)
            }
        }
        val route = withTimeout(2_000) { network().client() }
        assertTrue(route.dns.lookup(BangumiNetwork.API_HOST).single() is Inet6Address)
    }

    @Test fun failedConnectionProbesAreNotCachedAsUsableRoutes() = runBlocking {
        var reachable = false
        val network = network(probe = { _, _ -> if (!reachable) throw IOException("unreachable") })
        try { network.client(); fail() } catch (_: IOException) { }
        reachable = true
        network.client()
        assertEquals(2, configQueries())
    }

    @Test fun anOldFailureDoesNotWaitForAnotherRequestsDnsPreparation() = runBlocking {
        val network = network()
        val first = network.client()
        clock = 5_000
        val started = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }
        val preparing = launch(Dispatchers.Default) { network.client() }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(250) { network.invalidate(first) }
        } finally { preparing.cancelAndJoin() }
    }

    @Test fun ipv4OnlyRouteIsSharedUntilTtlExpiresThenRefreshed() = runBlocking {
        val network = network()
        val first = network.client()
        assertEquals(1, configQueries())
        clock = 4999
        assertSame(first, network.client())
        assertEquals(1, configQueries())
        clock = 5000
        assertNotSame(first, network.client())
        assertEquals(2, configQueries())
    }

    @Test fun parallelCallersShareOnePreparationAndDnsNeverCarriesCredentials() = runBlocking {
        val network = network()
        val results = (1..4).map { async { network.client() } }.awaitAll()
        assertTrue(results.all { it === results.first() })
        assertEquals(1, configQueries())
        synchronized(queries) {
            queries.forEach {
                assertNull(it.getHeader("Authorization"))
                assertNull(it.getHeader("Cookie"))
                assertEquals("application/dns-message", it.getHeader("Accept"))
            }
        }
    }

    @Test fun bootstrapFailureUsesTheNextResolver() = runBlocking {
        network(fallback = true).client()
        assertEquals(1, configQueries())
        synchronized(queries) { assertTrue(queries.any { it.requestUrl!!.encodedPath == "/unavailable" }) }
    }

    @Test fun cancellationCancelsTheActualDohCall() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val canceled = CountDownLatch(1)
        val client = bootstrap.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        val pending = launch(Dispatchers.Default) { network(client).client() }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(1000) { pending.cancelAndJoin() }
        assertTrue(canceled.await(1, TimeUnit.SECONDS))
    }

    @Test fun failedOldRequestCannotInvalidateANewerRoute() = runBlocking {
        val network = network()
        val first = network.client()
        network.invalidate(first)
        val second = network.client()
        network.invalidate(first)
        assertSame(second, network.client())
        assertEquals(2, configQueries())
    }

    @Test fun failedSecureDnsRefreshesTheEchConfiguration() = runBlocking {
        val normal = server.dispatcher
        var unavailable = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (unavailable && request.requestUrl!!.encodedPath == "/dns") MockResponse().setResponseCode(503)
                else normal.dispatch(request)
        }
        val network = network()
        try { network.client(); fail() } catch (_: IOException) { }
        unavailable = false
        network.client()
        assertEquals(2, configQueries())
    }

    @Test fun disconnectDuringPreparationPreventsAuthenticatedRequest() = runBlocking {
        val session = BangumiSession(BangumiUser(17, "test"), "generation", "test-only-token")
        val normal = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                session.revoke()
                return normal.dispatch(request)
            }
        }
        MockWebServer().use { apiServer ->
            apiServer.start()
            val api = BangumiApi(OkHttpClient(), apiServer.url("/"), network())
            try { api.updateVolumes(session, 123, 9); fail() } catch (_: CancellationException) { }
            assertEquals(1, configQueries())
            assertEquals(0, apiServer.requestCount)
        }
    }

    @Test fun preparedTransportPreservesWriteBodyAndKeepsCredentialsOutOfDns() = runBlocking {
        MockWebServer().use { apiServer ->
            apiServer.start()
            apiServer.enqueue(MockResponse().setResponseCode(204))
            val session = BangumiSession(BangumiUser(17, "test"), "generation", "test-only-token")
            val api = BangumiApi(OkHttpClient(), apiServer.url("/"), network())
            try { api.updateVolumes(session, 123, 9) } finally { session.revoke() }
            val request = apiServer.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PATCH", request.method)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("{\"vol_status\":9}", request.body.readUtf8())
            assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
            assertEquals(1, apiServer.requestCount)
            synchronized(queries) { queries.forEach { assertNull(it.getHeader("Authorization")) } }
        }
    }

    @Test fun oversizedAndNonDnsResponsesAreRejected() = runBlocking {
        server.dispatcher = QueueDispatcher()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("not DNS"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message")
            .setBody(Buffer().write(ByteArray(65_536))))
        repeat(2) {
            try { BangumiDoh().query(bootstrap, server.url("/dns"), "api.bgm.tv", Type.A); fail() }
            catch (_: IOException) { }
        }
    }

    @Test fun mismatchedTruncatedAndErrorAnswersAreRejected() {
        val query = Message.newQuery(Record.newRecord(Name.fromString("api.bgm.tv."), Type.A, DClass.IN))
        val invalid = listOf(
            response(query).apply { header.id = query.header.id xor 1 },
            response(query).apply { header.setFlag(Flags.TC.toInt()) },
            response(query).apply { header.rcode = Rcode.SERVFAIL },
            response(query).apply { header.unsetFlag(Flags.QR.toInt()) },
            response(query).apply { removeAllRecords(Section.QUESTION) },
        )
        invalid.forEach {
            try { BangumiDoh.parse(query, it.toWire()); fail("Accepted invalid DNS response") }
            catch (_: IOException) { }
        }
    }

    @Test fun unrelatedAnswerAndAdditionalRecordsCannotSupplyAnAddress() {
        val query = Message.newQuery(Record.newRecord(Name.fromString("api.bgm.tv."), Type.A, DClass.IN))
        val reply = response(query)
        reply.addRecord(ARecord(Name.fromString("other.example."), DClass.IN, 60,
            InetAddress.getByName("192.0.2.1")), Section.ANSWER)
        reply.addRecord(ARecord(query.question.name, DClass.IN, 60,
            InetAddress.getByName("192.0.2.2")), Section.ADDITIONAL)
        assertTrue(BangumiDoh.parse(query, reply.toWire()).addresses().isEmpty())
    }

    @Test fun missingEchConfigurationIsNotTreatedAsOrdinaryTls() {
        try { BangumiDnsAnswer(emptyList()).echConfig(); fail() }
        catch (_: IOException) { }
    }

    private fun response(query: Message) = Message(query.header.id).apply {
        header.setFlag(Flags.QR.toInt())
        addRecord(query.question, Section.QUESTION)
    }
}
