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
        // Match upstream RNS TCPClientInterface: 5s connect timeout,
        // then no read timeout (idle silence is normal — RNS has no
        // application-level heartbeat over TCP).
        s.connect(InetSocketAddress(host, port), 5_000)
        // Disable Nagle so each Reticulum packet ships immediately —
        // upstream sets this and benchmarks badly without it.
        s.tcpNoDelay = true
        // Enable OS keepalive. Without this, half-open connections (NAT
        // idle timeout, middlebox dropping conntrack) sit dead until the
        // next write triggers an RST. JDK's default keepalive idle is 2
        // hours, way too long for mobile — try to override via
        // ExtendedSocketOptions where available, fall back to OS default.
        s.keepAlive = true
        runCatching {
            // jdk.net.ExtendedSocketOptions is present on Android API 30+
            // (JDK 11 desugar) and on regular JDK 11+. On older Android
            // these throw and we silently keep the OS default.
            val cls = Class.forName("jdk.net.ExtendedSocketOptions")
            val get: (String) -> java.net.SocketOption<Int>? = { name ->
                @Suppress("UNCHECKED_CAST")
                runCatching { cls.getField(name).get(null) as java.net.SocketOption<Int> }.getOrNull()
            }
            get("TCP_KEEPIDLE")?.let { s.setOption(it, 5) }      // idle seconds before first probe
            get("TCP_KEEPINTVL")?.let { s.setOption(it, 2) }     // seconds between probes
            get("TCP_KEEPCNT")?.let { s.setOption(it, 12) }      // probes before declaring dead
        }
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
