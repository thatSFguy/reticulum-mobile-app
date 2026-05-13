# Screenshots spec

Apple requires screenshots on at least one device-class size. Submitting only the largest covers everything (Apple auto-scales for display on smaller listings) but providing native captures for both 6.7" and 6.5" is strongly recommended.

## Required dimensions (as of iOS 17 / 2026)

| Device class | Native screenshot resolution | Required? |
|---|---|---|
| 6.9" iPhone (15/16 Pro Max) | 1320 × 2868 px | Optional but recommended |
| 6.7" iPhone (12/13/14/15/16 Plus, Pro Max) | 1290 × 2796 px | **Required** |
| 6.5" iPhone (XS Max, 11 Pro Max, 14 Plus) | 1284 × 2778 px | **Required** |
| 5.5" iPhone (8 Plus) | 1242 × 2208 px | Optional (deprecated; Apple may drop the requirement entirely soon) |
| iPad Pro 12.9" | 2048 × 2732 px | Required IF you submit iPad support |
| iPad Pro 11" | 1668 × 2388 px | Optional |

This app does **not** ship an iPad-optimized layout, so the iPad submission can be skipped. Apple's iPad-on-iPhone-compatibility mode is acceptable for the App Store; it'll show iPhone screenshots and run in scaled mode on iPad if a user happens to install it.

## Minimum count and order

- Minimum: 1 screenshot per required device class (so 2 total at minimum: 6.7" + 6.5").
- Maximum: 10 per device class.
- Order matters — the first screenshot is what appears in App Store search results, the rest are seen only on the listing page.

## Recommended screenshot order

Mirroring the bottom-nav tab order in the app:

1. **Messages — conversation view with at least one received and one sent bubble.** The most-recognizable "this is a messenger" view; do this first so the App Store search result preview communicates the function instantly. Compose a small mock conversation that doesn't expose real contact hashes.
2. **Nodes — list view showing 3-5 destinations** with recent timestamps and at least one map-coordinate-bearing destination so the geolocation chip is visible.
3. **Nomad — a rendered NomadNet page**. Pick a real public page like `index.mu` from `RNS.MichMesh.net`, or a curated sample page that demonstrates the micron rendering (headers, links, a form input).
4. **Graph — the force-directed topology view** with at least one multi-hop relay path visible. Demonstrates the relay-aware visualization that distinguishes this app from a flat chat list.
5. **Settings — identity + transport sections** showing the QR card and a connected RNode. Reinforces "no account / no cloud" by showing all controls are local.

If a sixth screenshot slot is desired:

6. **Compose row with paperclip + image preview chip** (the Phase 2 image-attach feature). This is the most-recent shipped feature and worth highlighting.

## Capture flow

1. Build a signed Release on a real iPhone (Simulator screenshots are not acceptable for the App Store; Apple actively rejects them).
2. Run the app, populate it with a minimal demo dataset:
   - One own identity with a set display name (e.g. *Demo Rob*).
   - 3-5 contacts with realistic display names (e.g. *Alice*, *RNS.MichMesh.net*, *test-relay-1*).
   - 5-10 messages across one conversation.
   - One Nomad page cached.
3. Capture with the standard iOS Power + Volume-Up combo. Each capture lands in Photos at the native screen resolution.
4. Optionally crop the status bar (the Apple-prescribed style is to leave the system status bar visible with a clean "9:41 AM 100% battery" — apps that show real battery percentages or notifications in screenshots can be rejected). Use a screenshot framing tool like Screen Studio or the open-source `screenshot-frame.app` to substitute the clean status bar.
5. Upload to App Store Connect → *App Store → iOS → Screenshots*.

## Localization

Default to English (U.S.). If the volunteer wants to localize the listing, App Store Connect supports per-language screenshot sets — but the app itself is English-only at the moment, so localized screenshots would mislead. Stick with English unless someone is willing to localize the app strings first.

## Avoid in screenshots

- Real contact destination hashes (privacy: each is a public identifier in the mesh, but App Store screenshots are crawled and indexed broadly).
- Real GPS coordinates for the demo identity (the Nodes map view will leak the demo phone's location if the demo identity ran in a location-broadcasting node).
- Real propagation node addresses unless those nodes are publicly advertised by their operators (RNS.MichMesh.net is fine; a private home server isn't).
- "Lorem ipsum" placeholder text — Apple's reviewers consider it unprofessional and may reject.
