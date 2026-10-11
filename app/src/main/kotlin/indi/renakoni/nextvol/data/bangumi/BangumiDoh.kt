package indi.renakoni.nextvol.data.bangumi

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.xbill.DNS.*
import org.xbill.DNS.Record
import java.io.IOException
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class BangumiDnsAnswer(val records: List<Record>) {
    val ttl: Long get() = records.minOfOrNull { it.ttl.coerceAtMost(300) } ?: 0
    fun echConfig(): ByteArray = records.filterIsInstance<HTTPSRecord>()
        .filter { it.svcPriority > 0 && it.targetName == Name.root }
        .mapNotNull { (it.getSvcParamValue(SVCBBase.ECH) as? SVCBBase.ParameterEch)?.data }
        .firstOrNull { it.isNotEmpty() } ?: throw IOException("ECH configuration unavailable")
    fun addresses(): List<InetAddress> = records.mapNotNull {
        when (it) { is ARecord -> it.address; is AAAARecord -> it.address; else -> null }
    }
}

/** Only DNS wire messages enter these clients; account headers and cookies never do. */
internal class BangumiDoh {
    suspend fun query(client: OkHttpClient, endpoint: HttpUrl, host: String, type: Int): BangumiDnsAnswer {
        val question = Record.newRecord(Name.fromString("$host."), type, DClass.IN)
        val query = Message.newQuery(question)
        val url = endpoint.newBuilder().addQueryParameter("dns", query.toWire().toByteString().base64Url().trimEnd('=')).build()
        val request = Request.Builder().url(url).header("Accept", DNS_TYPE)
            .header("Content-Type", DNS_TYPE).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val answer = response.use {
                            if (!it.isSuccessful || it.body.contentType()?.let { t -> "${t.type}/${t.subtype}" } != DNS_TYPE)
                                throw IOException("Invalid DoH response")
                            val source = it.body.source()
                            if (source.request(65_536)) throw IOException("Oversized DoH response")
                            parse(query, source.readByteArray())
                        }
                        continuation.resume(answer)
                    } catch (failure: Exception) { continuation.resumeWithException(failure) }
                }
            })
        }
    }

    companion object {
        private const val DNS_TYPE = "application/dns-message"
        internal fun parse(query: Message, bytes: ByteArray): BangumiDnsAnswer {
            val response = Message(bytes)
            if (response.header.id != query.header.id || response.question != query.question ||
                response.header.getCount(Section.QUESTION) != 1 || response.header.opcode != Opcode.QUERY ||
                !response.header.getFlag(Flags.QR.toInt()) || response.header.getFlag(Flags.TC.toInt()) || response.rcode != Rcode.NOERROR)
                throw IOException("Invalid DNS answer")
            // These fixed names have direct records. Never accept unrelated answer/additional data.
            return BangumiDnsAnswer(response.getSection(Section.ANSWER).filter {
                it.name == query.question.name && it.type == query.question.type && it.dClass == DClass.IN
            })
        }
    }
}
