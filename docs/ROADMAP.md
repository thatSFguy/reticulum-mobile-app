# Reticulum Mobile App — Competitive Roadmap

> Strategy and phased implementation plan for making this the default native
> Reticulum client. Grounded in a codebase + spec audit (2026-06-21) and a
> competitive scan of Sideband, MeshChatX, Columba, and Ratspeak.
>
> **Reconciled 2026-06-26** against the 2026-06-23 scope cull (see
> *Cancelled / out of scope* at the bottom). Real-time voice calls (LXST) and a
> dedicated onboarding wizard were cut; the strategy below no longer hinges on
> voice.

## The strategy in one sentence

Don't try to be a better Sideband. Be the **native, zero-config, truly
cross-platform (iOS included)** Reticulum client — own the position Columba is
reaching for but can't follow us to (iOS), and that Sideband, MeshChatX, and
Ratspeak are each structurally unable to occupy.

## The field (what we're actually up against)

| App | What it is | Its moat | Its structural weakness |
|---|---|---|---|
| **Sideband** | The protocol author's reference client (Kivy/Python) | First to every wire feature — LXST voice, Codec2-over-LoRa, telemetry, remote-command | Non-native UX, battery, background reliability; iOS is a 3rd-party fork |
| **MeshChatX** | Desktop/web maximalist (Node + Chromium fork of MeshChat) | Everything: voicemail, phonebook, ringtones, multi-identity, page archiving | Not mobile-native — voice runs through Chromium; mobile = run a server |
| **Columba** | Our mirror image — native Android, Material 3, BLE/WiFi/RNode/TCP, LXMF+LXST | Already occupies the "clean native mobile" niche; has press | Android-only; young |
| **Ratspeak** | An ecosystem — client + T-Deck/Cardputer hardware + a token | Hardware + community + games | A token polarizes the privacy crowd; thinnest client |

## The brutal truth & the throne

- **Real-time voice calls (LXST) are deliberately out of scope (cut 2026-06-23).**
  Three of four competitors ship LXST calls and Sideband does Codec2-over-LoRa, so
  we concede the *call* row of the comparison table — but full-duplex telephony is
  a multi-week subsystem we've chosen not to take on. We ship **async audio message
  clips** instead (Phase 0, `FIELD_AUDIO`/Opus, shipped 1.2.79), which covers the
  off-grid voice-note use case without the real-time-call machinery.
- **The unclaimed throne is native iOS.** Our KMP architecture (real `iosMain`
  actuals) is the one asset none of them can cheaply match. Columba is
  Android-only; Sideband-iOS is a fork; MeshChatX is desktop. A great native iOS
  LXMF client is a throne nobody is sitting on — this is now priority zero.

## What the audit changed (the surprises, all in our favor)

1. **Group chat is already built — just hidden.** Full RRC (IRC-style, CBOR over
   a Link to a hub; the kc1awv/rrcd standard) ships today behind the
   `experimentalRrc` flag. No competitor has good group chat. Cheapest leapfrog
   in the deck — this is the headline forward item.
2. **Onboarding is already low-friction.** Identity auto-creates; first launch
   lands on Connect; `KnownTcpNodes` pre-fills a public TCP node. A dedicated
   first-run *wizard* was considered and cut (2026-06-23) as polish — the existing
   auto-identity + pre-filled-node flow is the baseline we ship. Surface it, don't
   build a wizard.

## The Mac problem — solved without a Mac

KMP iOS can't compile on Windows/WSL, but TestFlight does **not** require a
physical Mac:
- GitHub Actions **macOS runners** build + test + archive.
- Apple Developer Program ($99/yr), enrollable from iPhone/web.
- Sign with an **App Store Connect API key** (`.p8`, generated in the web console).
- `xcodebuild` + `fastlane pilot` uploads to TestFlight from CI.

iOS becomes a parallel CI track, not a blocker — we just never open Xcode.

## Phased plan

Critical path: **group chat + native iOS are the live tracks; real-time voice is
out of scope.**

