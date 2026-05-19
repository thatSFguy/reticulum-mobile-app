# UI redesign proposal

**Status:** proposal — for review, no code yet. Captures the
"the UI is clunky" feedback (received 2026-05-18) and a direction to
fix it. Reviewed/refined with the maintainer before writing.

The protocol layer is solid; this is purely a UI/UX pass. It is also a
*cross-platform* spec — Android (Compose) and iOS (SwiftUI) are
separate UI codebases, so every decision below is described
platform-agnostically and each platform implements it.

---

## 1. Why it reads as "clunky"

Concrete causes, from the current screens:

- **Raw 64-character hashes on every list row** — Nodes, Messages and
  Rooms rows all show the full hex destination hash, wrapping to 2–3
  lines. The single biggest offender: it makes a messenger look like a
  debugger.
- **Triple inline action icons** — node rows cram mail + pencil + trash
  on the right edge: visual noise, small touch targets.
- **Red used for non-errors** — the "Welcome to the Server" notice
  renders in an error-red banner. Red must mean *error* only.
- **Bottom-nav icon collision** — Messages and Rooms use the *same*
  envelope icon.
- **Machine data foregrounded** — rows lead with `rrc.hub`, hop counts
  and hashes where a *name* should lead.
- **Settings is a wall of prose** — every option carries a full
  explanatory paragraph inline, in one long scroll.
- **Bare empty states**; the **Graph** labels overlap into mush.

## 2. Design principles

1. **Lead with the human, hide the machine.** Rows show name + a
   one-line status. Technical detail (full hash, public key, hop
   count, QR) lives one tap away in a detail sheet.
2. **One primary action per row.** Tap = open. Favorite / rename /
   remove move to swipe actions or a long-press menu.
3. **A real design system.** One spacing scale, one type scale, one
   accent, semantic colours — red = error only; a neutral/blue tone
   for informational notices.
4. **Progressive disclosure.** Short labels; explanations on demand;
   advanced/diagnostic options collapsed.
5. **Lean by default, powerful on opt-in.** The default app is a
   messenger; advanced surfaces are opt-in.

## 3. Terminology: Favorites → Contacts

"Favorite" becomes **Contact** everywhere — it's the word users
expect, and it implies a *managed list*, not just a pin.

- The star icon → a person / person-add icon.
- "Favorite / unfavorite" → "Add to Contacts / Remove from Contacts".
- **Nodes ≠ Contacts.** Nodes stays the raw mesh-discovery view
  (every destination the network has surfaced, including telemetry
  beacons, relays, infrastructure). **Contacts** is the curated list
  of people you've deliberately saved. Two clean mental models:
  *Nodes = the network, Contacts = your people.*
- Storage already supports this — `StoredDestination.favorite` is the
  membership flag, `userLabel` is the contact's name. No schema change
  needed; this is naming + a management surface.

## 4. Hashes stay first-class

Hashes are how you verify *who* you're talking to — they must stay
accessible, just not dominate every row.

- **On a row:** a short fingerprint, e.g. `7579c857…d75a3315`
  (first 8 + last 8) — one line, no wrap, enough to recognise a peer.
- **In the destination detail sheet:** the **full hash** in monospace,
  a **Copy** button, and the **QR code** — the proper place to verify
  identity or share your address. (This mirrors how Signal surfaces
  safety numbers: always reachable, on their own screen.)

## 5. Navigation

The bottom bar is slimmed and made opt-in-extensible:

- **Default tabs:** Messages · Nodes · Settings.
- **Opt-in tabs**, each enabled from Settings → Features and only then
  shown in the bar:
  - **NomadNet browser** (gated like the existing `experimentalRrc`
    flag — see §6).
  - **Relay Chat** (existing `experimentalRrc` gate).
- Every tab gets a **distinct icon** — fixes the Messages/Rooms
  envelope collision.
- Graph and Map are *not* tabs — they are a view-switcher inside the
  Nodes tab (already partly the case on Android).

