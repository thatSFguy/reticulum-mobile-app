package io.github.thatsfguy.reticulum.transport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.posix.AF_UNSPEC
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_KEEPALIVE
import platform.posix.TCP_NODELAY
import platform.posix.addrinfo
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.setsockopt
import platform.posix.socket as posixSocket
import platform.posix.connect as posixConnect
import platform.posix.close as posixClose
import platform.posix.read as posixRead
import platform.posix.write as posixWrite

/**
 * iOS implementation of [TcpSocket] backed by BSD/POSIX sockets via
 * `platform.posix.*`. Mirrors the Android actual's contract 1:1 — Java's
 * `Socket` is itself a thin wrapper around the same syscalls, so the
 * Android and iOS impls behave identically in foreground.
 *
 * Why POSIX over Network.framework: simpler binding, full
 * Kotlin/Native interop, direct port from the Android impl. The
 * trade-off is iOS background mode — POSIX sockets don't get the
 * cellular-handoff / multipath features `NWConnection` provides for
 * free. For the current "personal-sideload-only, foreground use" target
 * this is fine; Phase 4 can swap to `NWConnection` if backgrounding
 * matters in real deployments.
 *
 * Cancellation: closing the file descriptor from another thread
 * unblocks the in-flight `read()` with `errno=EBADF`, exactly as
 * Android relies on `Socket.close()` to interrupt a blocking read.
 */
@OptIn(ExperimentalForeignApi::class)
actual class TcpSocket actual constructor(
    actual val host: String,
    actual val port: Int,
) {
    /** -1 when not connected. Volatile because [close] writes from any
     *  thread while [incoming]'s read loop is blocked on syscall. */
    @kotlin.concurrent.Volatile
    private var fd: Int = -1

    actual suspend fun connect() = withContext(Dispatchers.Default) {
        memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_STREAM
                ai_protocol = IPPROTO_TCP
            }
            val resultPtr = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(host, port.toString(), hints.ptr, resultPtr.ptr)
            if (rc != 0) {
                error("getaddrinfo($host) failed: rc=$rc")
            }
            try {
                val addr = resultPtr.value!!.pointed
                val sock = posixSocket(addr.ai_family, addr.ai_socktype, addr.ai_protocol)
                if (sock < 0) {
                    error("socket() failed: errno=${errno}")
                }

                // Match Android's TcpSocket.android.kt: TCP_NODELAY for
                // packet-per-write semantics (RNS does its own batching),
                // SO_KEEPALIVE so half-open connections after a NAT idle
                // get torn down instead of silently buffering.
                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
                setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, one.ptr, sizeOf<IntVar>().convert())

                if (posixConnect(sock, addr.ai_addr, addr.ai_addrlen) != 0) {
                    val savedErrno = errno
                    posixClose(sock)
                    error("connect($host:$port) failed: errno=$savedErrno")
                }
                fd = sock
            } finally {
                freeaddrinfo(resultPtr.value)
            }
        }
    }

    actual suspend fun close() = withContext(Dispatchers.Default) {
        val s = fd
        if (s >= 0) {
            // Set fd = -1 BEFORE close() so the read loop sees the change
            // even if its blocked syscall returns before the next iteration.
            fd = -1
            posixClose(s)
        }
    }

    actual suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.Default) {
        val s = fd
        if (s < 0) error("TcpSocket not connected")
        if (bytes.isEmpty()) return@withContext
        bytes.usePinned { pinned ->
            var written = 0
            while (written < bytes.size) {
                val remaining = (bytes.size - written).convert<ULong>()
                val n = posixWrite(s, pinned.addressOf(written), remaining)
                if (n < 0) error("write() failed: errno=${errno}")
                if (n == 0L) error("write() returned 0 bytes — peer closed?")
                written += n.toInt()
            }
        }
    }

    actual fun incoming(): Flow<ByteArray> = flow {
        if (fd < 0) error("TcpSocket not connected — call connect() first")
        val buf = ByteArray(4096)
        while (true) {
            val s = fd
            if (s < 0) break  // close() raced us; bail cleanly.
            val n = buf.usePinned { pinned ->
                posixRead(s, pinned.addressOf(0), buf.size.convert<ULong>())
            }
            if (n <= 0L) {
                // 0 = clean EOF (peer closed). <0 = error; usually EBADF
                // because close() shut the fd from another coroutine.
                // Either way, the read loop is done.
                break
            }
            emit(buf.copyOf(n.toInt()))
        }
    }.flowOn(Dispatchers.Default)
}
