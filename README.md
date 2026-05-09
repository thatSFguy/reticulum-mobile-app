# Reticulum Mobile App

Native Android (Kotlin Multiplatform) client for the [Reticulum](https://reticulum.network/) LoRa mesh network. Replaces the [browser-based webclient](../reticulum-lora-webclient/) with a real native app — foreground service for persistent connections, system notifications on incoming LXMF, ships as a signed APK.

**No external dependencies.** No accounts, no API keys, no central server, no analytics, no Google Play Services, no Firebase. Identity generated on-device, all crypto runs locally, persistence is Room (SQLite). The only outbound traffic is whatever transport you attach (BLE / Bluetooth Classic to your own RNode, or TCP to an `rnsd` you pick — including `127.0.0.1` for offline LAN testing). The Nodes tab embeds OpenStreetMap tiles when at least one observed destination carries lat/lon — that's the only HTTP fetch in the app.

## Status

**v1.0 — feature-complete for the v1 scope. Signed APKs and unsigned IPAs ship from CI on every tag.**
[![Latest release](https://img.shields.io/github/v/release/thatSFguy/reticulum-mobile-app?label=latest&sort=semver&color=blue)](https://github.com/thatSFguy/reticulum-mobile-app/releases/latest)

The protocol implementation has been verified end-to-end against live `tools/test_lxmf_receiver.py` + `tools/test_nomadnet_node.py` runs and MichMesh nodes, audited for security (2026-05-07 review on the full 73-commit window since the last webclient audit), and the version pipeline derives `versionName` / `versionCode` directly from the git tag so what you install matches what the release page advertises.

## Capabilities

**Transports** — BLE NUS, Bluetooth Classic / RFCOMM (SPP), and direct TCP to a remote rnsd `TCPServerInterface`. Any combination simultaneously, with independent reconnect supervisors per kind. Per-link traffic pins to the kind that established the link; outbound LXMF + initiator LINKREQUEST to a known peer routes via hop-count-aware per-destination affinity (sticky on the shortest path so a TCP-attached mesh's re-emit of a peer's own LoRa announce can't hijack a working direct route). Only announces and path requests fan out to every attached transport. Inbound dedup is global. LoRa radio config (freq / BW / SF / CR / TX power) pushed to every attached RNode on connect, re-pushable from Settings.

**LXMF messaging** — Sideband-parity link delivery as the primary outbound path (encrypted single-link DATA + per-packet PROOF awaits), with up to 5 link-establishment attempts at 10s intervals (matching LXMF's `MAX_DELIVERY_ATTEMPTS` / `DELIVERY_RETRY_WAIT`) so a single LoRa half-duplex collision on the LINKREQUEST or LRPROOF doesn't kill the whole send. Opportunistic single-packet fallback only after the full retry budget is exhausted. Multi-hop transit via §2.3 HEADER_2 conversion. Per-packet PROOF receipts on inbound link DATA, **with full Ed25519 signature verification against the responder's long-term key per spec §6.5.1** (closed a silent-message-loss vector found in the v1 security review). Inbox surfaces every sender we've received from; favorites pin a thread to the top, toggleable in place from either the Nodes tab or the Messages tab. Tap any messagable row in Nodes to jump straight into a conversation without having to favorite first. LXMF propagation node sync (`/offer` + `/get` with the §2.3 fix and §11.1 16-byte path_hash).

**NomadNet browser** — micron parser at upstream `MicronParser.py` parity (backtick escapes, tables, page-level `#!c=` / `#!bg=` / `#!fg=` headers, partials / server-side includes). Single-packet and Resource-fragmented pages. Form inputs (text / checkbox / radio) submit as `field_<name>` keys per `Node.py`. In-page link navigation (same-node + cross-node), history-aware Back, link reuse across page nav, opt-in `LINKIDENTIFY` for ALLOW_LIST pages. Page cache with "last pulled Xm ago", reload, clear. Search box + Favorites + Cached chips. Selectable text + LXMF link targets directly from rendered pages.

**Identity & contacts** — per-install Reticulum identity (X25519 + Ed25519, ratchet, persisted in Room / SQLDelight). QR card on Settings to share, scan others' from Nodes. **Identity export / import via passphrase-encrypted `.rmid` archives** (PBKDF2-HMAC-SHA256 → HKDF-split → AES-256-CBC + HMAC-SHA256, encrypt-then-MAC) — back up before reinstalling, migrate to a new phone, recover from device loss. Saves through the Android share sheet, imports through the file picker. Wrong passphrase / tampered bytes are indistinguishable on the wire by design. Local-only nicknames per contact (`userLabel`, never sent on the wire) win over the announced display name everywhere; tap the pencil on any Nodes row to set or clear. Manual hash entry + QR scanner for adding contacts before they announce.

**Visibility** — relay-aware Graph (`me → relay → leaf` via cached HEADER_2 transport_ids), Nodes map (osmdroid + OpenStreetMap) for destinations carrying lat/lon, per-row metadata (hop count, RSSI, last-heard age, stale/far warnings), per-message link-quality footers (RSSI + hop count on each incoming bubble), diagnostics log with copy/clear. Bottom-nav Settings icon turns red when no transport is connected. Notifications surface incoming messages while backgrounded; tap an incoming notification to open the matching conversation.

**Spec compliance & hardening** — §2.3 originator HEADER_1→HEADER_2 conversion (DATA + LINKREQ), §11.1 16-byte request_path_hash, §11.2 request_id verification, §6.5 link-addressed `dest_type = LINK`, §6.5.1 Ed25519-verified link DATA proofs, §6.7.1 Token-encrypted KEEPALIVE bodies, §6.7.3 LINKCLOSE body verification, §10.2 / §10.5 Resource framing + RESOURCE_REQ, validateAnnounce recomputes `SHA256(name_hash ‖ identity_hash)[:16]` to reject impersonation announces, Resource size + bz2 decompression-bomb caps. Surviving gaps tracked in `todo.md` (initiator-side KEEPALIVE, LXMF stamps).

### Platform parity

The protocol stack is identical (commonMain Kotlin), so every wire-format / crypto / spec-compliance feature lands on both apps at the same time. Differences are confined to transport availability and a couple of platform-architectural items.

| Feature | Android | iOS | Notes |
|---|---|---|---|
| BLE (NUS) transport to RNode | ✅ | ✅ | |
| Bluetooth Classic / RFCOMM transport | ✅ | ❌ | iOS BT Classic to non-MFi peripherals requires Apple's MFi cert, closed program |
| Direct TCP to rnsd | ✅ | ✅ | |
| Multi-transport simultaneously | ✅ | ✅ | Per-link affinity, per-kind reconnect supervisors |
| LXMF send (link-delivered + opportunistic fallback) | ✅ | ✅ | Sideband-parity 5× retry @ 10 s |
| LXMF receive + delivery proofs | ✅ | ✅ | Ed25519-verified per spec §6.5.1 |
| LXMF propagation node sync | ✅ | ✅ | |
| NomadNet browser — micron rendering | ✅ rich | ✅ rich | Headings, paragraphs, tables, partials, color/bg, alignment |
| NomadNet form inputs (text / checkbox / radio) | ✅ | ✅ | `field_<name>` submit per `Node.py` |
| LINKIDENTIFY toggle for ALLOW_LIST pages | ✅ | ✅ | |
| Page cache + reload + clear | ✅ | ✅ | |
| Identity (X25519 + Ed25519 + ratchet) | ✅ | ✅ | Generated on-device |
| Display name editor (Settings) | ✅ | ✅ | Triggers immediate re-announce on save |
| Identity QR card + scanner | ✅ | ✅ | Wire-compatible QR cards across platforms |
| Manual hash entry | ✅ | ✅ | |
| Identity export / import (passphrase `.rmid`) | ✅ | ✅ | Wire-compatible `.rmid` cross-platform |
| Reset identity | ✅ | ✅ | |
| Per-contact local nickname (userLabel) | ✅ | ✅ | |
| Favorites + inbox surfaces | ✅ | ✅ | Star-toggle from Nodes / Messages / Nomad |
| Tap-to-message from Nodes | ✅ | ✅ | |
| Per-message link-quality footer (RSSI / hops) | ✅ | ✅ | |
| Force-directed Graph view | ✅ | ✅ | Pan/zoom on iOS, same legend |
| Nodes map (lat/lon destinations) | ✅ osmdroid | ⏳ deferred | iOS uses MapKit slot; not wired yet |
| RNode radio config form (freq / BW / SF / CR / TX) | ✅ | ✅ | Pushed on connect + on Save |
| TCP transport-node rotation ("Pick another") | ✅ | ⏳ deferred | iOS uses fixed default + manual edit |
| Theme picker (System / Light / Dark) | ⏳ system-only | ✅ | Android relies on Material 3 system theming |
| Diagnostics log (copy / clear / verbose toggle) | ✅ | ✅ | |
| Notification on incoming message | ✅ | ⏳ deferred | UNUserNotificationCenter wiring still TODO; the BLE link itself stays live in the background after v1.0.8 |
| Persistent background mesh listening | ✅ foreground service | ✅ | iOS uses `UIBackgroundModes: bluetooth-central` + `CBCentralManagerOptionRestoreIdentifierKey` + `willRestoreState` handler — Apple's officially-supported BLE background path. Works in TestFlight, App Store, and AltStore-resigned builds |
| Signed release artifact | ✅ APK | unsigned IPA | Sideload via AltStore / Sideloadly with a free Apple ID |

## Screenshots

### Android

Live against the MichMesh TCP transport node (`RNS.MichMesh.net:7822`) on a Galaxy A42 5G. Layout still matches v1.0; minor cosmetic deltas vs. v0.1.83 capture (additional star-toggle on Messages thread rows, identity export/import buttons in Settings).

| Messages | Nodes | Nomad | Graph | Settings |
|---|---|---|---|---|
| ![Messages](docs/screenshots/01-messages.png) | ![Nodes](docs/screenshots/02-nodes.png) | ![Nomad](docs/screenshots/03-nomad.png) | ![Graph](docs/screenshots/04-graph.png) | ![Settings](docs/screenshots/05-settings.png) |

### iOS

| Messages | Nomad | Graph |
|---|---|---|
| ![Messages](iosApp/screenshots/messages.jpeg) | ![Nomad](iosApp/screenshots/nomad%20browser.png) | ![Graph](iosApp/screenshots/graph.png) |

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
| 1. KMP iOS targets + `Shared.xcframework` production | ✅ shipped | `iosArm64` / `iosSimulatorArm64` / `iosX64` configured; static XCFramework via the KMP `XCFramework` helper; macOS CI smoke test (`.github/workflows/ios-build.yml`). |
| 2. iOS platform actuals | ✅ shipped | libbz2 cinterop, POSIX-socket TcpSocket, IosCryptoProvider (CommonCrypto + CryptoKit halves), SQLDelight storage, CoreBluetooth IosBleTransport. **Bluetooth Classic skipped** — needs MFi certification. |
| 3. iOS app shell | ✅ shipped | SwiftUI five-tab `TabView` matching the Android nav. Settings / Messages / Nodes / Nomad / Graph all real (basic feature parity). XcodeGen-managed project (`iosApp/project.yml` → `xcodegen generate` → `iosApp.xcodeproj`). |
| 4. Sideload distribution + polish | ✅ shipped | Tag-triggered `ios-vX.Y.Z` builds produce **unsigned** IPAs attached to the GitHub release alongside the Android APK — re-sign locally with a free Apple ID via AltStore / Sideloadly / SideStore. No App Store, no $99/year Developer Program, no signing keys in this repo. CI runs a structural validation suite on every IPA before upload (`zip -t`, Mach-O arm64 verify, `vtool -show-build` deployment-target check, `otool -L` `@rpath` resolution, Info.plist + UIBackgroundModes assertions). CoreBluetooth scan UI + AVCaptureSession QR scanner ship in the BLE / Add-by-hash flows. Tap-to-message + favorite-from-Messages parity with Android. Rich Compose-parity micron renderer + force-directed Graph canvas remain a future polish — current iOS UI uses a plain-text micron renderer + hop-count grouped Graph list. |

CoreBluetooth's delegate-based callback model was the biggest mismatch with the Android `BluetoothGatt` callback chain in Phase 2; everything else was mostly straight ports of small modules. See `iosApp/README.md` for build instructions.

## Related

- [reticulum-lora-webclient](../reticulum-lora-webclient/) — the Capacitor-based browser client this replaces
- [reticulum-rnode](../reticulum-rnode/) — RNode firmware (the LoRa modem)
- [reticulum-lora-repeater](../reticulum-lora-repeater/) — repeater firmware
- [markqvist/Reticulum](https://github.com/markqvist/Reticulum) — upstream Python RNS
- [torlando-tech/columba](https://github.com/torlando-tech/columba) — another native Android Reticulum client (independent codebase)
- [liamcottle/reticulum-meshchat](https://github.com/liamcottle/reticulum-meshchat) — Reticulum chat with Android builds