## 6. Screen-by-screen

### Messages
One unified conversation list — Signal-style, no Contacts/Inbox
split (revised 2026-05-18; supersedes the earlier two-section +
separate-Contacts-screen design).
- **Recency-sorted** — most-recent conversation on top.
- **Pinned** conversations stick to the top under a "Pinned" header;
  the rest fall under "Recent". Pinning is its own concept, separate
  from the contact (`favorite`) flag, stored as a local pinned-hash
  set in Preferences (no DB migration).
- A **search bar** filters by name or hash.
- Rows: avatar + name + short fingerprint. Tap = open conversation;
  long-press = the shared detail sheet (which carries Pin / Unpin,
  Add-to-Contacts, rename, delete).
- Starting a *new* chat is unchanged — open a node on the Nodes tab
  and tap Message.

### Contacts
*No separate Contacts management screen* — the Signal-style Messages
list above replaced it (the cost/benefit didn't justify another
screen). "Contact" survives only as the `favorite` flag: the
detail-sheet "Add to Contacts" action and the Nodes "Contacts"
filter. That flag is still the intended foundation for a future
"contacts-only inbound" privacy mode (see `todo.md`).

### Nodes
- Stays the raw discovery view. Name-led rows: status dot + name +
  compact meta line (`RRC hub · 2 hops · 33s`). Short fingerprint as
  a secondary line.
- The mail/pencil/trash cluster → tap row opens the **detail sheet**;
  swipe for quick favourite/delete. *(Done — Phase 1.)*

**Nodes header declutter (Phase 2).** The top of the tab currently
stacks *three* full-width control rows before the first node:
1. the `Nodes | Graph` view toggle,
2. a search field + a Contacts-only filter icon + an `add (+)` button,
3. the filter chips (`Messagable | All | Telemetry | RRC`).

Collapse this to roughly one row:
- **Search** hides behind a search icon that expands to the field on
  tap — recovers a whole row when not searching.
- The **Contacts-only** filter stops being a separate person-icon
  toggle and becomes a chip in the filter row (`Contacts | Messagable
  | All | Telemetry | RRC`).
- **Add-by-hash (`+`)** moves into an overflow menu (or is reached via
  the QR-scan / detail-sheet flow) — it's a rare action.
- The `Nodes | Graph` toggle stays but slimmer.
- Result: 3 stacked rows → ~1, with a search icon + overflow on the
  same line as the view toggle.

### Destination detail sheet (shared pattern)
One consistent bottom sheet used from Nodes, Messages and Contacts:
name, **full hash + Copy**, **QR**, public-key-known state, hop
count / last-seen / signal, and the actions (Add to Contacts /
rename / message / delete). This is the single home for all the
"machine" detail pulled off the rows.

### NomadNet browser — opt-in
Folded behind a Settings → Features toggle, **default off**. It's a
polished feature,
so the toggle must be *discoverable*: the Features screen shows a
short blurb + thumbnail per feature, not a bare switch.

### Relay Chat — opt-in
Already gated (`experimentalRrc`); just surfaced in the same
Features screen, and given its own distinct nav icon.

### Settings — reorganised
From one long prose-scroll to a **grouped index that drills into
focused sub-screens** (the iOS-Settings model):
- **Connection** — transports (BLE / Bluetooth / USB / TCP), radio
  config, auto-reconnect.
- **Identity** — your identity, display name, QR card, export /
  import, reset.
- **Features** — NomadNet browser & Relay Chat toggles (§6).
- **Privacy & security** — drop-unverified, (future) contacts-only
  inbound.
- **Appearance** — theme.
- **About & diagnostics** — version, log, links.
- Each index row: short label + one-line subtitle. The long
  explanatory paragraphs move *into* the sub-screen or an "ⓘ" — never
  inline on the index.

### Empty states & Graph
- Empty states: an icon + one-line explanation + a primary action,
  not bare text.
