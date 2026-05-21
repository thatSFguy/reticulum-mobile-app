## Highlights

- **Rooms tab now shows the real hub name** — RRC-hub rows that displayed the bogus `"epr"` literal (a CBOR-vs-msgpack decode quirk on the hub's app_data — see Announce.kt:208) now refresh as soon as a fresh announce lands or the user connects. Two propagation points were added to the engine (commonMain, so both platforms benefit): every announce mirrors the hub's name onto the existing `StoredRrcHub` row, and the `Welcomed` event repairs the row from the hub's authoritative `hubName`. Idempotent — no effect once the row is already correct. Pre-fix iOS had no UI fallback for stale rows (Android masked the issue at render time), so this is especially visible on iOS.

Companion: `android-v1.2.12` ships the same engine-side fix; the Android UI fallback to `state.hubName` continues to work and now matches the stored row once the user connects or a fresh announce arrives.
