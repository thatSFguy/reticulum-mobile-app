# App Store icon spec

Apple requires a single **1024 × 1024 pixel PNG** for the App Store listing. The Xcode bundle separately needs the standard icon set (every size from 20pt @1x through 60pt @3x). XcodeGen handles the in-bundle set if you point `Assets.xcassets/AppIcon.appiconset` at a complete source asset; the App Store Connect upload uses the 1024×1024 separately.

## Hard requirements

| Property | Required value |
|---|---|
| Resolution | 1024 × 1024 px |
| Format | PNG (sRGB or Display P3) |
| Color space | sRGB or P3, 8-bit per channel |
| Alpha channel | **NONE** (Apple rejects icons with an alpha channel for the App Store listing) |
| Corner rounding | **NONE** (Apple applies the standard iOS rounded-corner mask at display time — pre-rounded corners produce a doubled-rounding artifact) |
| Layers | Flat (PNG must be a single rasterized layer) |
| Padding | None (the icon must fill the 1024 × 1024 square edge-to-edge) |

## Design suggestions (not Apple requirements)

The project doesn't currently have an icon — the existing CI builds use Xcode's placeholder. A volunteer artist could produce something thematic. Suggestions:

- **Mesh + radio theme**. Concentric expanding arcs or hexagons evoke radio propagation; an off-center node-and-link motif suggests mesh topology.
- **Reticulum brand color**. The upstream Reticulum project page uses a deep ocean blue (#1B3A57-ish range). Pulling from that palette ties the app visually to the broader ecosystem.
- **High contrast at small sizes**. The smallest in-bundle icon is 40×40 px (20pt @2x). Avoid fine detail; a single bold shape is more recognizable than a complex composition.
- **No text in the icon**. Apple actively rejects icons that contain the app's name in the artwork — App Store displays the name as a separate string already.

## How to install the icon

1. Drop the 1024×1024 PNG at `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppStore@1x.png`. Xcode 15+ will auto-generate the smaller bundle sizes from a single 1024×1024 source if the asset is configured as a single-image entry.
2. Edit `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` to declare the new icon. If the file doesn't exist yet (the project might still be using Xcode's stub), recreate it from `xcodegen generate` after adding an `appIcon` entry to `iosApp/project.yml`.
3. Run `xcodegen generate` to refresh the `.xcodeproj`. Build locally on a Mac to verify Xcode picks the icon up; the placeholder grey "A" should be replaced.
4. In App Store Connect, upload the same 1024×1024 PNG to the *App Information → General Information → App Icon* slot. (App Store Connect's icon is independent of the bundle's icon; both must be uploaded.)