- Graph: label only hub nodes by default; other labels appear on
  zoom/tap, to end the overlap.

## 7. Visual system

A small token set both platforms share:
- **Colour:** dark + light; one accent (the app green `#1d9e75`);
  semantic — `error` red, `warning` amber, `info` blue/neutral,
  `success` green. Red is *never* decorative.
- **Spacing:** an 8-pt scale (4 / 8 / 12 / 16 / 24 / 32).
- **Type:** ~5 steps (title / headline / body / label / caption).
- **Components:** one row style, one card, one bottom sheet, one
  empty-state, one chip — defined once, reused everywhere.

## 8. Phasing

A full redesign across two native UIs is large; do it in order of
impact-per-effort:

- **Phase 1 — kills ~80% of the "clunky" impression, mostly
  mechanical:** distinct nav icons; recolour notices (red → semantic);
  short fingerprint on rows + the shared detail sheet; consolidate row
  actions into swipe/long-press; Favorites → Contacts rename.
- **Phase 2 — the structure:** the Signal-style Messages list
  (recency sort + pins + search — replaced the separate Contacts
  screen); Settings reorganisation; the Features toggles + NomadNet/
  RRC opt-in nav; the Nodes header declutter (3 control rows → ~1,
  see §6 Nodes); light-theme parity (with the semantic colour
  tokens); empty states.
- **Phase 3 — the visual system + polish:** tokens applied
  everywhere, Graph decluttering.

## 9. Decisions (resolved 2026-05-18)

1. **NomadNet browser defaults to off** — opt-in via Settings →
   Features. The Features screen carries the discovery weight.
2. **Contacts is a Messages sub-screen**, not a bottom-nav tab —
   Signal-style: reached from a compose / contacts action in the
   Messages top bar.
3. **Light-theme parity is pulled earlier** — into Phase 2, alongside
   the semantic colour tokens that Phase 1 already needs for the
   notice recolour.
4. **No note field.** The existing `userLabel` nickname is enough for
   v1 — no new DB column.

## 10. Android shipped — iOS parity checklist

As of **android-v1.2.0** (2026-05-18) Phases 1–2 are complete on
Android. The work below brought iOS to parity — Android reference
files are noted for cross-checking. iOS is SwiftUI (`iosApp/`);
reproduce the *behaviour*, not the Compose code.

**Status (2026-05-19): all §10 items implemented on iOS** — shared
`DestinationDetailSheet`, row consolidation across Nodes/Messages/
Rooms, Favorites→Contacts, Nodes header declutter + per-type avatars,
Settings index→sub-screens, Features toggles + opt-in nav, Signal-
style Messages, empty states, and the bug-fix batch (first-launch
routing, off-main identity KDF, portrait QR scanner, Nodes-first nav).
Pending build verification + `ios-v*` tag on macOS.

### Detail sheet (Phase 1 item 4)
- [x] A shared destination detail sheet — a SwiftUI `.sheet`. Content
  top→bottom: avatar + name + "appName · N hops"; a one-line `HASH`
  label + full hash + a compact copy-icon button; divider; the action
  buttons; a "Details & QR code below" hint; divider; facts (Public
  key known?/Last seen/Signal — Signal hidden when RSSI is null);
  divider; "ADDRESS QR CODE" + a ~140pt QR of the hash.
- [x] Actions, in order: Message (or "Open in Relay Chat" for an
  `rrc.hub`); Pin to top / Unpin (Messages only); Add / Remove from
  Contacts; Add / Edit nickname; Delete (destructive styling, but the
  same shape as the other buttons).
- [x] The sheet wraps its content — no forced full-screen height.
- Android: `ui/screens/DestinationDetailSheet.kt`.

### Row consolidation (Phase 1 item 5)
- [x] Nodes rows: tap opens the detail sheet; remove the inline
  mail/pencil/star/trash cluster; keep a trailing chevron.
- [x] Messages rows: tap opens the conversation, long-press opens the
  detail sheet; remove the inline star/trash.
