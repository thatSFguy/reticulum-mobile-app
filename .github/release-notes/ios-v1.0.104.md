A deleted Relay Chat hub came back on the next cold start.

`kConnLiveRrcHubs` is the launch-time restore set: `scheduleRrcRestore` re-opens every hub in it once a transport connects, and `openRrcSession` recreates a missing `rrc_hub` row on the way through. `deleteRrcHub` closed the session and deleted the row but never touched that set, so the next launch put the hub straight back.

Found by checking rather than reported — the same defect was reported on Android, and `ReticulumStore` is a deliberate mirror of the Android `ReticulumViewModel`, so a bug in one is worth looking for in the other. The Android fix ships as 1.2.113.

Nothing else in this build: the room-link sharing and tappable-link work in the Android 1.2.112/1.2.113 line is still Android-only. iOS has the shared link format and parser, so a room link is understood underneath, but `RoomsView` has no share sheet, does not linkify message text, and has no create-field name validation. That UI pass is still owed.
