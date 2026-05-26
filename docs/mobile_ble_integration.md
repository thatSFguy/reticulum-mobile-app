# Mobile-App ↔ reticulum-loramesh: BLE Integration Spec

**Hand-off doc for the mobile-app repo / agent.**

This document specifies how a mobile Reticulum app (e.g., a fork of Sideband / a fresh build) connects to a reticulum-loramesh firmware node over **BLE GATT** and integrates as a Reticulum `Interface` in the app's existing RNS instance.

Self-contained — you do not need the firmware repo to implement against this. References to firmware source paths are provided as ground truth if you need to verify a wire-format detail.

---

## 1. What you're connecting to

`reticulum-loramesh` is a custom firmware for nRF52840 / ESP32 LoRa boards that:

- Implements a **distance-vector mesh router with link-quality (SNR-weighted) routing** in firmware.
- Presents itself to a host as a **KISS-framed link** where every destination on the mesh appears one RNS-hop away (the mesh does its own multi-hop routing under the surface).
- Speaks a **custom KISS dialect** — same FEND/FESC framing as standard KISS, but with a CRC-16/CCITT and a different command set. Crucially, this is **not** RNode-compatible — stock `RNodeInterface` will not work against it.
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
| Min connection interval | 15 ms | Sub-100ms first-byte latency for messaging UX |
| Max connection interval | 30 ms | Bounded power cost |
| Slave latency | 0 | Snappy bidirectional |
| Supervision timeout | 4000 ms | Tolerate brief RF dropouts without dropping the connection |

The firmware accepts whatever the central requests; these are recommendations. For background / locked-screen battery saving, the firmware will request the central re-negotiate to `100–250 ms` interval + `latency 4` after 60 s of host idleness. The app should accept.

### Pairing / bonding

**Passkey-Entry pairing is the default**, using a static 6-digit passkey stored on the firmware. The phone's OS prompts the user to enter the digits at first pair. The factory default passkey is **`123456`** — operators are expected to change it in production via `CMD_SET_BLE_PASSKEY` (opcode `0x09`, payload = 6 ASCII digits; empty payload reverts to Just-Works).

Implications for the mobile app:

- The OS handles the pairing prompt automatically (Android: `ACTION_PAIRING_REQUEST` intent; iOS: CoreBluetooth shows the OS-level passkey dialog). You don't need to render your own UI for the digit entry — the phone OS does.
- After successful pair, the OS stores the bond on its side; the firmware stores it on its side. Subsequent connects skip the pairing dance and re-establish the encrypted channel from cached keys.
- The resulting BLE link is **Level-3 security** (encrypted AES-CCM + MITM-protected). The passive-listener-during-pairing vector that Just-Works leaves open is closed.
- If the operator changes the passkey on the firmware, existing bonds keep working (their stored keys still authenticate). New devices will need to enter the new passkey. To force re-pair on a specific device, clear the bond in the phone's OS Bluetooth settings.

For "just plug-and-play with no PIN" deployments, the firmware can be configured (via `CMD_SET_BLE_PASSKEY` with empty payload) to revert to Just-Works — but this is **not the default** anymore and weakens the threat model. Recommend keeping Passkey-Entry on.

---

## 3. Protocol over BLE: KISS

Once the BLE connection is up and the app has subscribed to the TX characteristic's notifications, **the byte stream in both directions is exactly the firmware's KISS dialect.** No additional BLE-level framing.

### Framing rules

Standard KISS framing:

```
FEND  CMD  ...escape-encoded-body...  CRC16-hi  CRC16-lo  FEND
```

| Symbol | Value |
|---|---|
| FEND  | `0xC0` |
| FESC  | `0xDB` |
| TFEND | `0xDC` |
| TFESC | `0xDD` |

Escape rules applied to the body (CMD + payload + CRC):

- `0xC0` byte → `0xDB 0xDC`
- `0xDB` byte → `0xDB 0xDD`

CRC-16/CCITT-FALSE, computed over `(CMD || payload)` **before** escape encoding, two bytes big-endian:

- Polynomial: `0x1021`
- Initial value: `0xFFFF`
- No input/output reflection
- Final XOR: `0x0000`

A reference Kotlin implementation:

```kotlin
fun crc16Ccitt(data: ByteArray): Int {
    var crc = 0xFFFF
    for (b in data) {
        crc = crc xor ((b.toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else (crc shl 1)
            crc = crc and 0xFFFF
        }
    }
    return crc
}
```

Maximum decoded frame size (CMD + payload + 2-byte CRC) is **512 bytes**. Anything larger is dropped with `BAD_LENGTH`.

### Streaming decoder state machine

On the app side, byte-by-byte (you'll feed bytes from each BLE notification through this):

```
IDLE       -- discard until first FEND, then → IN_FRAME (empty buf)
IN_FRAME   -- on FEND   → finalize: if len < 3 ignore (empty frame is a sync artifact);
                          else split off last 2 bytes as CRC, validate against body,
                          deliver (CMD, payload[1..]) on match; → IDLE.
              on FESC   → ESCAPED
              else      → append byte to buf (overflow at 512 → flag BAD_LENGTH, → IDLE)
ESCAPED    -- on TFEND  → append 0xC0 → IN_FRAME
              on TFESC  → append 0xDB → IN_FRAME
              else      → flag BAD_ESCAPE, → IDLE
```

Reference implementation: `host/rns_loramesh.py::KissDecoder` in the firmware repo.

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

```
+-----------+--------- ... ---------+
| src_node  |   reticulum_bytes...  |
| [2] BE    |   (the encrypted RNS  |
|           |    Packet from peer)  |
+-----------+--------- ... ---------+
```

`src_node` is the 2-byte node_id of the mesh node that originated the packet. The app does NOT need to use this — RNS doesn't care about mesh node_ids. It exists for diagnostics and for the firmware-side IdentityMap learning. The app should:

1. Strip the 2-byte prefix.
2. Pass `reticulum_bytes` directly to `RNS.Transport.inbound(packet, interface=self)` (or equivalent for your RNS port).

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

    // KISS encode (FEND/FESC + CRC16/CCITT) and write to the firmware's RX char.
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
- **Tolerate `BAD_CRC` / `BAD_LENGTH`** at the decoder level — log them, do not disconnect on a single bad frame.

---

## 6. Encryption boundaries

The firmware **is not part of Reticulum's security boundary.** It moves opaque bytes.

| Layer | Encryption | Handled by |
|---|---|---|
| BLE transport | optional just-works AES-CCM | BLE stack (mobile OS + firmware) |
| KISS frame | CRC for integrity (NOT auth) | firmware + app |
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
| `docs/decisions.md` | Why our KISS is CRC-protected (D5), why we route in firmware (D1), why no link-layer encryption (Q9). |

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
