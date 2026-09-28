package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TcpInterface] with [TcpFraming.Kiss] against a real loopback socket
 * standing in for a KISS-over-TCP TNC (modem73 / Direwolf). Behaviour
 * mirrors upstream `TCPClientInterface(kiss_framing=True)`: outbound
 * packets are `FEND 0x00 escaped(pkt) FEND`; inbound data frames on any
 * KISS port are accepted, non-data commands are ignored.
 */
class KissTcpInterfaceTest {

    private val fend = 0xC0.toByte()

    @Test fun sendWritesPlainKissDataFrame() = runBlocking {
        ServerSocket(0).use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val tnc = scope.async { server.accept() }
                val iface = TcpInterface("127.0.0.1", server.localPort, scope, framing = TcpFraming.Kiss)
                iface.connect()
                val peer = withTimeout(5_000) { tnc.await() }

                val packet = byteArrayOf(0x01, 0xC0.toByte(), 0x02, 0xDB.toByte(), 0x03)
                iface.send(packet)

                val expected = buildKissFrame(CMD_DATA, packet)
                val got = ByteArray(expected.size)
                var off = 0
                peer.soTimeout = 5_000
                while (off < got.size) {
                    val n = peer.getInputStream().read(got, off, got.size - off)
                    check(n > 0) { "TNC side closed early" }
                    off += n
                }
                assertContentEquals(expected, got)
                // FEND + CMD_DATA, no RNode config commands before it.
                assertEquals(fend, got[0])
                assertEquals(0x00.toByte(), got[1])

                iface.disconnect()
                peer.close()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun receiveStripsPortNibbleAndIgnoresNonDataFrames() = runBlocking {
        ServerSocket(0).use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val tnc = scope.async { server.accept() }
                val iface = TcpInterface("127.0.0.1", server.localPort, scope, framing = TcpFraming.Kiss)
                // UNDISPATCHED: subscribe to `incoming` (no replay) right
                // here, before the TNC writes — a lazily-dispatched
                // collector can miss frames that arrive first.
                val received = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) { iface.incoming.take(2).toList() }
                }
                iface.connect()
                val peer = withTimeout(5_000) { tnc.await() }

                val first = byteArrayOf(0x10, 0x20, 0xC0.toByte(), 0x30)
                val second = byteArrayOf(0x44, 0x55)
                val out = peer.getOutputStream()
                // TXDELAY (0x01) from the TNC — not data, must be dropped.
                out.write(buildKissFrame(0x01, byteArrayOf(0x32)))
                // Data on KISS port 0.
                out.write(buildKissFrame(CMD_DATA, first))
                // Data on KISS port 1 (cmd 0x10), split across two writes.
                val f2 = buildKissFrame(0x10, second)
                out.write(f2, 0, 3); out.flush()
                out.write(f2, 3, f2.size - 3); out.flush()

                val pkts = received.await()
                assertContentEquals(first, pkts[0].packet)
                assertContentEquals(second, pkts[1].packet)
                assertNull(pkts[0].rssi)
                assertNull(pkts[0].snr)

                iface.disconnect()
                peer.close()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun hdlcFramingIsStillTheDefault() = runBlocking {
        ServerSocket(0).use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val tnc = scope.async { server.accept() }
                val iface = TcpInterface("127.0.0.1", server.localPort, scope)
                iface.connect()
                val peer = withTimeout(5_000) { tnc.await() }
                val packet = byteArrayOf(0x01, 0x7E, 0x02)
                iface.send(packet)
                val expected = buildHdlcFrame(packet)
                val got = ByteArray(expected.size)
                var off = 0
                peer.soTimeout = 5_000
                while (off < got.size) {
                    val n = peer.getInputStream().read(got, off, got.size - off)
                    check(n > 0) { "peer closed early" }
                    off += n
                }
                assertContentEquals(expected, got)
                iface.disconnect()
                peer.close()
            } finally {
                scope.cancel()
            }
        }
    }
}
