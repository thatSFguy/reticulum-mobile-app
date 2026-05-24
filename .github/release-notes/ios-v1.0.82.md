## Highlights

- **New App Store-compliant app icon.** Re-exported the hexagonal-mesh icon so the bundle PNG (`iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`) is now 1024×1024, 8-bit RGB, **no alpha and no pre-rounded corners** — Apple applies its own rounded-corner mask at display time, and the previous source had its corners baked into the artwork (a known cause of App Review rejection and black corner wedges on the home screen). Same hexagon design, just square edge-to-edge under the iOS mask.
- **Reproducible icon pipeline.** Added a canonical SVG source at `iosApp/branding/reticulum_icon_ios_square.svg` and a Pillow-based renderer at `iosApp/branding/render_ios_icon.py`. No Cairo / librsvg native dependency; a future redesign needs only the SVG edit + `python iosApp/branding/render_ios_icon.py`. The iOS App Store starter pack at `iosApp/AppStore/icon-1024-spec.md` was updated to point at this triple and dropped the stale "project doesn't currently have an icon" claim.

## What didn't change

- No wire-format, protocol, or message-handling changes since `ios-v1.0.81`. The shared engine is byte-identical; the parity-table feature matrix in the README is unchanged for this tag.
- The Android-side fixes that shipped in `android-v1.2.20` (notification cold-start deep-link via Channel + unread-count badge on the Messages list) are Android-only — iOS already routed taps into the matching conversation via the `pendingDeepLink` drain on store init, and the iOS unread-badge parity is a future-session follow-up.
