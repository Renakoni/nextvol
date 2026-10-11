package indi.renakoni.nextvol.data.bangumi

import android.net.http.X509TrustManagerExtensions
import androidx.annotation.Keep
import org.conscrypt.Conscrypt
import org.conscrypt.ConscryptNetworkSecurityPolicy
import org.conscrypt.DomainEncryptionMode
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Private provider: other clients and the platform's trust configuration are not replaced. */
internal class BangumiEchTls {
    private val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(null as KeyStore?)
    }.trustManagers.filterIsInstance<X509TrustManager>().single()
    val trustManager = EchTrustManager(trust)
    private val context = SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
        init(null, arrayOf(trustManager), null)
    }

    fun socketFactory(config: ByteArray): SSLSocketFactory = object : SSLSocketFactory() {
        private val delegate = context.socketFactory
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            if (host !in HOSTS) throw SSLException("Unsupported Bangumi TLS host")
            val tls = delegate.createSocket(socket, host, port, autoClose) as SSLSocket
            try {
                Conscrypt.setEchConfigList(tls, config)
                return tls
            } catch (failure: Exception) {
                tls.close()
                throw failure
            }
        }
        // OkHttp connects its cancellable raw socket before layering TLS over it.
        override fun createSocket(): Socket = throw SSLException("Layered TLS socket required")
        override fun createSocket(host: String, port: Int): Socket = createSocket()
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
    }

    // Conscrypt 2.7 discovers the policy reflectively. Its engine-socket adapter strips this
    // method from X509ExtendedTrustManager wrappers, so keep the host-aware Android bridge.
    @Keep
    class EchTrustManager(private val delegate: X509TrustManager) : X509TrustManager {
        private val androidTrust = X509TrustManagerExtensions(delegate)
        private val policy = object : ConscryptNetworkSecurityPolicy() {
            override fun getDomainEncryptionMode(hostname: String?): DomainEncryptionMode =
                if (hostname in HOSTS) DomainEncryptionMode.REQUIRED else DomainEncryptionMode.DISABLED
        }
        fun getNetworkSecurityPolicy(): ConscryptNetworkSecurityPolicy = policy
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkServerTrusted(chain, authType)
        fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> =
            androidTrust.checkServerTrusted(chain, authType, host)
    }

    companion object {
        private val HOSTS = setOf("api.bgm.tv", "cloudflare-dns.com")
    }
}
