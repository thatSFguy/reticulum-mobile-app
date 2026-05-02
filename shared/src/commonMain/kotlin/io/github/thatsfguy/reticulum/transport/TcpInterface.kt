package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * [Transport] implementation that attaches the app directly to an
 * rnsd TCPServerInterface — e.g. a public transport node such as
 * `RNS.MichMesh.net:7822`.
 *
 * Wire protocol: raw TCP carrying HDLC-framed Reticulum packets.
 * That's the entire spec — no upgrade handshake, no auth, no TLS.
 * Upstream's [RNS/Interfaces/TCPInterface.py] is just SocketServer
 * pumping bytes through `HDLC.escape` / `HDLC.unescape`.
 *
 * Tradeoffs vs. attaching a local RNode over BLE:
 *   + No radio hardware required, no LoRa range constraints.
 *   + Works while the phone is online over any network (Wi-Fi / LTE).
 *   - Loses the off-grid property — this is just an internet client.
 *   - No RSSI/SNR metadata; [IncomingPacket] always has rssi=null.
 *   - You see EVERY packet flooding through the transport node
 *     (announces from the whole connected mesh, not just your local
 *     RF neighborhood). The contact list will be much noisier.
 *   - The transport operator can observe your destination hash and
 *     announce traffic — same as any RNS attachment, not specific to
 *     this implementation, but worth surfacing in the UI.
 *
 * Threading: the read loop runs as a child coroutine of [scope] on
 * whatever dispatcher [TcpSocket.incoming] hands it. Cancel [scope]
 * or call [disconnect] to stop.
 */
class TcpInterface(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val socketFactory: (String, Int) -> TcpSocket = ::TcpSocket,
    private val txLogger: (String) -> Unit = {},
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private var socket: TcpSocket? = null
    private var readJob: Job? = null

    /** Serializes outbound writes. Concurrent send() calls without this
     *  can interleave HDLC frames mid-byte and corrupt the stream;
     *  upstream Python relies on the GIL for the same effect. */
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    private val parser = HdlcParser { packet ->
        // HDLC payload IS the raw Reticulum packet (no command byte
        // like KISS). RSSI/SNR are unavailable on the rnsd path.
        _incoming.tryEmit(IncomingPacket(packet = packet, rssi = null, snr = null))
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected ||
            _state.value == TransportState.Connecting) return

        _state.value = TransportState.Connecting
        try {
            val s = socketFactory(host, port).also { socket = it }
            s.connect()
            parser.reset()

            readJob = scope.launch {
                try {
                    s.incoming().collect { chunk -> parser.feed(chunk) }
                    // Flow completed normally → remote closed cleanly.
                    txLogger("TCP: read loop ended (remote closed)")
                    _state.value = TransportState.Disconnected
                } catch (t: Throwable) {
                    // Surface the actual failure — without this the read
                    // loop dies silently and the only sign is the connect
                    // supervisor reporting "TCP transport ended: Error"
                    // every reconnect.
                    txLogger("TCP: read loop crashed: ${t::class.simpleName}: ${t.message}")
                    _state.value = TransportState.Error
                }
            }
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            socket?.close()
            socket = null
            throw t
        }
    }

    override suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        socket?.close()
        socket = null
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(packet: ByteArray) {
        val s = socket ?: error("TcpInterface not connected")
        // Diagnostic: emit a short prefix of every outbound RAW packet so
        // we can wire-trace what's leaving this app vs what Python RNS
        // would emit for the same logical operation. The HDLC framing
        // adds the 0x7E delimiters; what's logged here is the underlying
        // Reticulum packet.
        val n = minOf(packet.size, 32)
        val hex = (0 until n).joinToString("") { (packet[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
        txLogger("tx ${packet.size}B: $hex${if (packet.size > n) "..." else ""}")
        val frame = buildHdlcFrame(packet)
        writeMutex.withLock { s.write(frame) }
    }
}
