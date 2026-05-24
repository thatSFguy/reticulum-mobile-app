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

## Current icon

A working icon ships in the repo: a hexagonal mesh-topology design on a dark teal gradient, matching the Android branding. Files:

- **Bundle PNG** — `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (1024×1024, 8-bit RGB, no alpha, square edge-to-edge background). Wired into `Contents.json` as the single universal source — Xcode 15+ auto-generates every smaller bundle size from this one file at build time.
- **SVG source** — `iosApp/branding/reticulum_icon_ios_square.svg`. The canonical vector form; re-render with the Python script next to it to refresh the PNG. The geometry mirrors `androidApp/branding/reticulum_icon.svg` (every coordinate doubled, 512→1024 viewBox) **except** the background `<rect>` has no `rx` / `ry` rounding. That's deliberate: Apple applies its own rounded-corner mask at display time; a pre-rounded source would produce doubled rounding with black wedges in the corners on device.
- **Renderer** — `iosApp/branding/render_ios_icon.py`. Pure Pillow, no native deps, runs on a vanilla Windows/Mac/Linux Python install. Recreates the icon from primitives instead of going through librsvg/Cairo so a refresh works without a Cairo install. Run from the repo root: `python iosApp/branding/render_ios_icon.py`.

If a volunteer wants to redesign the icon, replace the SVG (keeping the square-corner background invariant) and re-run the script. Apple's hard requirements above still apply.

## How to install the icon

The icon is already wired into the asset catalog. To verify after `xcodegen generate`:

1. Open `iosApp/iosApp.xcodeproj` in Xcode and confirm the AppIcon asset shows the mesh design (not Xcode's placeholder grey "A").
2. Build to a real device and look at the home-screen tile — Apple's mask should produce a clean rounded square, no black corner wedges.
3. In App Store Connect, upload the same 1024×1024 PNG to *App Information → General Information → App Icon*. App Store Connect's icon slot is independent of the bundle's icon; both must be uploaded.
