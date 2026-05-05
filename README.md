# Reticulum Mobile App

Native Android (Kotlin Multiplatform) client for the [Reticulum](https://reticulum.network/) LoRa mesh network. Replaces the [browser-based webclient](../reticulum-lora-webclient/) with a real native app — foreground service for persistent connections, system notifications on incoming LXMF, ships as a signed APK.

**No external dependencies.** No accounts, no API keys, no central server, no analytics, no Google Play Services, no Firebase. Identity generated on-device, all crypto runs locally, persistence is Room (SQLite). The only outbound traffic is whatever transport you attach (BLE / Bluetooth Classic to your own RNode, or TCP to an `rnsd` you pick — including `127.0.0.1` for offline LAN testing). The Nodes tab embeds OpenStreetMap tiles when at least one observed destination carries lat/lon — that's the only HTTP fetch in the app.

## Status

**Alpha — signed APKs ship from CI on every `android-vX.Y.Z` tag.**
[![Latest release](https://img.shields.io/github/v/release/thatSFguy/reticulum-mobile-app?label=latest&sort=semver&color=blue)](https://github.com/thatSFguy/reticulum-mobile-app/releases/latest)

## Capabilities

End-to-end verified against `tools/test_lxmf_receiver.py` + `tools/test_nomadnet_node.py` and live MichMesh nodes.

**Transports** — BLE NUS, Bluetooth Classic / RFCOMM (SPP), and direct TCP to a remote rnsd `TCPServerInterface`. Any combination simultaneously, with independent reconnect supervisors per kind. Per-link traffic pins to the kind that established the link; broadcasts (announces, path requests, opportunistic LXMF, initiator LINKREQUEST) fan out to every attached transport. Inbound dedup is global. LoRa radio config (freq / BW / SF / CR / TX power) pushed to every attached RNode on connect.

**LXMF messaging** — opportunistic single-packet delivery with retry queue + delivery proofs, plus link-delivered. Multi-hop transit via §2.3 HEADER_2 conversion. Inbox surfaces every sender we've received from; favorites pin a thread to the top.

**NomadNet browser** — micron parser at upstream `MicronParser.py` parity (backtick escapes, tables, page-level `#!c=` / `#!bg=` / `#!fg=` headers, partials / server-side includes). Single-packet and Resource-fragmented pages. Form inputs (text / checkbox / radio) submit as `field_<name>` keys per `Node.py`. In-page link navigation (same-node + cross-node), history-aware Back, link reuse across page nav, opt-in `LINKIDENTIFY` for ALLOW_LIST pages. Page cache with "last pulled Xm ago", reload, clear. Search box + Favorites + Cached chips.

**Identity & contacts** — per-install Reticulum identity (X25519 + Ed25519, ratchet, persisted in Room). QR card on Settings to share, scan others' from Nodes. Local-only nicknames per contact (`userLabel`, never sent on the wire) win over the announced display name everywhere; tap the pencil on any Nodes row to set or clear. Manual hash entry + QR scanner for adding contacts before they announce.

**Visibility** — relay-aware Graph (`me → relay → leaf` via cached HEADER_2 transport_ids), Nodes map (osmdroid + OpenStreetMap) for destinations carrying lat/lon, per-row metadata (hop count, RSSI, last-heard age, stale/far warnings), diagnostics log with copy/clear. Bottom-nav Settings icon turns red when no transport is connected.

**Spec compliance & hardening** — §2.3 originator HEADER_1→HEADER_2 conversion (DATA + LINKREQ), §11.1 16-byte request_path_hash, §11.2 request_id verification, §6.5 link-addressed `dest_type = LINK`, §10.2 / §10.5 Resource framing + RESOURCE_REQ, validateAnnounce recomputes `SHA256(name_hash ‖ identity_hash)[:16]` to reject impersonation announces, Resource size + bz2 decompression-bomb caps. Surviving gaps tracked in `todo.md` (initiator-side KEEPALIVE, LXMF stamps, PROOF signature verification).

## Screenshots

Live against the MichMesh TCP transport node (`RNS.MichMesh.net:7822`) on a Galaxy A42 5G running v0.1.83.

| Messages | Nodes | Nomad | Graph | Settings |
|---|---|---|---|---|
| ![Messages](docs/screenshots/01-messages.png) | ![Nodes](docs/screenshots/02-nodes.png) | ![Nomad](docs/screenshots/03-nomad.png) | ![Graph](docs/screenshots/04-graph.png) | ![Settings](docs/screenshots/05-settings.png) |

## Install

Sideload the latest signed APK. These links always serve the most recent tag:

- **Latest release page:** https://github.com/thatSFguy/reticulum-mobile-app/releases/latest
- **Direct APK URL:** `https://github.com/thatSFguy/reticulum-mobile-app/releases/latest/download/androidApp-release.apk`

Via `gh` CLI:

```powershell
gh release download --repo thatSFguy/reticulum-mobile-app --pattern '*.apk'
adb install androidApp-release.apk
```

## Layout

```
shared/commonMain/     Protocol logic, platform-independent
  ├── protocol/        Packet header encode/decode, constants
  ├── crypto/          Identity, TokenCrypto, CryptoProvider interface
  ├── announce/        Build/parse/validate announces, known destinations, telemetry parser
  ├── lxmf/            LXMF message pack/unpack with dual-variant signature verify
  ├── link/            Reticulum Link protocol (responder + initiator state machines)
  ├── nomad/           Micron parser for NomadNet pages
  ├── resource/        Reticulum Resource fragmentation (multi-packet pages, propagation /get)
  ├── engine/          ReticulumEngine glue: routes packets, per-kind transport map, link sessions
  ├── transport/       KISS + HDLC frame encode/decode, Transport interface, TcpInterface
  └── store/           Data models + repository interfaces (single Destinations table)

shared/androidMain/    Android-specific actuals (Bouncy Castle, BLE NUS, BT Classic, TCP)
shared/iosMain/        iOS scaffold (Phase 1 stubs only — see iOS section below)

androidApp/            Android UI + lifecycle
  ├── ui/screens/      Messages, Nodes, Nomad, Graph, Settings
  ├── service/         ReticulumService: foreground service, per-kind reconnect supervisors
  └── storage/         Room database + Repositories

iosApp/                iOS app shell (SwiftUI, Phase 3 scaffold)
  ├── iosApp/          Swift sources — TabView, per-tab placeholders
  ├── project.yml      XcodeGen spec (project.pbxproj is generated, not checked in)
  └── README.md        Build instructions
```

`reference/` holds the JS webclient source + test vectors. `CLAUDE.md` has architecture, protocol reference, known bugs, and diagnostic commands.

## Build

CI handles releases. Locally:

```bash
# Install JDK 17 (e.g. Microsoft.OpenJDK.17 via winget on Windows)
gradle wrapper --gradle-version 8.7   # one-time bootstrap
./gradlew :androidApp:assembleDebug
```

APK lands at `androidApp/build/outputs/apk/debug/`. For signed releases, set the `ANDROID_KEYSTORE_*` GitHub Actions secrets and tag `android-vX.Y.Z`.

## iOS

**Personal-use sideload only — this app will not be published to the App Store.** Apple's $99/year Developer Program plus the App Review process aren't a fit for an off-grid LoRa mesh app whose primary use case is operating without internet, app stores, or Apple infrastructure. The build target is "drag the IPA onto a personal device with `Sideloadly` / `AltStore` / a personal provisioning profile" — same posture as the Android signed-APK sideload.

Port is broken into four phases. Each is independently shippable.

| Phase | Status | Description |
|-------|--------|-------------|
| 1. KMP iOS targets + `Shared.xcframework` production | ✅ shipped (PR #1) | `iosArm64` / `iosSimulatorArm64` / `iosX64` configured; static XCFramework via the KMP `XCFramework` helper; macOS CI smoke test (`.github/workflows/ios-build.yml`). |
| 2. iOS platform actuals | 🟡 in progress | `Bz2.ios.kt` cinterop ✅ (PR #2, merged). Remaining: `TcpSocket.ios.kt` → `Network.framework` `NWConnection`. New `IosCryptoProvider` using `CryptoKit` (Curve25519, HKDF, HMAC) + `CommonCrypto` for AES-CBC. iOS storage actual via `SQLDelight` matching Room v8 schema. `IosBleTransport` against `CoreBluetooth` for the NUS service. **Bluetooth Classic skipped** — requires MFi certification. |
| 3. iOS app shell | 🟡 in progress (`iosApp/`) | SwiftUI scaffold with five-tab `TabView` matching the Android nav. Each tab is a placeholder calling into `Shared.xcframework` to validate the bridge. XcodeGen-managed project (`iosApp/project.yml` → `xcodegen generate` → `iosApp.xcodeproj`). Real screens land in Phase 4 once Phase 2 actuals exist. |
| 4. Real screens + sideload distribution | not started | SwiftUI ports of Messages / Nodes / Nomad / Graph / Settings against the Phase 2 actuals. `ci_scripts/ci_post_clone.sh` for JDK 17 + Gradle bootstrap. Personal Apple Developer account ($99/year) for ad-hoc signing. Tag-triggered `ios-vX.Y.Z` builds producing IPAs attached to the GitHub release alongside the Android APK — same one-tap-sideload posture, no App Store review. |

Phase 2 is the bulk of the protocol-level work. CoreBluetooth's delegate-based callback model is the biggest mismatch with the Android `BluetoothGatt` callback chain; everything else is mostly straight ports of small modules. See `iosApp/README.md` for build instructions.

## Related

- [reticulum-lora-webclient](../reticulum-lora-webclient/) — the Capacitor-based browser client this replaces
- [reticulum-rnode](../reticulum-rnode/) — RNode firmware (the LoRa modem)
- [reticulum-lora-repeater](../reticulum-lora-repeater/) — repeater firmware
- [markqvist/Reticulum](https://github.com/markqvist/Reticulum) — upstream Python RNS
- [torlando-tech/columba](https://github.com/torlando-tech/columba) — another native Android Reticulum client (independent codebase)
- [liamcottle/reticulum-meshchat](https://github.com/liamcottle/reticulum-meshchat) — Reticulum chat with Android builds
