package hnovel.network

import okio.Buffer
import okio.Socket
import okio.buffer
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom

/** Host-only exchange messages; no socket or WebSocket operation is exposed to scripts. */
sealed interface BrokerWebSocketMessage {
    class Text(val text: String) : BrokerWebSocketMessage
    class Binary(val bytes: ByteArray) : BrokerWebSocketMessage
}

/** Stable transport facts only, without response text, request URLs or credentials. */
class BrokerWebSocketException(val failure: BrokerResult.Failure, val statusCode: Int? = null) :
    IOException(failure.code.name)

/** One bounded exchange, used synchronously inside SourceSession.webSocket's background callback. */
class BrokerWebSocket internal constructor(socket: Socket, private val maxRequestBytes: Int, private val maxResponseBytes: Int) {
    private val source = socket.source.buffer()
    private val sink = socket.sink.buffer()
    private val random = SecureRandom()
    private var sent = 0L
    private var received = 0L

    fun sendText(text: String) = transport { send(1, text.toByteArray(Charsets.UTF_8)) }

    fun sendBinary(bytes: ByteArray) = transport { send(2, bytes.copyOf()) }

    fun readMessage(): BrokerWebSocketMessage = transport {
        val message = Buffer()
        var messageOpcode = -1
        while (true) {
            val first = source.readByte().toInt() and 255
            val second = source.readByte().toInt() and 255
            val final = first and 128 != 0
            val opcode = first and 15
            if (first and 112 != 0 || second and 128 != 0) invalidFrame()
            val length = when (val short = second and 127) {
                126 -> source.readShort().toLong() and 65535
                127 -> source.readLong().also { if (it < 0) invalidFrame() }
                else -> short.toLong()
            }
            val control = opcode and 8 != 0
            if (control && (!final || length > 125)) invalidFrame()
            // Check before reading/allocating, including continuation and control payloads.
            if (length > maxResponseBytes - received)
                throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
            received += length
            if (control) {
                val payload = source.readByteArray(length)
                when (opcode) {
                    8 -> { send(8, payload); throw EOFException("WebSocket closed") }
                    9 -> send(10, payload)
                    10 -> Unit
                    else -> invalidFrame()
                }
                continue
            }
            when (opcode) {
                0 -> if (messageOpcode == -1) invalidFrame()
                1, 2 -> { if (messageOpcode != -1) invalidFrame(); messageOpcode = opcode }
                else -> invalidFrame()
            }
            source.readFully(message, length)
            if (final) return@transport if (messageOpcode == 1) BrokerWebSocketMessage.Text(message.readUtf8())
                else BrokerWebSocketMessage.Binary(message.readByteArray())
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable frame state")
    }

    private fun send(opcode: Int, bytes: ByteArray) {
        if (bytes.size > maxRequestBytes - sent) throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
        sent += bytes.size
        sink.writeByte(128 or opcode)
        when {
            bytes.size < 126 -> sink.writeByte(128 or bytes.size)
            bytes.size <= 65535 -> { sink.writeByte(128 or 126); sink.writeShort(bytes.size) }
            else -> { sink.writeByte(128 or 127); sink.writeLong(bytes.size.toLong()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        sink.write(mask)
        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
        sink.write(bytes).flush()
    }

    private fun invalidFrame(): Nothing = throw BrokerFailure(RequestStage.Response, FailureCode.Network)

    private inline fun <T> transport(action: () -> T): T = try { action() }
    catch (failure: BrokerFailure) { throw BrokerWebSocketException(BrokerResult.Failure(failure.stage, failure.code)) }
    catch (_: InterruptedIOException) { throw BrokerWebSocketException(BrokerResult.Failure(RequestStage.Response, FailureCode.Timeout)) }
    catch (_: IOException) { throw BrokerWebSocketException(BrokerResult.Failure(RequestStage.Response, FailureCode.Network)) }
}
