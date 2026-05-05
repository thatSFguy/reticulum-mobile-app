# iosApp

SwiftUI app shell for the iOS port. **Phase 3 milestone — non-functional.** The shell launches and tabs render, but every protocol-touching screen ("real Messages", "real Nodes", "Connect over BLE / TCP") needs the iosMain platform actuals from Phase 2 to do anything useful.

## What's here

| File | Purpose |
|------|---------|
| `iosApp/iOSApp.swift` | `@main` app entry — single `WindowGroup` hosting `ContentView`. |
| `iosApp/ContentView.swift` | Root `TabView` with five tabs matching the Android NavigationBar. |
| `iosApp/Tabs/*.swift` | Per-tab placeholder views. Each calls into `Shared` to prove the framework bridge works. |
| `iosApp/PhaseThreePlaceholder.swift` | Common placeholder body — title, "blocked on" message, shared-framework demo string. |
| `iosApp/Assets.xcassets/` | App icon + accent color stubs. Empty for now; populate before TestFlight. |
| `project.yml` | XcodeGen spec. The `.xcodeproj` is generated, not checked in. |

## Build

```sh
brew install xcodegen
cd iosApp
xcodegen generate
open iosApp.xcodeproj
```

The project consumes `Shared.xcframework` from `../shared/build/XCFrameworks/release/`. Build it from the repo root with:

```sh
./gradlew :shared:assembleSharedXCFramework
```

The first run downloads the Kotlin/Native compiler (~600 MB to `~/.konan`). Subsequent runs are incremental and fast. Requires macOS — Kotlin/Native for Apple targets needs the Xcode toolchain.

After `xcodegen` runs, you can also build from the CLI:

```sh
xcodebuild -project iosApp.xcodeproj \
           -scheme iosApp \
           -destination 'platform=iOS Simulator,name=iPhone 15'
```

## Status by tab

Each tab calls a small pure-Kotlin function in `Shared.xcframework` so you can confirm the bridge works the moment the app launches. The user-visible content is a placeholder until Phase 4 fleshes out each screen.

| Tab | Blocked on | Demo it shows |
|-----|------------|---------------|
| Messages | iOS storage (SQLDelight) + IosCryptoProvider | KISS frame hex for an empty CMD_DATA payload (`KissKt.buildKissFrame`) |
| Nodes | IosBleTransport (CoreBluetooth) + iOS storage | The well-known `lxmf.delivery` name_hash hex (KnownDestinations table lookup) |
| Nomad | NWConnection-backed `TcpSocket` + iOS storage cache | Quick line about micron rendering |
| Graph | iOS storage + a Canvas-based renderer | The MTU constant from `Constants.kt` |
| Settings | IosCryptoProvider, NWConnection, CoreBluetooth | Computed Reticulum header byte (`HEADER_1 \| DEST_SINGLE << 2 \| PACKET_DATA`) |

## Sideload (Phase 4 distribution)

Tagging `ios-vX.Y.Z` triggers `.github/workflows/ios-release.yml`, which builds an **unsigned** `iosApp-unsigned.ipa` on macOS GHA and attaches it to the matching GitHub release.

To install on your iPhone:

1. Download `iosApp-unsigned.ipa` from the latest [release](https://github.com/thatSFguy/reticulum-mobile-app/releases/latest).
2. Re-sign with a free Apple ID via:
   - **AltStore** (recommended) — runs as a tray app on your Mac/PC, auto-renews the 7-day free-Apple-ID profile while connected: https://altstore.io/
   - **Sideloadly** — single-shot, you re-run it weekly: https://sideloadly.io/
   - **SideStore** (no host computer needed once initial pairing is done): https://sidestore.io/
3. Trust your Apple-ID-signed developer cert under Settings → General → VPN & Device Management.

The repo holds **zero signing keys**. Each user signs locally with their own Apple ID. Free-tier Apple ID profiles expire after 7 days; AltStore renews automatically while it's running.

Why no App Store: this is an off-grid LoRa mesh app. Apple's Developer Program ($99/year), App Review process, and centralized distribution model are at odds with the use case. See the root README's "iOS" section for the longer rationale.

## What's NOT here yet (Phase 4 polish backlog)

- A real launch icon (the `AppIcon.appiconset` is an empty placeholder).
- Localizations beyond English.
- The `bluetooth-central` background mode (Info.plist).
- AVCaptureSession-backed QR scanner for adding contacts.
- Rich Compose-parity micron renderer (current Nomad screen ships a plain-text stripper; styled / form-input rendering is a multi-day SwiftUI port of `MicronView.kt`).
- Force-directed Graph canvas (current Graph tab ships a hop-count grouped list).

## Phase progression

1. ✅ **Phase 1** — KMP iOS targets + `Shared.xcframework` production.
2. ✅ **Phase 2** — iOS platform actuals (libbz2, POSIX TCP, CryptoKit, SQLDelight, CoreBluetooth).
3. ✅ **Phase 3** — iOS app shell with all five tabs real.
4. 🟡 **Phase 4** ← *you are here*. CI/IPA distribution + the polish backlog above.