| Phase | Goal | Effort | Why now |
|---|---|---|---|
| 0. Audio clips | `FIELD_AUDIO` (§5.9.3) send+receive | ~1–2 days | **SHIPPED 1.2.79** — off-grid voice notes, no real-time-call machinery |
| 1. Un-hide group chat | Productize RRC | ~3–5 days | Highest ROI — already built; nobody else has it |
| B. iOS via CI (parallel) | TestFlight builds | ~1–2 weeks | The structural moat; runs independently |

### Phase 0 — Audio message clips (`FIELD_AUDIO`, key 7) — SHIPPED (Opus) in 1.2.79
Done 2026-06-21, Android-only, Opus/OGG via AOSP `MediaRecorder`/`MediaPlayer`
(no extra dependency, per the FOSS rule):
- Wire: `extractAudioField` / `audioField` / `AudioMode` (full AM_* ladder) +
  round-trip tests.
- Receive: `audioMode` column (Room v17), `withAudio` wired into all 3 inbound
  paths; a tap-to-play `AudioBubble` (Opus plays; Codec2 labelled unsupported).
- Send: attach-menu **Voice** item (API 29+) → `RECORD_AUDIO` → record → ship as
  `FIELD_AUDIO` via `sendAudioMessage` (Resource path; survives queue→drain).
- Followups: **Codec2** encode/decode (needs a bundled FOSS lib) for Sideband
  -over-LoRa interop; a shared `AudioIo` expect/actual + iOS playback/record.
  Needs on-device verification of record/playback/mic-permission (can't be
  unit-tested).

### Phase 1 — Productize group chat (un-hide RRC)
- Remove/relabel the `experimentalRrc` gate; promote Rooms to a first-class tab.
- Ship a **default hub** so a user can join with zero setup (current blocker:
  you must know a hub hash).
- Add the missing client-side **room-creation UI** and basic formatting.
- Harden reconnect/auto-rejoin; add room-mention notifications.
- Files: `RoomsScreen.kt`, `rrc/`, `Preferences.kt`, `ReticulumService.kt`.
  Risk: low — protocol is done. Dependency: a reliable public hub to point at.

### Track B (parallel) — iOS via macOS CI
1. GitHub Actions `macos-latest`: `./gradlew :shared:iosX64Test`, `xcodebuild archive`.
2. Apple Developer enrollment + App Store Connect API key (`.p8`) as CI secrets.
3. `fastlane pilot upload` to TestFlight.
4. Finish/verify `iosMain` actuals (BLE background, `AudioIo` iOS actual, push) —
   CI is the iOS dev loop.

#### iOS↔Android parity status (2026-06-27 sweep)

