# Mobile-App ↔ reticulum-loramesh: BLE Integration Spec

**Hand-off doc for the mobile-app repo / agent.**

This document specifies how a mobile Reticulum app (e.g., a fork of Sideband / a fresh build) connects to a reticulum-loramesh firmware node over **BLE GATT** and integrates as a Reticulum `Interface` in the app's existing RNS instance.

Self-contained — you do not need the firmware repo to implement against this. References to firmware source paths are provided as ground truth if you need to verify a wire-format detail.

---

## 0. Change-log — read this before re-implementing

**2026-05-26 — Samsung-Android BLE compatibility (KNOWN ISSUE — must fix on app side):**

Empirical test result: with the firmware MeshCore-backed and running ~30 s HELLO cycles, **Samsung phones drop the BLE link exactly 5.04 s after every LoRa TX-DONE** while Pixels on the identical firmware stay connected indefinitely. The 5.04 s matches Samsung's *forced* supervision-timeout value (`sup=500`), which Samsung's BLE stack overrides our PPCP to regardless of what we request. Samsung's stack appears to interpret the brief post-TX BLE-radio silence as a link failure where stock Android tolerates it.

**The firmware is correct.** The mesh works end-to-end (Pixel proves it). The fix has to live in the mobile app. See §13 below for specific Samsung mitigations the app MUST implement.

**2026-05-26 (afternoon) — `CMD_DATA_RX` payload extended (BREAKING).** The payload now carries per-message link metadata before the reticulum bytes:

```
OLD:  src_node[2 BE] || reticulum_bytes
NEW:  src_node[2 BE] || rssi_dbm[1 i8] || snr_db[1 i8] || hops[1 u8] || reticulum_bytes
```

The mobile app MUST update its `CMD_DATA_RX` decoder to read bytes 2-4 as `rssi/snr/hops` and pass bytes 5+ (not 2+) to `RNS.Transport.inbound`. Until updated, the app will receive misaligned bytes for RNS parsing and throw `IllegalStateException` ("transport disconnected"). See §4 for the full format + test vectors T1-T5.

**2026-05-26 — breaking wire-format changes.** If you have an existing implementation, you MUST update it:

1. **KISS CRC-16 trailer removed.** Frames are now `FEND CMD payload... FEND` with **no CRC** in between. BLE GATT already provides a link-layer CRC-24 and USB-CDC has bulk-transfer CRC, so the KISS-level CRC was redundant and was a real source of decode-error bugs whenever the encoder/decoder disagreed on byte order or polynomial. See §3 for the updated framing.
2. **`frames_bad_crc` counter is retained but always 0** in `NODE_INFO_REQ` replies, kept for ABI compatibility with older diag UIs. Don't read it as a health signal.
3. **Recommended BLE connection parameters widened.** The firmware now sets PPCP `20-50 ms` interval (was `15-30`) and `6000 ms` supervision timeout (was `2000`/`4000`). Update your central-side request to match, and — see §2.5 — explicitly issue a connection-parameter-update on Android post-pairing; Samsung BLE stacks sometimes ignore PPCP.

If you're reading this for the first time, you can ignore the historical notes above — the spec below already reflects the current wire format.

---

## 1. What you're connecting to

`reticulum-loramesh` is a custom firmware for nRF52840 / ESP32 LoRa boards that:

- Implements a **distance-vector mesh router with link-quality (SNR-weighted) routing** in firmware.
- Presents itself to a host as a **KISS-framed link** where every destination on the mesh appears one RNS-hop away (the mesh does its own multi-hop routing under the surface).
- Speaks a **custom KISS dialect** — same FEND/FESC framing as standard KISS but a different command set. Crucially, this is **not** RNode-compatible — stock `RNodeInterface` will not work against it.
- Runs `node_id`-based mesh routing internally (16-bit IDs); identity ↔ node_id mapping is learned via `ANNOUNCE_FORWARD` frames.
- Handles **none of Reticulum's encryption.** The app's RNS instance is the security boundary; firmware moves opaque encrypted bytes.

From the app's perspective: this is **a wireless RNS Interface that delivers Reticulum Packet bytes to and from a mesh of other RNS hosts**, with the mesh routing happening invisibly in firmware.

---

## 2. BLE Service definition

The firmware exposes a **single GATT service** built on the Nordic UART Service (NUS) pattern. UUIDs are intentionally the standard NUS UUIDs so the firmware side can use any off-the-shelf BLE NUS implementation (e.g., Adafruit Bluefruit BLEUart on nRF52).

### Service

| Attribute | Value |
|---|---|
| **Service UUID** | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART Service) |
| Advertised name | `rlm-<6 hex>` where the 6 hex chars are derived from the chip's unique ID (e.g., `rlm-a3f2c1`) |
| Manufacturer data | optional, may include the firmware's `node_id` as 2 BE bytes — use it as a discovery hint, not a stable identifier (node_id can be re-provisioned) |
| Advertising interval | 100 ms (50 ms in fast-pairing mode for the first 30 s after boot) |

