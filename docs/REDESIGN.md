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
- Two sections: **Contacts** (saved people, with their latest message)
  then **Other** (messages from senders not in Contacts).
- Rows: avatar + name + last-message preview + timestamp — the
  universal messenger row. No hash on the row.
- An "Add to Contacts" affordance on every *Other* row.
- A **Contacts** entry point in the top bar (people icon) → §Contacts.
- Swipe a row for quick actions (Add to Contacts / mute / delete).

### Contacts (new management surface)
- Reached from the Messages top bar — not a separate bottom tab.
- Lists your saved contacts (avatar, name, short fingerprint).
- **Add a contact:** paste a hash, scan a QR card, or "Add to
  Contacts" from a Nodes row or a received message.
- **Edit:** set the contact name (`userLabel`); optional note.
- **Remove:** drops them from Contacts (the destination stays in
  Nodes — removing a contact never loses message history unless the
  user also deletes the conversation).
- This list is also the foundation for the deferred
  "contacts-only inbound" privacy mode (see `todo.md`).

### Nodes
- Stays the raw discovery view. Name-led rows: status dot + name +
  compact meta line (`RRC hub · 2 hops · 33s`). Short fingerprint as
  a secondary line.
- The mail/pencil/trash cluster → tap row opens the **detail sheet**;
  swipe for quick favourite/delete.
- Filter chips cleaned up into a tidy segmented control.

### Destination detail sheet (shared pattern)
One consistent bottom sheet used from Nodes, Messages and Contacts:
name, **full hash + Copy**, **QR**, public-key-known state, hop
count / last-seen / signal, and the actions (Add to Contacts /
rename / message / delete). This is the single home for all the
"machine" detail pulled off the rows.

### NomadNet browser — opt-in
Folded behind a Settings → Features toggle. It's a polished feature,
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
- **Phase 2 — the structure:** the Contacts management screen;
  Settings reorganisation; the Features toggles + NomadNet/RRC
  opt-in nav; empty states.
- **Phase 3 — the visual system + polish:** tokens applied
  everywhere, Graph decluttering, light-theme parity.

## 9. Open questions (decide during review)

1. Should the **NomadNet browser default to on or off**? Off keeps the
   default lean but costs discovery — the Features screen mitigates it.
2. Is **Contacts** definitely a Messages sub-screen, or does it earn a
   bottom-nav tab once it's a full management surface?
3. Light theme — bring it to full parity in Phase 3, or sooner?
4. Do contacts need a free-text **note** field (new column), or is the
   existing `userLabel` nickname enough for v1?