Closed this pass (all local-only; pending one CI run to compile-verify):
- **Identity vault — was the open security gap.** iOS now seals identity
  private keys with `KeychainIdentityVault` (AES-256-CBC + HMAC-SHA256 under
  a 32-byte master key in the Keychain, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`)
  instead of the pass-through `PlaintextIdentityVault`. Closes the deferred
  half of audit 2026-05-13 HIGH-1. Pre-Keychain installs (raw plaintext in
  the `*Enc` columns) migrate in place on load (32-byte raw vs 97-byte sealed
  blob disambiguates). Keychain key-mgmt is a new `rcr_keychain_get_or_create_key`
  Swift-bridge fn; the seal/unseal envelope has an `iosTest` round-trip + tamper suite.
- **`requestPath` wiring** — iOS now fires an RNS path request when following
  a cross-node Nomad link to an unseen hash (NomadView/ConversationView), matching
  Android's `resolveOrPrepareDestination`. Latency optimisation, not correctness.
- **RNS-format identity export/import** — Settings now offers the unencrypted
  cross-tool RNS export (behind a warning) and auto-detects a 64-byte RNS file on
  import, alongside the existing `.rmid` flow. Mirrors Android `SettingsScreen`.

Known platform limitations (NOT parity gaps — iOS forbids the hardware access):
Bluetooth Classic SPP, USB-serial RNode, and the ALN BLE tunnel are Android-only.

Remaining real gap — **inline voice clips (`FIELD_AUDIO`/Opus-in-Ogg)**:
inbound clips already degrade gracefully on iOS (they render as a saveable
`voice-rec.ogg` file attachment, playable in another app). Full inline
record + playback needs bundling **libopus + libogg** (both BSD/FOSS-OK) as
new cinterop + AVAudioEngine capture/encode/mux — unverifiable on WSL and, per
Phase 0's own note, needs on-device record/playback/mic-permission verification.
Deferred pending a dependency decision + a hardware dev loop, not done blind.

## Known issues / active bugfixes

These are correctness bugs to fix alongside (and ahead of) the phases — a
broken core feature undercuts every competitive argument above.

- **RESOLVED — file attachments (send, pick, and save).** A chain of bugs on
  the attachment path, all fixed 2026-06-21:
  - *Queued sends dropped the attachment* — `drainQueuedOutgoing` re-sent
    without image/file; fixed by reloading from the row (1.2.72).
  - *Compose `ActivityResult` launchers dropped their result on this
    device class* (the host Activity is recreated mid-SAF-pick and the
    per-composition launcher key isn't restored). This silently broke the
    **file picker** (nothing attached), the **save** of a received file
    (0-byte file), the **identity export** (0-byte `.rmid` — a real
    data-loss trap), and **Nomad `/file/` downloads**. Fixed by moving all
    of these to **Activity-level launchers** in `MainActivity`
    (`pickFile` / `saveFile`), with bytes staged in the ViewModel so a
    recreation can't lose them (1.2.76 picker, 1.2.77 save, 1.2.78 export +
    Nomad). Diagnosed live via `adb logcat` (`ReticulumSave`).

## Recommended order

- **Now:** Phase 1 (un-hide group chat) — days of work, a visible win nobody
  else has. Stand up Track B's CI skeleton in the background.
- **Then:** Track B (iOS via CI) as the main structural bet — the throne nobody
  is sitting on.
- Phase 0 (audio clips) is shipped; surface the existing low-friction onboarding
  rather than building a wizard.

Every interim release beats the field on something — group chat, audio clips,
frictionless onboarding — and native iOS is the moat the multi-week voice build
would never have bought us.

## Cancelled / out of scope (cut 2026-06-23)

Recorded so they don't get re-proposed; revisit only on real demand. (Migrated
here from `todo.md`, which was retired 2026-06-26.) Cut to keep the focus on
messaging / protocol / reliability fundamentals.

- **Real-time voice calls (LXST telephony)** — a whole separate subsystem; was
  the former Phase 3. Async audio clips (Phase 0) cover the voice-note use case.
- **Onboarding wizard** (part of an *in-app RNode flasher + onboarding wizard +
  theme editor* polish bundle) — was the former Phase 2; existing auto-identity
  first-run stands.
- Short video messages — impractical airtime over LoRa.
- BLE GATT server / peripheral mode — niche phone-to-phone.
- Location sharing + telemetry-collector role — product expansion, not core messaging.
- Multi-identity management — niche local UX.
- In-app RNode flasher + theme editor — polish bundle.
- APK sharing over local hotspot — out of scope.
- `rncp` inbound — `FIELD_FILE_ATTACHMENTS` already covers file transfer.
- Encrypted migration bundle — `.rmid` already moves the identity.
- iOS cosmetic-parity polish — full emoji picker (6-emoji palette is fine),
  Nodes-row `source=`/"waiting for announce", Nomad "Cached" filter,
  Announce-button feedback.
- iOS agnostic-LoRa-Net (ALN) transport — experimental; revisit when the ALN
  contract stabilises.

Kept deliberately (NOT cancelled): **AutoInterface** / UDP-multicast LAN
discovery — real interop value.

---
*Living document. Update as phases land or priorities shift.*