### Characteristics

Two characteristics, both reside on the NUS service:

| Name | UUID | Direction | Properties | Purpose |
|---|---|---|---|---|
| **RX** (from app's POV: write to firmware) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | App → Firmware | Write, WriteWithoutResponse | App sends KISS-framed bytes here |
| **TX** (from app's POV: notifications from firmware) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Firmware → App | Notify | App subscribes to receive KISS-framed bytes |

These names follow the Nordic convention: **RX is what the peripheral (firmware) receives — i.e., what the central (app) writes.** Don't get them backwards.

### MTU

- Request **ATT MTU 247** on connect (BLE 5.x default max). Firmware will accept anything ≥ 23 (the BLE minimum).
- A single KISS frame may exceed one BLE notification; **the app must concatenate consecutive notifications and run them through the streaming KISS decoder.** KISS self-synchronizes on `FEND`, so framing across notification boundaries is not a special case.
- Conversely when writing: split outgoing KISS frames into chunks ≤ `negotiated_mtu - 3` bytes. Order is preserved on a single GATT write characteristic.

### Connection parameters

Request from the central (app):

| Parameter | Value | Why |
|---|---|---|
| Min connection interval | 20 ms | Sub-100ms first-byte latency for messaging UX |
| Max connection interval | 50 ms | Bounded power cost; gives the Android scheduler room when the firmware's LoRa SPI bus is busy |
| Slave latency | 0 | Snappy bidirectional |
| Supervision timeout | **6000 ms** | Must comfortably exceed worst-case LoRa TX duration (~1.4 s for a 222 B SF8/BW125 frame). 2 s caused supervision-timeout drops (`reason=0x08`) on Samsung centrals — verified empirically 2026-05-26. |

The firmware's PPCP advertises these same values (`src/Ble.cpp::init`), but Android — Samsung in particular — often ignores PPCP. **Issue an explicit `gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)` plus a low-level connection-parameter-update right after pairing succeeds**, as reference implementations like MeshCore do.

For background / locked-screen battery saving, the firmware may request a slower interval (~100–250 ms + latency 4) after 60 s of host idleness; the app should accept.

### Pairing / bonding

**Just-Works pairing is the default** (no PIN prompt). The firmware does not require encryption to operate, but the app **should** request encryption + bonding on first connect — the BLE stack will negotiate AES-CCM at Level-2.

- Bond is stored on the firmware so subsequent connects skip the pairing dance.
- The mobile OS bonds on its side; reconnects are faster.

**Passkey-Entry pairing (Level-3 security, MITM-protected) is available as an opt-in** but not enabled by default. To turn it on, the operator sends `CMD_SET_BLE_PASSKEY` (opcode `0x09`, payload = 6 ASCII digits) over an already-established USB or BLE session. The new passkey takes effect for the *next* pairing; existing bonds keep working until cleared.

Why opt-in: the first cut of the mobile-side BLE pairing flow ran into the OS pairing-prompt state machine blocking on Android in ways that took hold of the connection state until the user manually canceled. Firmware defaults to Just-Works until the mobile app handles the Passkey-Entry flow cleanly.

Empty-string passkey (`CMD_SET_BLE_PASSKEY` with empty payload) reverts to Just-Works. Both modes use the same KISS-over-NUS protocol on top; the change is purely at the BLE link-layer.

---

## 3. Protocol over BLE: KISS

Once the BLE connection is up and the app has subscribed to the TX characteristic's notifications, **the byte stream in both directions is exactly the firmware's KISS dialect.** No additional BLE-level framing.

### Framing rules

```
FEND  CMD  ...escape-encoded-body...  FEND
```

| Symbol | Value |
|---|---|
| FEND  | `0xC0` |
| FESC  | `0xDB` |
| TFEND | `0xDC` |
| TFESC | `0xDD` |

Escape rules applied to the body (CMD + payload):

- `0xC0` byte → `0xDB 0xDC`
- `0xDB` byte → `0xDB 0xDD`

**No CRC trailer.** Both transports we run over already carry their own integrity check — BLE GATT has CRC-24 at the link layer, USB-CDC has bulk-transfer CRC — and a separate KISS-level CRC-16 was both redundant and a real source of decode-error bugs whenever the encoder/decoder disagreed on byte order or polynomial. If you're porting from a pre-2026-05-26 implementation, **delete the CRC compute/verify code on both encode and decode paths**.

Maximum decoded frame size (CMD + payload) is **512 bytes**. Anything larger is dropped with `BAD_LENGTH`.

### Streaming decoder state machine

On the app side, byte-by-byte (you'll feed bytes from each BLE notification through this):

```
IDLE       -- discard until first FEND, then → IN_FRAME (empty buf)
IN_FRAME   -- on FEND   → finalize: if buf is empty ignore (empty frame is a sync
                          artifact); else deliver (CMD=buf[0], payload=buf[1..]); → IDLE.
              on FESC   → ESCAPED
              else      → append byte to buf (overflow at 512 → flag BAD_LENGTH, → IDLE)
ESCAPED    -- on TFEND  → append 0xC0 → IN_FRAME
              on TFESC  → append 0xDB → IN_FRAME
              else      → flag BAD_ESCAPE, → IDLE
```

Reference Kotlin encoder:

```kotlin
fun kissEncode(cmd: Int, payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0xC0)                                      // leading FEND
    fun emit(b: Int) = when (b and 0xFF) {
        0xC0 -> { out.write(0xDB); out.write(0xDC) }    // FEND  → FESC TFEND
        0xDB -> { out.write(0xDB); out.write(0xDD) }    // FESC  → FESC TFESC
        else ->   out.write(b and 0xFF)
    }
    emit(cmd)
    for (b in payload) emit(b.toInt())
    out.write(0xC0)                                      // trailing FEND
    return out.toByteArray()
}
```

Reference Python implementation of both encoder and decoder: `host/rns_loramesh.py::kiss_encode` and `host/rns_loramesh.py::KissDecoder` in the firmware repo.

---

## 4. Command reference

### App → Firmware

| CMD | Name | Payload | When to use |
|---|---|---|---|
| `0x00` | `DATA_TX` | `dst_identity_hash[16]` ‖ `reticulum_bytes` | The app's RNS `Interface.processOutgoing()` ships a packet; encode it here. |
| `0x01` | `DIAG_ENABLE` | 1 byte (0=off, 1=on) | Optional — toggles verbose `DIAG_EVENT` streaming for debug builds. |
| `0x02` | `CONFIG_CMD` | msgpack envelope (not yet implemented in firmware; replies `UNIMPLEMENTED`) | Phase 7+ — leave unimplemented in the app's first cut. |
| `0x03` | `NODE_INFO_REQ` | empty | Send on connect to learn the node's `node_id`, board, firmware version, counters. |
| `0x04` | `REGISTER_IDENTITY` | `identity_hash[16]` | **Send on connect** with the RNS identity the app intends to use. Firmware will emit `ANNOUNCE_FORWARD` frames to the mesh claiming this identity. |
| `0x05` | `DUMP_STATE` | empty | Optional — for diag UI: streams a multi-row dump (neighbors, routes, identities, drop counters) framed by `DUMP-BEGIN`/`DUMP-END` literal lines inside `DIAG_EVENT` payloads. |

### Firmware → App

| CMD | Name | Payload |
|---|---|---|
| `0x00` | `DATA_RX` | `src_node[2]` ‖ `reticulum_bytes` |
| `0x01` | `DIAG_EVENT` | UTF-8 ASCII string (status / log line) |
| `0x02` | `CONFIG_REPLY` | ASCII status line (response to `NODE_INFO_REQ`) or msgpack reply (Phase 7+) |

#### `DATA_RX` payload layout

**Wire format (5-byte metadata header + reticulum bytes), since 2026-05-26:**

```
+-----------+---------+---------+---------+--- ... ---+
| src_node  | rssi    | snr     | hops    | reticulum |
| [2 BE]    | [1 i8]  | [1 i8]  | [1 u8]  | bytes...  |
+-----------+---------+---------+---------+--- ... ---+
  offset 0    offset 2  offset 3  offset 4  offset 5
```

| Field | Bytes | Type | Meaning |
|---|---|---|---|
| `src_node` | 0-1 | uint16 big-endian | Mesh node_id that originated the DATA frame. Display-only — RNS doesn't use it. |
| `rssi_dbm` | 2 | **signed int8** | RSSI of the last-hop arrival in dBm. Typical range -120..-30. NOT end-to-end if `hops > 1`. |
| `snr_db` | 3 | **signed int8** | LoRa SNR of the last-hop arrival, in dB. Typical range -20..+12. NOT end-to-end if `hops > 1`. |
| `hops` | 4 | uint8 | Mesh hop count from `src_node` to the local node. **1 = direct neighbor.** 0 = unknown (route table didn't have an entry at delivery time — this is rare and usually transient). |
| `reticulum_bytes` | 5..end | byte array | The encrypted RNS Packet, byte-for-byte. |

Important: **`rssi`, `snr`, and `hops` describe the last hop, NOT the end-to-end link.** If `hops=3` and `rssi=-50 dBm`, that means the immediate sender (the 3rd-hop relay) is -50 dBm away — not that `src_node` is -50 dBm away.

The app should:

1. Read `src_node` from bytes 0-1 (for display).
2. Read `rssi`/`snr`/`hops` from bytes 2-4 (for the link-quality UI).
3. Pass `reticulum_bytes` (everything from byte 5 onwards) directly to `RNS.Transport.inbound(packet, interface=self)` (or equivalent for your RNS port).

#### `DATA_RX` test vectors

Each vector is `<hex of CMD_DATA_RX payload (before KISS framing)>` followed by the decoded fields. The mobile app's parser should produce identical decoded fields. Add these as TDD test cases.

**T1 — direct-neighbor, strong link, 4-byte reticulum payload:**
```
payload hex:   9C 8A C5 0C 01 DE AD BE EF
              └──┬──┘ │  │  │  └────┬────┘
                 │    │  │  │      └── reticulum_bytes = DE AD BE EF
                 │    │  │  └────────── hops = 0x01 = 1 (direct)
                 │    │  └─────────────── snr = 0x0C = +12 dB
                 │    └────────────────── rssi = 0xC5 = -59 dBm  (int8(0xC5) = -59)
                 └─────────────────────── src_node = 0x9C8A
```
Decoded: `src_node=0x9C8A, rssi=-59, snr=12, hops=1, reticulum_bytes=[0xDE,0xAD,0xBE,0xEF]`

**T2 — 3-hop arrival, weak last-hop, negative SNR, empty reticulum payload:**
```
payload hex:   7C 63 A6 EC 03
              └──┬──┘ │  │  │
                 │    │  │  └── hops = 3
                 │    │  └───── snr = 0xEC = -20 dB
                 │    └──────── rssi = 0xA6 = -90 dBm
                 └───────────── src_node = 0x7C63
```
Decoded: `src_node=0x7C63, rssi=-90, snr=-20, hops=3, reticulum_bytes=[]`

**T3 — unknown hops (route not yet learned), longer reticulum payload:**
```
payload hex:   12 34 D8 0A 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10
              └──┬──┘ │  │  │  └──────────────── 16-byte reticulum_bytes ────┘
                 │    │  │  └── hops = 0 (unknown — display "?" or "—")
                 │    │  └───── snr = 0x0A = +10 dB
                 │    └──────── rssi = 0xD8 = -40 dBm  (very strong)
                 └───────────── src_node = 0x1234
```
Decoded: `src_node=0x1234, rssi=-40, snr=10, hops=0, reticulum_bytes=[0x00..0x10]`

**T4 — boundary: minimum valid frame (no reticulum bytes):**
```
payload hex:   FF FF 80 80 FF
```
Decoded: `src_node=0xFFFF, rssi=-128, snr=-128, hops=255, reticulum_bytes=[]`

This is the smallest legal `DATA_RX` payload (5 bytes). If the app receives a `CMD_DATA_RX` with <5 bytes of payload, treat as malformed and log.

**T5 — boundary: positive RSSI (firmware clamps to 0 — should never actually appear over the air, but the byte layout must handle it):**
```
payload hex:   00 01 00 00 01 AA
```
Decoded: `src_node=0x0001, rssi=0, snr=0, hops=1, reticulum_bytes=[0xAA]`

#### Sign-extension reminder (Kotlin / Java)

`Byte` in Kotlin/Java is signed. `rssi` and `snr` are already signed int8, so reading `bytes[2].toInt()` and `bytes[3].toInt()` gives the correct signed value directly. `hops` is unsigned, so use `bytes[4].toInt() and 0xFF` to avoid sign-extension turning 0xFF into -1.

```kotlin
// Correct
val srcNode = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
val rssi    = bytes[2].toInt()              // already signed, range -128..127
val snr     = bytes[3].toInt()              // already signed
val hops    = bytes[4].toInt() and 0xFF     // unsigned, range 0..255
val rns     = bytes.copyOfRange(5, bytes.size)
```

#### `NODE_INFO_REQ` reply format (current ASCII)

A single-line ASCII string in the `CONFIG_REPLY` payload, e.g.:

```
rlm v1 ProMicroDIY nid=0x0001 up=173030 radio[rx=12 tx=8 txerr=0] kiss[ok=14 badcrc=0 badlen=0 badesc=0] mesh[nbrs=2 routes=2 idmap=1 helloRx=8 helloTx=6 helloDrop=0 annRx=2 annTx=1 annFwd=1 dataSelf=0 dataFwd=0 dataDrop=0 txRefused=0]
```

The app does not need to parse this beyond `nid=0xNNNN` for display. Phase 7+ replaces this with a structured msgpack reply.

---

## 5. Reticulum Interface integration

The app should subclass its RNS library's `Interface` (the actual class name depends on which RNS port is in use — RNS Python, an Android Kotlin RNS port, an Objective-C port, etc.). The skeleton is the same across ports:

### Skeleton

```kotlin
class LoRaMeshBleInterface(
    private val rns: ReticulumStack,
    private val device: BluetoothDevice,
    private val localIdentityHash: ByteArray  // 16 bytes
) : RnsInterface() {

    private val decoder = KissDecoder()
    private val gatt = device.connectGatt(context, false, gattCallback)

    override val mtu = 232          // max payload bytes per docs/protocol.md
    override val direction = RnsInterface.BIDIRECTIONAL
    override var online: Boolean = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(247)
            } else {
                online = false
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(SERVICE_UUID).getCharacteristic(TX_UUID)
            g.setCharacteristicNotification(tx, true)
            val descriptor = tx.getDescriptor(CCCD_UUID)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)

            online = true
            sendRegisterIdentity(localIdentityHash)
            sendNodeInfoReq()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == TX_UUID) {
                for (b in c.value) {
                    if (decoder.feed(b.toInt() and 0xFF)) {
                        handleFrame(decoder.cmd, decoder.payload)
                        decoder.consume()
                    }
                }
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        // The first 16 bytes of an RNS Packet are NOT always the destination
        // hash (Link packets carry link_id instead). Two viable approaches:
        //
        //  (A) Pass an all-zero dst_identity_hash and let the firmware fall
        //      back to broadcast-flood. Simple, slightly more airtime.
        //  (B) Parse the outgoing packet using your RNS port's packet helper
        //      to extract the actual destination hash. Tighter routing.
        //
        // First cut: ship (A). Move to (B) once announce / packet handling is
        // verified end-to-end.
        val frame = kissEncode(CMD_DATA_TX, ByteArray(16) + data)
        writeRxCharacteristic(frame)
    }

    private fun handleFrame(cmd: Int, payload: ByteArray) {
        when (cmd) {
            CMD_DATA_RX -> {
                // Strip 2-byte src_node prefix; rest is the RNS Packet.
                if (payload.size < 2) return
                val rnsBytes = payload.sliceArray(2 until payload.size)
                rns.transport.inbound(rnsBytes, this)
            }
            CMD_DIAG_EVENT -> log.d("DIAG", String(payload, Charsets.UTF_8))
            CMD_CONFIG_REPLY -> handleConfigReply(payload)
        }
    }

    // KISS encode (FEND/FESC only, no CRC — see §3) and write to the
    // firmware's RX char. Splits into chunks of negotiated_mtu - 3 bytes;
    // order is preserved on a single write characteristic.
    private fun writeRxCharacteristic(frame: ByteArray) { /* ... */ }
}
```

### Things the app's `Interface` does NOT need to do

- Decryption: RNS Transport / Link layer in the app handles all packet decryption.
- Path-table maintenance: RNS Transport already does this. Every peer appears 1 hop away through this interface.
- Multi-hop awareness: the firmware does multi-hop. From RNS's POV, this interface delivers packets to any reachable identity; how it does that internally is opaque.
- Retries: firmware drops un-routable frames immediately (D6 in the firmware's `docs/decisions.md`). RNS's Link layer retries above us.

### Things the app **MUST** do

- **Send `REGISTER_IDENTITY` after every reconnect.** The firmware does not persist registered host identities (only the local-host registration; if the connection drops it stays valid, but if the firmware reboots it's lost). Watch for the `READY`/banner equivalent and re-register. See §7 "Reconnect detection."
- **Honor backpressure.** If the app can't drain `DATA_RX` fast enough, the firmware drops frames silently (no flow control in KISS by design). Keep the GATT notification handler fast.
- **Tolerate `BAD_LENGTH` / `BAD_ESCAPE`** at the decoder level — log them, do not disconnect on a single bad frame. (CRC validation no longer exists — see §3.)

---

## 6. Encryption boundaries

The firmware **is not part of Reticulum's security boundary.** It moves opaque bytes.

| Layer | Encryption | Handled by |
|---|---|---|
| BLE transport | optional just-works AES-CCM + link-layer CRC-24 | BLE stack (mobile OS + firmware) |
| KISS frame | none (no integrity check at this layer; relies on the transport's CRC) | firmware + app |
| Mesh routing | none | firmware |
| RNS Packet | per-destination ECDH + AES | **RNS in the app**, not firmware |
| RNS Link | per-link ECDH + AES | RNS in the app, not firmware |
| LXMF / Sideband | message-level | app |

Practical implication: a passive attacker sniffing 906 MHz LoRa traffic OR the BLE link sees ciphertext that requires the destination's private key to decrypt. The firmware never sees plaintext message content. The 16-byte destination identity hash IS visible in the clear in normal RNS operation (it's how routing works) — same as any other RNS Interface.

The BLE just-works pairing is the weakest link if someone is physically present at pairing time. For the operator's threat model (personal mesh in a known location) this is acceptable. For higher-threat deployments, switch the firmware to passkey pairing via `CONFIG_CMD` once Phase 7+ plumbs that.

---

## 7. Connection lifecycle

Recommended state machine on the app side:

```
                  +-----------+
                  |  IDLE     |
                  +-----+-----+
                        | user picks node
                        v
                  +-----+-----+
                  | SCANNING  |
                  +-----+-----+
                        | found NUS service
                        v
                  +-----+-----+
                  | CONNECTING|
                  +-----+-----+
                        | services discovered + MTU negotiated + notifications subscribed
                        v
                  +-----+-----+
                  | HANDSHAKE |--+
                  +-----+-----+  |  send REGISTER_IDENTITY
                        |        |  send NODE_INFO_REQ
                        |        |  await CONFIG_REPLY
                        v        |
                  +-----+-----+  |
                  |  ONLINE   |<-+
                  +-----+-----+
                        | (forever) processOutgoing -> CMD_DATA_TX
                        | (forever) CMD_DATA_RX -> rns.transport.inbound
                        |
                        | BLE disconnect (timeout / out of range / app background)
                        v
                  +-----+-----+
                  | RECONNECT |---- retry with backoff (1s, 2s, 4s, 8s, cap 30s)
                  +-----------+
```

### Reconnect detection

If the firmware reboots while connected (battery swap, OTA, watchdog), the BLE connection drops. On reconnect:

- The firmware may have lost the `REGISTER_IDENTITY` state — **always re-send** on connect, don't try to detect "did we already register."
- The firmware's `node_id` is stable across reboots (persisted in `ConfigStore`), so the identity ↔ node_id binding the rest of the mesh learned still applies. As long as the app re-registers within ~4 HELLO cycles (~2 min), `ANNOUNCE_FORWARD` frames resume.

### Background / locked-screen behavior

- iOS: BLE central works in background with `bluetooth-central` background mode, but throughput drops to ~1 message/min. Use this to keep the connection up but expect deliveries to lag.
- Android: foreground service required for reliable BLE central operation in the background. Reticulum messaging apps already do this for `TCPClientInterface` keepalive; reuse the same service.

---

## 8. Discovery UX

Two discovery modes the app should support:

1. **Scan + tap-to-pair.** Standard BLE scan filtered by NUS service UUID (`6E400001-...`). Show a list of `rlm-xxxxxx` devices in range with their advertised manufacturer-data `node_id`. User picks one.
2. **Auto-reconnect to bonded.** On app launch, if a previously-bonded `rlm-xxxxxx` device is in range, connect silently.

The firmware advertises continuously while no host is connected; once connected it stops advertising (only one central at a time). If the connection drops, advertising resumes within ~1 s.

---

## 9. Testing recipe (one board, one phone)

1. Flash a board with `pio run -e XIAO_nRF52840 -t upload` (the recommended pocket node form factor) — see firmware repo's README for the full flash recipe.
2. Power the board; LED blinks heartbeat (~1 Hz). BLE service should be advertising as `rlm-xxxxxx`.
3. App scans → finds the device → connects → subscribes to TX notifications → MTU negotiates to 247.
4. App sends `REGISTER_IDENTITY` with a placeholder hash (e.g., the app's own RNS identity).
5. App sends `NODE_INFO_REQ` → receives `CONFIG_REPLY` with one-line ASCII status. Display `nid=0xNNNN` somewhere in the diag UI.
6. App sends `DUMP_STATE` → receives a stream of `DIAG_EVENT` lines: `DUMP-BEGIN`, optional `NBR ...` / `ROUTE ...` / `IDENT ...` rows (will be empty for a lone node), `DROPS ...`, `AIRTIME ...`, `DUMP-END`.
7. Power a second board. After ~30-60s, repeat the dump — should now show one `NBR` row + one `ROUTE` row for the other board.
8. From the app, call `processOutgoing(testBytes)` where `testBytes` is any random byte sequence. The second board's app instance should receive a `DATA_RX` notification with `src_node=0xNNNN` ‖ `testBytes`.

If step 8 works, the integration is end-to-end functional. Real RNS-encrypted traffic will work the same way — RNS at the app layer just generates more realistic byte patterns.

---

## 10. Open questions for the next agent

1. **Outgoing destination-hash extraction.** Approach (A) all-zero prefix vs. (B) parse the RNS Packet to extract `destination_hash`. Recommend shipping (A) first; revisit once announce flow is verified — see §5.
2. **Multi-node handover.** If the user moves from one node's range into another's, the BLE connection drops and the app reconnects to a different node. The mesh's IdentityMap on the new node may not yet have a route — the app's identity gets re-announced after the next ~4 HELLOs (~2 min). Decide if you want to surface this as "reconnecting to mesh..." UI or just accept the delay silently. Recommend silent — the user shouldn't have to think about which node they're talking to.
3. **Multiple identities per app.** If the user has multiple RNS identities (one per LXMF account), the firmware currently only accepts ONE `REGISTER_IDENTITY`. Decide: pick one as "primary," or extend the firmware's `set_local_identity` to accept up to N. Recommend single-identity for v1; multi-identity is a firmware-side change.
4. **Notification batching.** On Android, BLE notifications can arrive in batches with one `onCharacteristicChanged` per byte under bad conditions. Verify your KISS decoder is per-byte; the reference impl in `host/rns_loramesh.py` is.
5. **iOS background lifetime.** iOS will suspend the BLE connection eventually even with the right entitlements. Plan a "wake on push" mechanism if remote-delivered LXMF messages need to wake the app — that's RNS-layer, not BLE-layer, but worth scoping early.

---

## 11. References

**In the firmware repo** (`reticulum-loramesh`, github.com/thatSFguy/reticulum-loramesh — private, ask the operator for access if you need it):

| Path | What's in it |
|---|---|
| `src/Kiss.{h,cpp}` | Authoritative KISS codec — port to Kotlin/Swift line-for-line. |
| `src/HostLink.cpp` | The current USB-CDC command dispatcher — exact same dispatch logic will move to a BLE-fed pump in Phase 6. |
| `host/rns_loramesh.py` | Working Python reference for the entire protocol, including the streaming decoder. |
| `docs/protocol.md` | On-air mesh wire format (you don't need this — firmware abstracts it — but useful context). |
| `docs/kiss_protocol_comparison.md` | RNode vs ours, explains why stock Sideband won't work and what would. |
| `docs/architecture.md` | System stack diagram. |
| `docs/decisions.md` | Why we route in firmware (D1), why no link-layer encryption (Q9). (D5 "KISS is CRC-protected" was reversed 2026-05-26 — see §0.) |

**Upstream Reticulum:**
- `markqvist/Reticulum` — RNS source. Look at existing `Interface` subclasses (TCPClientInterface, AutoInterface, RNodeInterface) for the contract your subclass must implement.
- `markqvist/Sideband` — current Android source. The interface-registration pattern in `core.py` / equivalents is what you'll be extending.

**Nordic BLE NUS:**
- The standard Nordic UART Service definition: https://infocenter.nordicsemi.com/index.jsp?topic=%2Fcom.nordic.infocenter.sdk5.v15.0.0%2Fble_sdk_app_nus_eval.html
- Adafruit Bluefruit BLEUart Arduino impl (what the firmware uses): https://github.com/adafruit/Adafruit_nRF52_Arduino/tree/master/libraries/Bluefruit52Lib

---

## 12. Coordination with the firmware repo

The mobile-app side spec is locked-in here. The corresponding firmware-side work is **Phase 6 of `docs/roadmap.md` in the firmware repo** — currently deferred pending hardware-side BLE bring-up validation.

If you start the mobile work before firmware Phase 6 lands: you can develop and test against `host/rns_loramesh.py` running on a Pi/laptop as a stand-in. Replace the BLE transport in your `Interface` skeleton with a TCP socket that wraps the USB-CDC link via `socat` (or the `tcp://` URL support in `rns_loramesh.py` once it lands — see the firmware repo's "what's next" notes). The KISS protocol bytes are identical in both transports; only the transport changes.

Coordinate version-pinning of the KISS opcode list with the firmware repo before shipping. New opcodes are easy to add (the `0x80..0xFF` range is reserved for future extensions); changing existing semantics requires a wire-format version bump (`PROTOCOL_VERSION` in firmware `docs/protocol.md`).

---

## 13. Samsung Android BLE compatibility (REQUIRED mitigations)

**Background.** Empirical testing on 2026-05-26 showed the firmware's BLE link stays stable on **Pixel** (stock Android) but drops **exactly 5.04 s after every LoRa TX-DONE** on **Samsung Android**, identically across multiple Samsung models and identical firmware. The firmware was confirmed correct (Pixel proves it). The Samsung BLE stack is the issue.

The mechanism (verified via firmware-side `[BLE[mc]: disconnected]` timestamps):

1. Firmware transmits a LoRa frame (HELLO every ~30 s, plus user-triggered DATA_TX).
2. SX1262 transitions through `RX → STBY → TX → STBY → RX` over ~100-1500 ms (frame size dependent).
3. Some specific feature of Samsung's BLE controller stack — likely related to its hostile conn-param negotiation behavior, OR aggressive supervision-timer behavior under 2.4 GHz environmental noise — interprets the brief post-TX BLE-radio activity as a link failure.
4. **At T+5.04 s after `TX-DONE`, the BLE link drops** with a typical `status=0` to the Android `BluetoothGattCallback.onConnectionStateChange` (Android obscures the actual HCI reason).
5. App-side reconnect takes ~1.5-2 s (depending on phone state and bond status).

Net: with default 30 s HELLO cadence, a Samsung user sees the connection cycle 7-8 s after every HELLO TX. The app barely settles before the next drop. **Without the mitigations below, the app is unusable on Samsung.**

### Mandatory mitigations

**M1: Foreground service while connected.** Android (especially Samsung's variant) aggressively suspends BLE-using processes in background. Run a foreground service for the duration of the BLE session, with a persistent notification. This alone resolves ~50% of Samsung's BLE instability for many apps.

```kotlin
class BleConnectionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("rlm-mesh connected")
            .setSmallIcon(R.drawable.ic_ble)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        // start BLE work here
        return START_STICKY
    }
}
```

**M2: `gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` immediately after services discovered + bonded.** Samsung's stack honors `CONNECTION_PRIORITY_HIGH` (11.25-15 ms interval, 0 latency, 20 s supervision) more reliably than it honors peripheral PPCP. This is the single most important Samsung mitigation. Even if the central later renegotiates, the initial state is what matters during the first few minutes when announces fly.

```kotlin
override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    super.onServicesDiscovered(gatt, status)
    if (status == BluetoothGatt.GATT_SUCCESS) {
        // Critical for Samsung. Call as early as possible.
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        // ...subscribe to TX notifications, etc.
    }
}
```

**M3: Aggressive auto-reconnect with backoff.** The Samsung BLE cycle (drop → reconnect 7 s later → drop again after next TX) is the reality the app has to live with until the firmware can rate-limit announces or Samsung's stack improves. Make the reconnect transparent to the user:

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        // Schedule reconnect with exponential backoff (start 500ms, max 8s).
        val delay = minOf(500L * (1 shl reconnectAttempts), 8_000L)
        handler.postDelayed({ gatt.connect() }, delay)
        reconnectAttempts++
    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
        reconnectAttempts = 0
        gatt.discoverServices()
    }
}
```

Do NOT `gatt.close()` and reopen the GATT — `gatt.connect()` on the existing instance is faster and reuses the bond.

**M4: Detect Samsung specifically and surface a UI warning.** Until the stack improves, set user expectations:

```kotlin
fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
// In UI: if isSamsung(), show "Samsung BLE may briefly disconnect when mesh data is sent.
// Reconnection is automatic." with a link to settings to disable battery optimization
// for this app.
```

**M5: Disable Android battery optimization for the app.** Samsung's "Adaptive Battery" + "Background usage limits" aggressively kills BLE-holding apps. Prompt the user once to whitelist the app:

```kotlin
private fun requestIgnoreBatteryOptimizations() {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
    startActivity(intent)
}
```

Surface this as part of the first-run setup wizard, not as an unexplained system dialog.

**M6: MTU exchange on connect.** Request 247-byte MTU explicitly. Samsung's default is often 23, which fragments KISS frames excessively:

```kotlin
override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    gatt.requestMtu(247)  // Samsung may agree to 185 — fine.
}
override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    Log.d(TAG, "MTU negotiated: $mtu")
    // proceed with subscription, etc.
}
```

### Recommended (not strictly required)

**M7: Surface BLE drop counter in diag UI.** Let advanced users see how often the link is cycling. Helps Samsung-specific bug triage.

**M8: Coalesce outbound DATA_TX writes when a drop is imminent.** If the app has a queue of outbound RNS packets and a drop has just happened, write them in tight succession upon reconnect rather than dripping them out. Reduces the per-TX BLE-disruption count from the firmware side.

**M9: Test on Pixel first.** When debugging the app, always reproduce on a Pixel before declaring a Samsung-side issue resolved. Pixel is the closest thing to "stock Android" available for BLE testing and gives a clean baseline.

### What the firmware will NOT do

For completeness, here's what we tried in firmware that did NOT help Samsung — so the app dev doesn't ask:

- Increasing supervision timeout PPCP from 200 → 600 (Samsung overrides to 500 anyway).
- Lowering TX power from 22 dBm to 10 dBm (5.04 s drop pattern unchanged — RF coupling is not the cause).
- Removing all per-frame Serial.print logging (helped marginally, didn't fix).
- Switching to MeshCore's vendored radio + BLE driver stack (mesh works, Samsung BLE still drops the same way).
- Early-return from main loop during TX-in-flight (matches MeshCore's discipline exactly; Samsung still drops).
- Async LoRa TX with proper TX_DONE IRQ handling (works correctly; Samsung still drops).
- `BLE_GAP_EVT_CONN_PARAM_UPDATE_REQUEST` handler that accepts central counter-proposals (Samsung still drops).
- `setRxCallback` with deferred Bluefruit-task context (helped marginally; Samsung still drops).
- Removed the KISS CRC-16 trailer (clean integration win; Samsung still drops).

The firmware has been tuned exhaustively. The remaining 5.04 s post-TX disconnect is a Samsung BLE stack characteristic, not a fixable firmware bug.
