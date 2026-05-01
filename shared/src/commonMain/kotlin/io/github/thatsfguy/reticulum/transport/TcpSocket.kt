package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic blocking-style TCP socket used by [TcpInterface]
 * to talk to an rnsd TCPServerInterface (e.g. RNS.MichMesh.net:7822).
 *
 * Why expect/actual instead of Ktor? The rnsd TCP path is plain raw
 * TCP with no protocol negotiation — every byte read is HDLC-framed
 * Reticulum. We just need read/write/close, and JDK Socket on Android
 * plus NSStream on iOS deliver that with zero added dependencies.
 *
 * [incoming] is a cold flow that begins reading from the socket on
 * first collection and emits whatever raw chunks the kernel hands it.
 * Framing is HDLC's job, not the socket's, so chunk boundaries here
 * have no meaning — feed everything into [HdlcParser].
 *
 * Lifecycle: callers must call [connect] before [write] or collecting
 * [incoming]. After [close] the socket is single-shot; create a new
 * instance to reconnect.
 */
expect class TcpSocket(host: String, port: Int) {
    val host: String
    val port: Int

    suspend fun connect()
    suspend fun close()
    suspend fun write(bytes: ByteArray)

    /** Cold flow of raw byte chunks as they arrive. Closes when the
     *  remote drops the connection or [close] is called. */
    fun incoming(): Flow<ByteArray>
}
