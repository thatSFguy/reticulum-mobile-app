# CLAUDE.md — Reticulum Mobile App (Kotlin Multiplatform)

## What this project is

A native Android (and future iOS) messaging client for the Reticulum network, built in Kotlin Multiplatform (KMP). It replaces the browser-based webclient at `../reticulum-lora-webclient/` with a real native app that can maintain BLE connections in the background, fire system notifications on incoming messages, and run a foreground service for persistent mesh monitoring.

The protocol logic is identical to the webclient — this is a port, not a reimplementation. Every packet format, crypto construction, and handshake sequence matches the existing JavaScript version which has been tested against the Python RNS reference implementation. The `reference/` directory contains the original JS source files and test vectors to verify the Kotlin port against.

## Scope rule

This project lives in `reticulum-mobile-app/`. You may read files from the sibling `reticulum-lora-webclient/` project for reference but should not modify them.

## Authoritative protocol reference: `../reticulum-specifications/`

The sibling `reticulum-specifications/` repo is the **first place to look** for any wire-format / routing / framing question — not the JS webclient, not the upstream Python source, not the test vectors. Its `SPEC.md` is the spec the protocol stack must conform to, with section numbers (§1.2 destination hash, §2.1 flag byte layout, §2.3 originator HEADER conversion, §6 Link, §10 Resource, §11 REQUEST/RESPONSE, §12 transport-relay behaviour, etc.) cross-referenced from upstream Python source lines.

**Before debugging any "X works but Y doesn't" bug**, especially anything involving routing, framing, or transit forwarding, do this:

1. Identify which spec section governs the byte path the failing packet should take (likely §2 packet header, §6 link, §10 resource, §11 request/response, or §12 transport relay).
2. Read that section. Compare every byte / flag / context value the spec mandates against what our code actually produces or accepts.
3. Then turn to logs and a reproducer.

Recent bugs that were a single spec section away the whole time:

- **§2.3** — outbound DATA / LINKREQ to a multi-hop destination must be HEADER_2 with a transport_id; HEADER_1 dies on the relay's dedup hashlist (v0.1.40, v0.1.43).
- **§11.1** — REQUEST `path_hash` is the **16-byte** truncation of `SHA256(path)`, not the full 32 bytes; servers key handler dicts on the 16-byte form (v0.1.42).
- **§12.5.2** — packets addressed to a `link_id` must have `dest_type = LINK`; otherwise the relay's `link_table` lookup never fires and the packet is dropped (v0.1.45).

In each case the bug had been "obvious" for hours of log-staring and was visible in a paragraph of `SPEC.md` that took two minutes to read. The specs repo is the cheapest debugging tool we have. Use it first.

When the spec is silent or ambiguous, then fall back to the JS webclient + upstream Python — but call out the discrepancy in commit messages so it can be promoted into the spec repo's `todo.md`.

## Architecture overview

### Kotlin Multiplatform structure

```
shared/          — KMP module, compiles to both Android (JVM) and iOS (Native)
  commonMain/    — All protocol logic, crypto interfaces, storage interfaces
  androidMain/   — Android-specific: BluetoothGatt BLE, JCA/BouncyCastle crypto, Room storage
  iosMain/       — iOS-specific: CoreBluetooth BLE, CryptoKit crypto, SQLDelight storage

androidApp/      — Android application: Compose UI, foreground service, notifications
iosApp/          — iOS application: SwiftUI (or Compose Multiplatform), background modes
```

### What goes where

**commonMain (platform-independent Kotlin):**
- `protocol/` — Reticulum packet header encode/decode, constants (MTU, hash lengths, etc.)
- `crypto/` — Identity class (Ed25519 + X25519 keypair), destination hash computation, name hash computation. Crypto OPERATIONS (ECDH, HKDF, AES-CBC, HMAC, Ed25519 sign/verify) are declared as `expect` interfaces here and implemented per-platform in androidMain/iosMain.
- `announce/` — Build and parse Reticulum announces, signature validation, display name extraction from msgpack app_data, known-destinations lookup table
- `lxmf/` — LXMF message pack/unpack, signature verification, opportunistic and link-delivered wire formats
- `link/` — Reticulum Link protocol: LINKREQUEST validation (responder), link creation (initiator), LRPROOF signing, link_id derivation, signalling byte encode/decode, session key derivation
- `transport/` — KISS frame encode/decode, HDLC frame encode/decode (for the rnsd WebSocket path)
- `store/` — Data models and repository interfaces for identity, contacts, messages, nodes. Declared as interfaces; implemented per-platform.

