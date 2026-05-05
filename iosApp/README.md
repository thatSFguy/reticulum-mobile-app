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

## What's NOT here yet

- A real launch icon (the `AppIcon.appiconset` is an empty placeholder).
- Localizations beyond English.
- The `bluetooth-central` background mode (added in Phase 4 alongside `IosBleTransport`).
- Code signing / provisioning. Phase 4 wires this for personal-device sideload only — see the root README's "iOS" section for the no-App-Store stance.

## Phase progression

1. ✅ **Phase 1** — KMP iOS targets + `Shared.xcframework` production. (PR #1, merged)
2. 🟡 **Phase 2** — iOS platform actuals. libbz2 cinterop ✅ (PR #2, merged); `TcpSocket.ios.kt` (NWConnection), `IosCryptoProvider`, iOS storage actual, `IosBleTransport` not started.
3. 🟡 **Phase 3** — iOS app shell. ← *you are here*. SwiftUI scaffold + per-tab placeholders calling into Shared.
4. 🔴 **Phase 4** — Real screens + sideload distribution. SwiftUI ports of Messages / Nodes / Nomad / Graph / Settings. Personal Apple Developer cert + ad-hoc IPA attached to the GitHub release alongside the Android APK.
