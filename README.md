# Reticulum Mobile App

Native Android (Kotlin Multiplatform) client for the Reticulum LoRa mesh network. Replaces the [browser-based webclient](../reticulum-lora-webclient/) with a real native app that maintains BLE connections in the background, fires system notifications on incoming messages, and runs a foreground service for persistent mesh monitoring.

## Status

**Not yet buildable.** This project is scaffolded with the full directory structure, stubbed Kotlin modules with detailed doc comments, Gradle build configuration, and comprehensive protocol documentation. The actual implementation is pending. See `CLAUDE.md` for the full build plan and implementation order.

## What's here

| Path | Description |
|------|-------------|
| `CLAUDE.md` | Complete project guide: architecture, protocol reference, known bugs, implementation order, dependencies |
| `reference/` | JS source files from the webclient (for porting reference), protocol notes, test vectors |
| `shared/src/commonMain/` | KMP shared module stubs: protocol, crypto, announce, LXMF, link, transport, storage |
| `shared/src/androidMain/` | Android platform stubs: BLE transport, crypto provider, storage |
| `shared/src/iosMain/` | iOS platform stubs (placeholder) |
| `androidApp/` | Android application: Compose UI stubs, foreground service stub, manifest, resources |
| `iosApp/` | iOS application (placeholder) |
| `build.gradle.kts` | Root Gradle build with KMP + Android + Compose plugins |

## Architecture

```
shared/commonMain/     ← Protocol logic (write once, both platforms)
  ├── protocol/        Packet header encode/decode, constants
  ├── crypto/          Identity, ECDH, HKDF, AES-CBC, HMAC, Ed25519 (CryptoProvider interface)
  ├── announce/        Build/parse/validate announces, known destinations
  ├── lxmf/            LXMF message pack/unpack, signature verification
  ├── link/            Reticulum Link protocol (responder + initiator)
  ├── transport/       KISS + HDLC frame encode/decode
  └── store/           Data models + repository interfaces

shared/androidMain/    ← Android implementations (expect/actual)
  └── platform/        JCA/BouncyCastle crypto, BluetoothGatt BLE, Room storage

androidApp/            ← Android UI + lifecycle
  ├── ui/screens/      Compose screens: Messages, Nodes, Settings, Map
  ├── service/         Foreground service for background BLE monitoring
  └── MainActivity.kt  Entry point
```

## Implementation order

1. Protocol layer (commonMain) — packet parsing, constants
2. Crypto (expect/actual) — Identity, ECDH, HKDF, AES-CBC, HMAC, Ed25519
3. KISS + HDLC (commonMain) — frame encode/decode
4. Announce (commonMain) — build/parse/validate
5. LXMF (commonMain) — unpack/pack messages
6. Storage (expect/actual) — Room on Android
7. BLE transport (androidMain) — BluetoothGatt NUS
8. Basic Compose UI — Messages + Settings
9. Message send/receive — encrypt/decrypt, retry queue
10. Link protocol — responder + initiator
11. Foreground service — background BLE, notifications
12. Nodes + Map — telemetry, osmdroid

## Related projects

- [reticulum-lora-webclient](../reticulum-lora-webclient/) — the browser-based client this replaces
- [reticulum-rnode](../reticulum-rnode/) — RNode firmware
- [reticulum-lora-repeater](../reticulum-lora-repeater/) — repeater firmware
- [markqvist/Reticulum](https://github.com/markqvist/Reticulum) — upstream Python RNS