**androidMain:**
- `platform/CryptoProvider.kt` — `actual` implementations using `java.security` (Ed25519 via EdDSA provider on API 33+ or Bouncy Castle fallback), `javax.crypto` (AES/CBC/PKCS5Padding, HmacSHA256), HKDF via `javax.crypto.SecretKeyFactory` with HkdfKeySpec or manual implementation
- `platform/BleTransport.kt` — Android `BluetoothGatt` + `BluetoothGattCallback` wrapping NUS service UUIDs. Handles scan, connect, GATT service discovery, characteristic write/notify, MTU negotiation, disconnect detection.
- `platform/Storage.kt` — Room database with DAOs for identity, contacts, messages, nodes

**iosMain:**
- `platform/CryptoProvider.kt` — `actual` implementations bridging to Apple CryptoKit (Curve25519 signing/agreement, AES.GCM or CommonCrypto for AES-CBC, HMAC)
- `platform/BleTransport.kt` — CoreBluetooth `CBCentralManager` + `CBPeripheralDelegate`
- `platform/Storage.kt` — SQLDelight generated code

### Transport chain

```
Android app ──► BluetoothGatt (NUS) ──► RNode firmware (KISS) ──► SX1262 ──► LoRa RF
    │
    ├──► TCP socket (HDLC) ──► rnsd TCPServerInterface ──► any Reticulum network
    │       e.g. RNS.MichMesh.net:7822
    │
    └──► USB Serial (Android USB Host API) ──► RNode firmware (KISS) ──► LoRa RF
```

All transports plug into the shared `Transport` interface in
`commonMain/transport/Transport.kt`, which exposes raw Reticulum
packets in/out plus optional RSSI/SNR sidecar. The packet router and
the rest of the protocol stack only see this interface; they never
care whether bytes came in over BLE, USB, or TCP.

## Reticulum protocol reference

### Packet header (minimum 19 bytes)

```
Byte 0 (flags):
  bits 7-6: header_type (0=HEADER_1, 1=HEADER_2 transport)
  bit 5:    context_flag (on announces: 1 = ratchet present)
  bit 4:    transport_type (0=broadcast, 1=transport)
  bits 3-2: destination_type (0=SINGLE, 1=GROUP, 2=PLAIN, 3=LINK)
  bits 1-0: packet_type (0=DATA, 1=ANNOUNCE, 2=LINKREQUEST, 3=PROOF)
Byte 1: hop count
Bytes 2-17: destination_hash (16 bytes) [HEADER_1]
  OR Bytes 2-17: transport_id (16), Bytes 18-33: destination_hash (16) [HEADER_2]
Next byte after dest: context
Remaining: payload
```

### Constants

```
HEADER_1 = 0x00, HEADER_2 = 0x01
PACKET_DATA = 0x00, PACKET_ANNOUNCE = 0x01, PACKET_LINKREQ = 0x02, PACKET_PROOF = 0x03
DEST_SINGLE = 0x00, DEST_GROUP = 0x01, DEST_PLAIN = 0x02, DEST_LINK = 0x03
TRUNCATED_HASHLENGTH = 16 bytes (128 bits)
NAME_HASH_LENGTH = 10 bytes (80 bits)
KEYSIZE = 64 bytes (32 X25519 pub + 32 Ed25519 pub)
SIGLENGTH = 64 bytes (Ed25519 signature)
MTU = 500 bytes
```

### Identity

