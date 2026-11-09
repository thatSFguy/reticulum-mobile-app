package io.github.thatsfguy.reticulum.transport

/** How to write to a BLE characteristic. */
enum class BleWriteMode { WithoutResponse, WithResponse }

/**
 * Pick the write mode a characteristic actually supports, or null if it
 * accepts neither.
 *
 * This has to come from the characteristic's own properties because
 * RNodes differ. Upstream's nRF52 builds (Bluefruit BLEUart) offer both
 * modes; its ESP32 builds declare only `PROPERTY_WRITE`
 * (`RNode_Firmware/BLESerial.cpp`). CoreBluetooth reports no error for a
 * without-response write to a characteristic that doesn't support it,
 * so a fixed mode can look connected while nothing reaches the radio.
 *
 * Without-response wins when both are offered: it needs no round trip
 * per chunk.
 */
fun bleWriteModeFor(
    supportsWithoutResponse: Boolean,
    supportsWithResponse: Boolean,
): BleWriteMode? = when {
    supportsWithoutResponse -> BleWriteMode.WithoutResponse
    supportsWithResponse -> BleWriteMode.WithResponse
    else -> null
}
