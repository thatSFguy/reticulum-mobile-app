# Reticulum Mobile App — Competitive Roadmap

> Strategy and phased implementation plan for making this the default native
> Reticulum client. Grounded in a codebase + spec audit (2026-06-21) and a
> competitive scan of Sideband, MeshChatX, Columba, and Ratspeak.

## The strategy in one sentence

Don't try to be a better Sideband. Be the **native, zero-config, voice-capable,
truly cross-platform (iOS included)** Reticulum client — own the position
Columba is reaching for but can't follow us to (iOS), and that Sideband,
MeshChatX, and Ratspeak are each structurally unable to occupy.

## The field (what we're actually up against)

| App | What it is | Its moat | Its structural weakness |
|---|---|---|---|
| **Sideband** | The protocol author's reference client (Kivy/Python) | First to every wire feature — LXST voice, Codec2-over-LoRa, telemetry, remote-command | Non-native UX, battery, background reliability; iOS is a 3rd-party fork |
| **MeshChatX** | Desktop/web maximalist (Node + Chromium fork of MeshChat) | Everything: voicemail, phonebook, ringtones, multi-identity, page archiving | Not mobile-native — voice runs through Chromium; mobile = run a server |
| **Columba** | Our mirror image — native Android, Material 3, BLE/WiFi/RNode/TCP, LXMF+LXST | Already occupies the "clean native mobile" niche; has press | Android-only; young |
| **Ratspeak** | An ecosystem — client + T-Deck/Cardputer hardware + a token | Hardware + community + games | A token polarizes the privacy crowd; thinnest client |

## The brutal truth & the throne

- **Today we lose the comparison table on voice.** Three of four ship LXST
  calls; Sideband does Codec2 audio-over-LoRa. We have *zero* audio/voice code.
  This is priority zero — everything else is polish on a client that "can't call."
- **The unclaimed throne is native iOS.** Our KMP architecture (real `iosMain`
  actuals) is the one asset none of them can cheaply match. Columba is
  Android-only; Sideband-iOS is a fork; MeshChatX is desktop. A great native iOS
  LXMF+LXST client is a throne nobody is sitting on.

## What the audit changed (the surprises, all in our favor)

1. **Group chat is already built — just hidden.** Full RRC (IRC-style, CBOR over
   a Link to a hub; the kc1awv/rrcd standard) ships today behind the
   `experimentalRrc` flag. No competitor has good group chat. Cheapest leapfrog
   in the deck.
2. **Onboarding is ~70% done.** Identity auto-creates; first launch lands on
   Connect; `KnownTcpNodes` pre-fills a public TCP node. Needs a wizard +
   auto-connect, not a build.
3. **The voice path is half-wired and has a Kotlin reference.**
   `LinkSession.sendData()` + the `onLinkData` callback are what live audio needs;
   the Resource sender is complete; Columba is open-source Kotlin doing LXST via
   `lxst.telephony.Telephone` — a clean-room reference for the hard part.

## The Mac problem — solved without a Mac

KMP iOS can't compile on Windows/WSL, but TestFlight does **not** require a
physical Mac:
- GitHub Actions **macOS runners** build + test + archive.
- Apple Developer Program ($99/yr), enrollable from iPhone/web.
- Sign with an **App Store Connect API key** (`.p8`, generated in the web console).
- `xcodebuild` + `fastlane pilot` uploads to TestFlight from CI.

iOS becomes a parallel CI track, not a blocker — we just never open Xcode.

## Phased plan

Critical path: **voice is the long pole; ship the cheap leapfrogs alongside it.**

| Phase | Goal | Effort | Why now |
|---|---|---|---|
| 0. Audio clips | `FIELD_AUDIO` (§5.9.3) send+receive | ~1–2 days | Closes a checkbox *and* seeds the codec layer voice needs |
| 1. Un-hide group chat | Productize RRC | ~3–5 days | Highest ROI — already built; nobody else has it |
| 2. Onboarding wizard | Install→talking in 60s | ~1 week | Mainstream wedge; 70% already there |
| 3. LXST voice | Real-time calls | ~3–5 weeks | The parity gate; de-risked by Columba reference |
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
  -over-LoRa interop; a shared `AudioIo` expect/actual + iOS playback/record;
  in-call/streaming reuse for Phase 3 (LXST). Needs on-device verification of
  record/playback/mic-permission (can't be unit-tested).

### Phase 1 — Productize group chat (un-hide RRC)
- Remove/relabel the `experimentalRrc` gate; promote Rooms to a first-class tab.
- Ship a **default hub** so a user can join with zero setup (current blocker:
  you must know a hub hash).
- Add the missing client-side **room-creation UI** and basic formatting.
- Harden reconnect/auto-rejoin; add room-mention notifications.
- Files: `RoomsScreen.kt`, `rrc/`, `Preferences.kt`, `ReticulumService.kt`.
  Risk: low — protocol is done. Dependency: a reliable public hub to point at.

### Phase 2 — 60-second onboarding
- 3-step first-run wizard: identity (already automatic — surface it) → one-tap
  "Join the public network" (auto-connect the pre-picked `KnownTcpNodes` host) →
  "say hi" with a seeded first contact so the screen isn't empty.
- Keep the server-trust disclosure (MED-5) as "Learn more," not a wall.
- Files: new onboarding composables, `MainActivity.kt`, `SettingsScreen.kt`.
  Risk: low.

### Phase 3 — LXST voice calls (the gate)
**Spec discipline first (CLAUDE.md RULE #1):** LXST is *not* in `SPEC.md`. Fall
back to Columba's Kotlin `lxst.telephony` + upstream Python, and per RULE #0
**document the reverse-engineered wire format and surface it to the user for the
spec agent** (don't edit the spec).
1. Codec: Codec2 (450–3200 bps for LoRa) + Opus FDX/HDX, in the Phase 0 layer.
2. Signaling: an `lxst.telephony` destination + call state machine
   (offer→ring→accept/decline→active→hangup), modeled on Columba's `Telephone`.
3. Media: stream encoded frames via existing `LinkSession.sendData()` — no link
   changes needed.
4. **Key risk — PROOF overhead.** Inbound link DATA triggers a mandatory 96-byte
   PROOF per packet (`LinkSession.kt`, spec §6.5). At ~50 fps that's a heavy
   return path. **Prototype this in week 1** before committing the architecture:
   evaluate fewer-but-larger frames, §6.8 Channel mode, or a voice context that
   suppresses per-packet proofs (verify against Columba; spec-document it).
5. UX: full-screen call screen, CallStyle notification, mic permission (infra
   exists, unused), echo/jitter buffer.
   Risk: high but bounded by the Columba reference.

### Track B (parallel) — iOS via macOS CI
1. GitHub Actions `macos-latest`: `./gradlew :shared:iosX64Test`, `xcodebuild archive`.
2. Apple Developer enrollment + App Store Connect API key (`.p8`) as CI secrets.
3. `fastlane pilot upload` to TestFlight.
4. Finish/verify `iosMain` actuals (BLE background, `AudioIo` iOS actual, push) —
   CI is the iOS dev loop.

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

- **This week:** Phase 0 + Phase 1 in parallel (days each, visible wins, Phase 0
  seeds the codec for Phase 3). Stand up Track B's CI skeleton in the background.
- **Then:** Phase 2 while spiking Phase 3's PROOF-overhead question.
- **Then:** Phase 3 as the main project, architecture gated on that spike.

Every interim release beats the field on something — group chat, audio clips,
frictionless onboarding — before the multi-week voice build even lands.

---
*Living document. Update as phases land or priorities shift.*
