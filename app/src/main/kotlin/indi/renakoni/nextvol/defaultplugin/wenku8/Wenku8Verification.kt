package indi.renakoni.nextvol.defaultplugin.wenku8

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import hnovel.content.SourceVerification
import hnovel.content.contentError
import hnovel.network.*
import indi.renakoni.nextvol.data.web.SourceNetworkSettings
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator
import indi.renakoni.nextvol.data.web.rules.VerificationOwner
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.ktor.http.encodeURLParameter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import javax.inject.Inject

/** Bridges the built-in HTTP adapter to the shared broker, browser and verification coordinator. */
class Wenku8Verification @Inject constructor(@ApplicationContext context: Context,
    private val registry: WebSourceRegistry, private val coordinator: SourceVerificationCoordinator,
    private val browser: BrowserExecutor, private val cipher: StorageCipher,
    private val networks: SourceNetworkSettings) : AutoCloseable {
    private class Entry(val broker: SourceBroker, val session: SourceSession)
    private val root = File(context.filesDir, "wenku8-browser").toPath()
    private val sessions = mutableMapOf<VerificationOwner, Entry>()
    private var closed = false

    suspend fun request(source: Identifier, url: String, cookies: Map<String, String>, http: suspend () -> Document): Document {
        val metadata = registry.sources.value.firstOrNull { it.metadata.id == source }?.metadata
            ?: throw SourceContentException(ContentError.Unavailable, "wenku8")
        val owner = VerificationOwner(source, metadata.revision, metadata.accountGeneration)
        var verified: Document? = null
        return coordinator.execute(owner, metadata.item.name) {
            verified?.let { document -> verified = null; return@execute document }
            var entry = synchronized(this) { check(!closed); sessions[owner] }
            if (entry == null) {
                try { return@execute http() }
                catch (failure: SourceContentException) {
                    if (failure.code != ContentError.BrowserRequired) throw failure
                }
                entry = session(owner, cookies)
            }
            val session = entry.session
            // Both legacy domains redirect here. Seed the destination's cookies before its first navigation.
            val target = url.toHttpUrl().let { address ->
                if (address.host in setOf("www.wenku8.cc", "www.wenku8.com"))
                    address.newBuilder().scheme("https").host("www.wenku8.net").port(443).build() else address
            }.toString()
            val request = BrokerRequest("wenku8", target, responseCharset = "GB18030")
            suspend fun read(interactive: Boolean): Document {
                val result = session.execute(request.copy(browser = BrowserOptions(nativeWebsite = true,
                    interactive = interactive, title = metadata.item.name,
                    script = "document.documentElement.outerHTML")))
                return when (result) {
                    is BrokerResult.Success -> wenku8Document(result.response)
                    is BrokerResult.Failure -> {
                        if (result.code == FailureCode.BrowserRequired) throw Wenku8Challenge(
                            result.challenge ?: BrowserChallengeKind.SiteVerification)
                        throw SourceContentException(result.code.contentError(), "wenku8",
                            denial = result.denial, verification = result.certificate?.let { certificate ->
                                SourceVerification(null, certificate) { session.approveCertificate(certificate) }
                            })
                    }
                }
            }
            try { read(false) }
            catch (failure: SourceContentException) {
                if (failure.code != ContentError.BrowserRequired) throw failure
                throw SourceContentException(ContentError.BrowserRequired, "wenku8",
                    verification = SourceVerification((failure as? Wenku8Challenge)?.kind ?: BrowserChallengeKind.SiteVerification,
                        origin = sourceOrigin(target)) { verified = read(true) })
            }
        }
    }

    @Synchronized private fun session(owner: VerificationOwner, seeds: Map<String, String>): Entry {
        check(!closed)
        return sessions.getOrPut(owner) {
            val origins = listOf("cc", "net", "com").flatMap { suffix ->
                listOf("https://www.wenku8.$suffix", "http://www.wenku8.$suffix")
            }
            val broker = SourceBroker(root, cipher = cipher, browser = browser, route = networks.forSource(owner.source))
            try {
                val session = broker.open(SourceScope(owner.source.namespace, owner.source.id, "default", owner.generation),
                    origins.map(::NetworkGrant))
                session.configureSource("https://www.wenku8.net", cookiesEnabled = true, preferBrowserUserAgent = true)
                val names = session.cookie(session.sourceUrl).split(';').map { it.trim().substringBefore('=') }.toSet()
                val missing = seeds.filterKeys { it !in names }
                if (missing.isNotEmpty()) session.setCookie(session.sourceUrl,
                    missing.entries.joinToString("; ") { (name, value) -> "$name=${value.encodeURLParameter()}" })
                Entry(broker, session)
            } catch (failure: Exception) { broker.close(); throw failure }
        }
    }

    @Synchronized override fun close() {
        closed = true
        sessions.values.forEach { it.broker.close() }
        sessions.clear()
    }
}

internal fun wenku8Document(response: BrokerResponse): Document {
    websiteChallenge(response)?.let { throw Wenku8Challenge(it, response.status.takeIf { it > 0 }) }
    if (response.kind != ResponseKind.BrowserDocument && response.status !in 200..299) {
        throw SourceContentException(ContentError.Network, "wenku8", httpStatus = response.status)
    }
    return Jsoup.parse(response.text(), response.finalUrl)
        .outputSettings(Document.OutputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml))
}

internal class Wenku8Challenge(val kind: BrowserChallengeKind, status: Int? = null) :
    SourceContentException(ContentError.BrowserRequired, "wenku8", httpStatus = status)