- Public key = X25519_pub (32 bytes) || Ed25519_pub (32 bytes) = 64 bytes
- identity_hash = SHA-256(public_key)[:16]
- name_hash = SHA-256("lxmf.delivery")[:10]
- destination_hash = SHA-256(name_hash || identity_hash)[:16]

IMPORTANT: The `expand_name` function in upstream Reticulum uses `identity=None` for computing name_hash, so the identity's hex hash is NOT part of the name_hash input. Only the plain string "lxmf.delivery" is hashed. The hex hash appears in the human-readable Destination.name but never in on-wire hashes. This was a bug we fixed in the webclient — see reference/PROTOCOL_NOTES.md §1.

### Announce format

```
payload = public_key(64) + name_hash(10) + random_hash(10) + [ratchet(32) if context_flag] + signature(64) + app_data
signed_data = dest_hash(16) + public_key(64) + name_hash(10) + random_hash(10) + [ratchet(32)] + app_data
```

Signature is Ed25519, signing key is the last 32 bytes of the 64-byte public_key.

LXMF announces carry `app_data` as msgpack: `[display_name_bytes, stamp_cost]` or sometimes just raw UTF-8. Try msgpack decode first, fall back to UTF-8. See `reference/js-reference/announce.js` extractDisplayName().

### Crypto: Token encrypt/decrypt (modified Fernet)

Encryption (opportunistic LXMF):
1. Generate ephemeral X25519 keypair
2. ECDH: shared = X25519(ephemeral_priv, recipient_pub)
3. HKDF-SHA256(shared, salt=recipient_identity_hash, info=empty, len=64) → signing_key(32) + encryption_key(32)
4. Generate random IV (16 bytes)
5. AES-256-CBC encrypt plaintext with encryption_key and IV
6. HMAC-SHA256(signing_key, IV + ciphertext)
7. Token = ephemeral_pub(32) + HMAC(32) + IV(16) + ciphertext

Decryption is the reverse. HMAC is verified BEFORE decryption (encrypt-then-MAC, prevents padding oracle attacks).

CRITICAL GOTCHA: Do NOT manually PKCS#7-pad before calling the platform AES-CBC API. Both Web Crypto (JS) and JCA (Android javax.crypto with "AES/CBC/PKCS5Padding") auto-pad. Manual padding on top causes double-padding which adds 16 garbage bytes to the plaintext. This was a real bug in the webclient — see reference/PROTOCOL_NOTES.md §2.

### LXMF message format

Opportunistic (single-packet):
```
plaintext = source_hash(16) + signature(64) + msgpack([timestamp, title, content, fields])
```

Link-delivered (over an established Reticulum Link):
```
container = dest_hash(16) + source_hash(16) + signature(64) + msgpack([timestamp, title, content, fields])
```

The `source_hash` is the SENDER's destination hash (not identity hash). The signature covers everything after the signature field. For verification, the signer's Ed25519 public key comes from the sender's identity (looked up by source_hash in the contacts table).

GOTCHA: LXMF signature verification must try BOTH the "stripped" msgpack (re-encoded from the decoded array) and the original raw msgpack bytes, because different msgpack encoders produce slightly different binary representations for the same data. The signature was computed by the sender over THEIR encoder's output. See reference/js-reference/lxmf.js verifyMessageSignature().

### Reticulum Link protocol

The Link protocol establishes an encrypted bidirectional channel. Full details in reference/js-reference/link.js and reference/PROTOCOL_NOTES.md §10-13.

Responder flow (we receive a LINKREQUEST addressed to our destination):
1. Parse LINKREQUEST payload: peer_pub_x25519(32) + peer_pub_ed25519(32) + [signalling(3)]
2. Generate our own ephemeral X25519 keypair
3. ECDH: shared = X25519(our_ephemeral_priv, peer_pub_x25519)
4. link_id = SHA-256(full_packet_hash of the LINKREQUEST)[:16]
5. HKDF(shared, salt=link_id, info=empty, len=64) → signing_key(32) + encryption_key(32)
6. Build LRPROOF: link_id(16) + our_ephemeral_x25519_pub(32) + Ed25519_sign(signed_data) + [signalling(3)]
7. signed_data = link_id + our_ephemeral_x25519_pub + our_long_term_ed25519_pub + [signalling]
8. Send LRPROOF as a PROOF packet with context=0xFF, dest=requestor_transport_id or link_id

