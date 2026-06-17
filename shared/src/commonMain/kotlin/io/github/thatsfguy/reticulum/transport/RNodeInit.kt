package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.platform.RadioConfig

/** Big-endian uint32 encoding for the 4-byte CMD_FREQUENCY /
 *  CMD_BANDWIDTH payloads. */
internal fun uint32BE(v: Long): ByteArray = byteArrayOf(
    ((v ushr 24) and 0xFF).toByte(),
    ((v ushr 16) and 0xFF).toByte(),
    ((v ushr  8) and 0xFF).toByte(),
    ( v          and 0xFF).toByte(),
)

/**
 * The ordered KISS command sequence to bring an RNode's radio up,
 * aligned with RNS `RNodeInterface.initRadio()` (issue #18):
 *
 *   detect → freq → bw → txpower → sf → cr → radio_on
 *
 * Returned as `(command, payload)` pairs so the sequence can be
 * unit-tested without a live GATT / RFCOMM connection — the part that
 * regresses silently (command order, CMD_DETECT presence, payload
 * encoding) lives here, not welded to the platform transport. All three
 * RNode-driving transports (Android BLE, iOS BLE, BtClassic) consume
 * this one definition instead of carrying their own copy.
 *
 * The platform transport is responsible for the inter-command pacing
 * ([RNODE_INIT_INTERCMD_DELAY_MS]) and the post-config settle
 * ([RNODE_INIT_SETTLE_MS], mirroring RNS `if use_ble: sleep(2)` after
 * initRadio) — those are timing, not wire content, so they stay out of
 * this pure function.
 *
 * CMD_DETECT is best-effort: we do NOT hard-gate on DETECT_RESP the way
 * RNS does, so a slow/older firmware that works today can't regress into
 * a failed connect. Its reply is a non-DATA frame the [KissParser]
 * already ignores.
 */
fun rnodeRadioInitCommands(config: RadioConfig): List<Pair<Int, ByteArray>> = listOf(
    CMD_DETECT      to byteArrayOf(DETECT_REQ.toByte()),
    CMD_FREQUENCY   to uint32BE(config.frequencyHz),
    CMD_BANDWIDTH   to uint32BE(config.bandwidthHz),
    CMD_TXPOWER     to byteArrayOf(config.txPowerDbm.toByte()),
    CMD_SF          to byteArrayOf(config.spreadingFactor.toByte()),
    CMD_CR          to byteArrayOf(config.codingRate.toByte()),
    CMD_RADIO_STATE to byteArrayOf(0x01),
)

/** Pause between consecutive radio-config commands so the firmware can
 *  apply each setting before the next lands. */
const val RNODE_INIT_INTERCMD_DELAY_MS = 120L

/** Settle after CMD_RADIO_STATE(on) before the engine attaches and
 *  starts sending — lets the SX1262 leave the config transient and
 *  settle into RX. Mirrors RNS `RNodeInterface`'s `if use_ble: sleep(2)`
 *  after `initRadio()` (issue #18). */
const val RNODE_INIT_SETTLE_MS = 2000L
