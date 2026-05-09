package io.github.thatsfguy.reticulum.platform

/**
 * RNode LoRa radio configuration. Values are sent verbatim to the RNode
 * via KISS commands when the BLE link comes up. Adjust to match the
 * local mesh you're joining — wrong freq/BW/SF means no one hears you.
 *
 * Defaults are tuned for an existing RatDeck mesh (US 902–928 ISM,
 * narrower-than-normal BW, SF 10 for range over rate, CR 4/5, max
 * TX). Change these in Settings → Radio config and a fresh install
 * picks them up automatically.
 */
data class RadioConfig(
    val frequencyHz: Long = 904_375_000L,
    val bandwidthHz: Long = 250_000L,
    val spreadingFactor: Int = 10,   // 7..12
    val codingRate: Int = 5,         // 5..8 (4/5 .. 4/8)
    val txPowerDbm: Int = 22,        // -9..22 typical
)