Initiator flow (we send a LINKREQUEST to a known destination):
1. Generate ephemeral X25519 AND Ed25519 keypairs (both ephemeral for initiator)
2. Build LINKREQUEST: ephemeral_x25519_pub(32) + ephemeral_ed25519_pub(32) + signalling(3)
3. Send as PACKET_LINKREQ to the target's destination hash
4. Receive LRPROOF, extract responder's ephemeral X25519 pub
5. ECDH + HKDF to derive session keys (same as responder but from initiator's perspective)
6. Verify LRPROOF signature using the responder's long-term Ed25519 pub

IMPORTANT: link_id derivation uses the SHA-256 of the FULL PACKET (including the Reticulum header) of the LINKREQUEST, not just the payload. See computePacketFullHash() in reference/js-reference/link.js.

### KISS framing (BLE / Serial path)

```
FEND = 0xC0, FESC = 0xDB, TFEND = 0xDC, TFESC = 0xDD
Frame = FEND + cmd + escaped_data + FEND
CMD_DATA = 0x00 (first byte after FEND for received/sent radio packets)
```

The RNode prefixes each received packet with CMD_STAT_RSSI + CMD_STAT_SNR frames before the CMD_DATA frame. Parse RSSI = byte - 157, SNR = (signed byte) / 4.0.

BLE NUS splits KISS frames across multiple BLE notifications. The parser must accumulate bytes and emit complete frames on FEND boundaries.

### HDLC framing (TCP / rnsd path)

```
FLAG = 0x7E, ESC = 0x7D, ESC_MASK = 0x20
Frame = FLAG + escaped_data + FLAG
Escape: 0x7E → 0x7D 0x5E,  0x7D → 0x7D 0x5D
```

Same escape logic as KISS but different byte values. Used by the
direct-TCP path to a Reticulum transport node (e.g. an rnsd
`TCPServerInterface` such as `RNS.MichMesh.net:7822`). Source of
truth: `RNS/Interfaces/TCPInterface.py` class `HDLC`.

Unlike KISS there is no command-byte prefix and no RSSI/SNR sidecar
— the HDLC payload IS the raw Reticulum packet.

### TCP transport (direct rnsd attachment)

`commonMain/transport/TcpInterface.kt` lets the user attach the app
to any rnsd `TCPServerInterface` over the internet, bypassing the
RNode entirely. Wire protocol is the entire spec:

- Open a plain TCP socket to `host:port`.
- Every byte sent or received is HDLC-framed Reticulum.
- No upgrade handshake, no auth, no TLS.

Settings UI should expose this as a "Connect over Internet" option
distinct from the BLE/USB RNode path. Persist host+port in the
identity table and let the user pick a default. A list of known
public transport nodes can be hard-coded as suggestions
(`RNS.MichMesh.net:7822` etc.) but the user must always be able to
type a custom host:port pair.

What to surface in the UI when TCP is selected:
- "You are connected to a remote transport node. Anyone running that
  node can observe your announces and destination hash" — this is
  true of any RNS attachment but worth saying out loud since LoRa
  users have a stronger off-grid intuition.
- No RSSI/SNR will appear on incoming messages — the chat metadata
  line should hide those fields when the source transport reports
  them as null.
- Hop counts will typically be > 1 — the contact list will fill up
  with destinations from the entire connected mesh, not just the
  user's RF neighborhood. Consider a "show only N hops" filter.

Implementation notes:
- `TcpSocket` is `expect class` with a JDK `java.net.Socket`-based
  Android actual and a TODO iOS actual (use `NWConnection` from the
  Network framework when filling it in).
- The read loop runs as a child coroutine of the scope passed to
  `TcpInterface`. Cancelling the scope or calling `disconnect()`
  closes the socket; the JDK unblocks the blocking `read()` on
  socket close, which is how cancellation propagates.
- No reconnect logic in `TcpInterface` itself — that belongs at the
  app/service layer next to the BLE reconnect supervisor.

### NUS BLE UUIDs

```
Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
TX (write to device):  6e400002-b5a3-f393-e0a9-e50e24dcca9e
RX (notify from device): 6e400003-b5a3-f393-e0a9-e50e24dcca9e
```

### Known destination name_hashes

Pre-computed SHA-256(name)[:10] for common Reticulum services:

```
6ec60bc318e2c0f0d908  lxmf.delivery
e03a09b77ac21b22258e  lxmf.propagation
213e6311bcec54ab4fde  nomadnetwork.node
0ad8bff9ff75737c058e  nomadnetwork.gossip
28f44518c0b20af50215  nomadnetwork.gossip.conversation
9efb9c771eeb5ae90ea6  rnstransport.broadcasts
4848a053c16415bed6c8  rnstransport.remote.management
3eea23374d2a3aedf2cc  rlr.telemetry
```

## Key bugs we found and fixed in the webclient (do not re-introduce)

1. **Destination hash formula**: `expand_name(None, app_name, *aspects)` does NOT include the identity hex hash. Only the plain name string is hashed for name_hash. Early code wrongly included it.

2. **PKCS#7 double padding**: Platform AES-CBC APIs auto-pad. Do NOT manually pad before calling them.

3. **LXMF source_hash is dest_hash, not identity_hash**: The 16-byte source_hash in an LXMF message body is the sender's DESTINATION hash (SHA-256(name_hash + identity_hash)[:16]), not the raw identity hash.

4. **Clockless sender timestamps**: LoRa nodes without an RTC send time.time() as seconds-since-boot (small numbers like 30, 90720). Treat any timestamp before 2020-01-01 as "no clock" and substitute the local receive time.

5. **LXMF signature verification — msgpack variant**: Try verifying against both the stripped (re-encoded) and original (raw) msgpack bytes. Different encoders produce different binary output for the same logical data.

6. **Link packet receipts are mandatory**: After receiving a CONTEXT_NONE data packet on an active link, immediately send a PROOF packet with the 32-byte SHA-256 of the received packet's hashable part. Without this, the sender's retry queue fires and the same message arrives repeatedly.

7. **Periodic re-announce is mandatory**: Relay identity caches expire. Without re-announcing every ~5 minutes, inbound link deliveries fail because relays can't verify LRPROOFs.

8. **Announce filtering by name_hash**: Only `lxmf.delivery` announces go in the contacts list. Everything else (rlr.telemetry, nomadnetwork.node, etc.) goes in the nodes list. The 10-byte name_hash field in the announce distinguishes them.

## Android-specific implementation notes

### Foreground service for background BLE

The primary reason for the native rewrite. The service should:
- Run as a persistent foreground service with a notification ("Reticulum — listening for messages")
- Own the BluetoothGatt connection independently of any Activity lifecycle
- Run the KISS parser on incoming BLE notifications
- Check each Reticulum packet's destination_hash against the user's dest_hash (16-byte comparison)
- When a match is found, fire an Android notification with sound + vibration
- Buffer matched packets; when the Activity binds to the service, drain the buffer into the UI
- Handle Doze mode: request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or guide the user to exempt the app
- Handle BLE reconnection if the RNode drops (scan + auto-reconnect with exponential backoff)

### Bluetooth permissions (Android 12+)

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

### Compose UI screens

Four main screens matching the webclient:
1. **Messages** — contact list + conversation view (two-pane on tablets, navigation on phones)
2. **Nodes** — map (osmdroid with OpenStreetMap tiles) + node list sidebar, telemetry parsing
3. **Settings** — connect, identity, radio config, appearance (theme), help, about
4. **Map** — same as Nodes but could be a separate full-screen view

Bottom navigation bar with Messages / Nodes / Settings tabs.

### Theme

Support both light and dark themes. Light theme uses the webclient's warm beige palette (#eeece6 background, #1D9E75 accent). Dark theme uses the webclient's original dark palette (#0f1115 background, #5eb0ff accent). Follow Material 3 dynamic theming where possible.

### App signing and distribution

The webclient's Capacitor APK uses GitHub Actions for automated builds. The same pattern works here: push an `android-v*` tag, Actions builds a signed APK/AAB, attaches it to a GitHub release. The signing keystore already exists (base64-encoded in the webclient repo's GitHub secrets). Consider a fresh keystore for this app since it's a different package ID.

Target package ID: `io.github.thatsfguy.reticulum.native` (or drop the `.native` suffix if this fully replaces the Capacitor app).

## Implementation order (recommended)

1. **Protocol layer** (commonMain) — packet parsing, constants. Verify with test vectors.
2. **Crypto** (expect/actual) — Identity, ECDH, HKDF, AES-CBC, HMAC, Ed25519. Verify with test vectors.
3. **KISS + HDLC** (commonMain) — frame encode/decode. Byte-level tests.
4. **Announce** (commonMain) — build/parse/validate. Verify against test vector announce packet.
5. **LXMF** (commonMain) — unpack/pack messages. Verify against test vector LXMF packet.
6. **Storage** (expect/actual) — Room on Android, SQLDelight on iOS. Identity, contacts, messages, nodes.
7. **BLE transport** (androidMain) — BluetoothGatt NUS. Connect to a real RNode and see raw packets.
8. **Basic Compose UI** — Messages + Settings screens, connect button, log view.
9. **Message send/receive** — encrypt/decrypt, retry queue, delivery receipts.
10. **Link protocol** — responder + initiator. Verify LRPROOF against test vectors.
11. **Foreground service** — background BLE, packet buffering, notifications.
12. **Nodes + Map** — telemetry parsing, osmdroid map, marker popups.
13. **Polish** — theme, onboarding, about, export/import identity.

## Test vectors

The file `reference/test-vectors.json` contains a full round-trip test output from the webclient's test harness. It includes:
- Alice and Bob identity key material (private + public + ratchet)
- A signed announce packet (hex) with display name and ratchet
- An encrypted LXMF packet (hex) from Alice to Bob
- A LINKREQUEST packet, derived link_id, LRPROOF packet, and signed_data
- A full link handshake with derived session keys and a test ciphertext/plaintext pair

Use these to verify each Kotlin module as you port it. If your Kotlin ECDH + HKDF + AES-CBC produces the same ciphertext as the test vector, the crypto layer is correct.

## Dependencies (recommended)

### Android (JVM)
- `org.bouncycastle:bcprov-jdk18on` — Ed25519, X25519 (or use JDK 17+ EdDSA if minSdk allows)
- `androidx.room:room-runtime` + `room-ktx` — SQLite storage
- `org.jetbrains.kotlinx:kotlinx-serialization-json` — JSON serialization
- `com.squareup.okio:okio` — byte buffer utilities (optional, Kotlin stdlib may suffice)
- `org.osmdroid:osmdroid-android` — OpenStreetMap map view
- `com.github.nicnordic:msgpack-kotlin` or similar — msgpack decode for LXMF app_data

### iOS (via CocoaPods or SPM)
- CryptoKit (built-in) — Ed25519, X25519, AES-GCM (AES-CBC via CommonCrypto)
- CoreBluetooth (built-in) — BLE
- MapKit (built-in) — map view

### KMP shared
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — async operations
- `org.jetbrains.kotlinx:kotlinx-datetime` — cross-platform timestamp handling
- `app.cash.sqldelight` — cross-platform SQLite (alternative to Room for shared storage)

## Sibling projects for reference

- `../reticulum-lora-webclient/` — the existing browser/Capacitor client being replaced. All protocol logic is here in JS.
- `../reticulum-rnode/` — the RNode firmware. Key files: `src/Ble.cpp` (BLE NUS), `src/Kiss.cpp` (KISS framing).
- `../reticulum-lora-repeater/` — repeater firmware. Shows how telemetry and LXMF presence announces are built.
- `../microReticulum_Faketec_Repeater/` — C++ microReticulum implementation. Good reference for Link protocol byte layout.
