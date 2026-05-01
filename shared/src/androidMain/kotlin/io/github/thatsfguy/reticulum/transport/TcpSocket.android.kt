package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android JVM implementation of [TcpSocket] backed by [java.net.Socket].
 *
 * IO is dispatched through [Dispatchers.IO]. The read loop blocks on
 * InputStream.read, which is cancellable on coroutine cancellation
 * because closing the socket from another thread unblocks read with
 * an IOException — so [close] is the cancellation mechanism.
 *
 * No timeouts, no keepalive, no TLS. The rnsd TCPServerInterface is
 * plain TCP and the upstream Reticulum stack handles its own session
 * liveness via Link keepalives.
 */
actual class TcpSocket actual constructor(
    actual val host: String,
    actual val port: Int,
) {
    private var socket: Socket? = null

    actual suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port))
        s.tcpNoDelay = true
        socket = s
    }

    actual suspend fun close() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
    }

    actual suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val s = socket ?: error("TcpSocket not connected")
        val out = s.getOutputStream()
        out.write(bytes)
        out.flush()
    }

    actual fun incoming(): Flow<ByteArray> = flow {
        val s = socket ?: error("TcpSocket not connected — call connect() first")
        val input = s.getInputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            emit(buf.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)
}