- [x] Rooms hub & room rows: long-press deletes (→ confirm dialog);
  remove the inline trash. Room rows keep the inline Join/Leave button.

### Favorites → Contacts (Phase 1 item 6)
- [x] User-facing "favorite" → "contact"; star icon → person icon.
  The storage field stays named `favorite`.

### Nodes header declutter (Phase 2)
- [x] One header row: the Nodes/Graph toggle + a search icon (expands
  to a field on tap) + an overflow menu. The menu has two sections —
  **Add** (Add by hash, Scan QR) and **Filter** (Contacts, Messagable
  [default], All, Telemetry, RRC — a check on the active one). The
  filter-chip row is removed; "Contacts" is a filter preset.
- [x] A round per-type avatar on each Nodes row: person =
  `lxmf.delivery`, list = `rrc.hub`, info = `nomadnetwork.node`,
  pin = everything else.

### Settings reorganisation (Phase 2)
- [x] Settings becomes a grouped index that drills into sub-screens:
  Connection, Identity, Features, Privacy & security, Appearance,
  About & diagnostics. Index rows: label + one-line subtitle +
  chevron; sub-screens have a back header.
- [x] The Appearance sub-screen holds a System / Light / Dark theme
  picker (a 3-way segmented control). iOS already has a theme
  preference — move it into this sub-screen rather than
  re-implementing it.

### Features toggles + opt-in nav (Phase 2)
- [x] A `nomadEnabled` preference (default **off**) gates the Nomad
  tab, mirroring the existing `experimentalRrc` gate for Rooms. The
  default tab bar is Nodes · Messages · Settings; Nomad / Rooms appear
  only when their feature is enabled.
- [x] The Settings "Features" sub-screen carries both toggles
  (NomadNet browser, Reticulum Relay Chat) with a short blurb each.

### Signal-style Messages (Phase 2)
- [x] One unified conversation list — no Contacts/Inbox split. Sorted
  by last-message time; pinned conversations under a "Pinned" header,
  the rest under "Recent"; a search bar filters by name/hash.
- [x] Pin is a *separate* concept from the contact flag — a local
  pinned-hash set in preferences (no DB column). The detail sheet's
  "Pin to top" / "Unpin" action drives it.
- [x] A refresh icon beside the search bar runs the propagation
  auto-sync — a spinner while it runs, then a short result line
  ("Synced — N new" / "nothing new" / "Sync failed"; auto-clears).

### Empty states (Phase 2)
- [x] A shared empty-state view — muted icon + one-line text +
  optional action button — on Messages, Nodes and Rooms.

### Light-theme parity (Phase 2)
- [x] Ensure the light theme explicitly sets the secondary /
  tertiary / outline / on-surface-variant roles (don't leave them at
  the platform default) so the tab bar and sheets don't pick up a
  stray tint.

### Bug fixes & smaller items
- [x] First launch lands on Settings → Connection (an empty Messages
  list is useless before a transport is attached). One-shot flag.
- [x] Identity export/import: run the archive KDF **off the main
  thread** — it was freezing the UI / ANR-ing — show a blocking
  progress spinner, never leave a 0-byte export file, and show a
  success message. **Audit the iOS export/import for the same
  main-thread KDF.**
- [x] Lock the QR scanner to portrait.
- [x] Bottom-nav order: Nodes is the leftmost tab.
- [x] Tint the system status / navigation bars to the app
  background so they blend with the theme (no stray grey band).
  Android does it in `ReticulumTheme`; on iOS set the status-bar
  style to match light/dark.

### iOS implementation note
New Swift files must be registered in `iosApp/iosApp.xcodeproj`
(`project.pbxproj`) — there are no file-system-synchronised groups.
Either edit the pbxproj, or add new composables/views to an existing
in-target file (how the shared `shortHash` helper was added).

*This checklist (android-v1.2.2) is the complete iOS parity spec —
a fresh session needs only this file + the named Android sources.*
