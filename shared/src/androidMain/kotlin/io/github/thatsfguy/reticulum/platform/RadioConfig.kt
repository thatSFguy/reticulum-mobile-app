package io.github.thatsfguy.reticulum.platform

/**
 * RNode LoRa radio configuration. Values are sent verbatim to the RNode
 * via KISS commands when the BLE link comes up. Defaults match the
 * common North-America Reticulum profile (915 MHz ISM, 125 kHz BW,
 * SF7, CR4/5, 17 dBm) — adjust to match the local mesh you're joining.
 */
data class RadioConfig(
    val frequencyHz: Long = 915_000_000L,
    val bandwidthHz: Long = 125_000L,
    val spreadingFactor: Int = 7,    // 7..12
    val codingRate: Int = 5,         // 5..8 (4/5 .. 4/8)
    val txPowerDbm: Int = 17,        // -9..22 typical
)
