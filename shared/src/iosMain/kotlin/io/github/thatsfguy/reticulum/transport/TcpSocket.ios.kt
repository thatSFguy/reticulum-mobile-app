package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.flow.Flow

/**
 * iOS implementation of [TcpSocket].
 *
 * TODO: Implement using NSStream pair from
 *       CFStreamCreatePairWithSocketToHost, or Network.framework
 *       NWConnection (iOS 12+). NWConnection is the modern path and
 *       handles backgrounding more cleanly.
 *
 * The shape mirrors the Android actual: connect once, single read
 * flow, single-shot close. See TcpSocket.android.kt for the contract.
 */
actual class TcpSocket actual constructor(
    actual val host: String,
    actual val port: Int,
) {
    actual suspend fun connect() {
        TODO("iOS NWConnection-backed connect")
    }

    actual suspend fun close() {
        TODO("iOS NWConnection close")
    }

    actual suspend fun write(bytes: ByteArray) {
        TODO("iOS NWConnection write")
    }

    actual fun incoming(): Flow<ByteArray> {
        TODO("iOS NWConnection receive flow")
    }
}
