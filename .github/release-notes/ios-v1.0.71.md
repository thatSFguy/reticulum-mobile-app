## Highlights

- **QR scan correctly categorises non-LXMF destinations** — pre-fix, scanning an RRC hub's QR (or any other non-`lxmf.delivery` destination) landed the entry in Nodes/Contacts as "LXMF delivery" and only migrated to its real category a minute later when the actual peer's announce arrived. Now the engine reverse-looks-up the service type at scan time by recomputing `SHA-256(name_hash || identity_hash)[:16]` against every well-known service — definitive, microseconds. RRC-hub QRs also seed the `StoredRrcHub` row so the hub appears in the Rooms tab immediately.
- **"Open in Relay Chat" button actually navigates** — tapping the action on a destination detail sheet was silently upserting the hub into the rrc_hubs table but leaving the user stranded on the originating tab (typically Nodes). The new `OpenRrcHubEvent` deep-link signal mirrors the existing Messages-tab `OpenContactEvent` pattern: `ContentView` switches to the Rooms tab, `RoomsView` pushes the hub onto its NavigationStack. Android already had the equivalent via its `_pendingShowRooms` SharedFlow.

These ride alongside the shared `android-v1.2.9` cut.
