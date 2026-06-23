# TODO

Outstanding work that's not blocking but shouldn't be lost.

## Reaction attribution through a re-originating relay — fwdsvc convention

App↔fwdsvc wire convention (an **application convention**, deliberately
NOT in SPEC.md — per the spec-agent decision: a custom field is built
for exactly this and can't collide with a future upstream `0x40`-dict
allocation).

**Problem.** Reactions are `FIELD_REACTION 0x40` (SPEC §5.9.8), which
carries no reactor identity — attribution is the carrying LXMF's
`source_hash`. The Go forwarding service re-originates each message
(unpacks, prepends `[Nick] ` to text, **re-signs as fwdsvc**, re-emits
with `source_hash = fwdsvc`). Text keeps authorship via the nick
prefix, but a reaction has an empty body, so its reactor is lost — every
relayed reaction would aggregate onto the fwdsvc.

**Convention (value = `source_hash`).** When the fwdsvc re-originates a
reaction it stamps the original reactor's **`source_hash`** in LXMF
custom fields (SPEC §5.9.1 keys). The cross-client convention settled on
`source_hash`, NOT identity hash — fwdsvc v1.10.2, Columba, and the
shared `docs/reaction-attribution.md` all agree; `source_hash ↔ identity
hash` is bijective for `lxmf.delivery`, so the app reconciles on its
side rather than re-forking the wire convention.

```
fields[0xFB]  FIELD_CUSTOM_TYPE  = "originator-identity"  (UTF-8)
fields[0xFC]  FIELD_CUSTOM_DATA  = <reactor source_hash, 16 raw bytes>
```

- `source_hash` = the reactor's `lxmf.delivery` **destination** hash
  (16 bytes), NOT its identity hash.
- **Receiver (per `docs/reaction-attribution.md` §Security), all MUSTs:**
  1. Validate `0xFC` is exactly 16 bytes (32 hex) — reject malformed.
  2. **Trust gate** — honor the stamp only when the reaction arrived via
     the relay that delivered the reacted-to message (carrying
     `source_hash == target.arrivedViaDest`); else ignore it (the stamp
     is unauthenticated → anyone could forge a reactor). Fall back to the
     carrying source.
  3. Resolve the honored `source_hash` to an **identity hash** before
     aggregating — never key a reaction by a destination hash.
- Spec-compliant peers ignore the custom fields — fully interoperable.

**App side: done** (parse + resolve + gate, no emit) — `ReticulumEngine.kt`
`ORIGINATOR_STAMP_TYPE`, `extractReactionOrReply` (16-byte validation),
`resolveReactor` (trust gate + dest→identity resolution) at all three
inbound reaction sites; tests in `ReactionOrReplyTest.kt` +
`ResolveReactorTest.kt`. The app never emits the stamp (on a direct send
`source_hash` already IS the reactor).

- [x] **fwdsvc side:** stamps `source_hash` as of v1.10.2 (§9.1).
      Confirm the type string matches `ORIGINATOR_STAMP_TYPE`
      (`"originator-identity"`) exactly, and the value is the raw 16-byte
      `source_hash` — else the app's trust gate / resolution drops it.

## NomadNet browser follow-ups (post-v0.1.67)

The Phases 1-4 audit-driven sweep landed in v0.1.53..v0.1.67 with
upstream parity for: REQUEST envelope, request_id verification,
Resource size cap + bz2 cap, cross-node + shorthand link routing,
checkbox/radio layout, HR variants, per-line escape, heading-with-
field demote, line-break preservation, field-name + link-target
sanitization, input-length cap, var_<name> URL params, checkbox
unchecked-omit, page-level `#!c=` `#!bg=` `#!fg=` headers, tables,
LINKIDENTIFY opt-in, link reuse, history stack, LazyColumn,
partials. Three items remain:

- [x] **`/file/` downloads with Resource metadata. ✅ 2026-06-23 VERIFIED SHIPPED** — `Resource.assemble` extracts the metadata-prefix on `hasMetadata` (flag `0x20`, `Resource.kt`), `LinkSession` surfaces `metadata["name"]`, and the SAF download flow is present on both platforms (confirmed in the 2026-06-23 parity audit). Original plan:
      `Node.py:128-141` returns `[BufferedReader, {"name": <bytes>}]`
      for `/file/` paths; the bytes go through the §10 Resource
      pipeline and the metadata is BOTH inserted as a 3-byte
      length-prefixed msgpack blob before the random_hash
      (SPEC.md §10.2 step 1) AND surfaced as a `(name, data)` 2-list
      response. Client side needs:
      1. `Resource.assemble` to extract the metadata-prefix when
         `advertisement.hasMetadata == true`, returning
         `(metadata: Map<String, Any>, data: ByteArray)`.
      2. Engine: new `fetchNomadFile(destHash, path):
         Result<DownloadedFile(filename, bytes)>` that detects the
         `/file/` path prefix and uses the metadata extraction.
      3. UI: when the user taps a `/file/` link in NomadScreen,
         use Android's Storage Access Framework
         (`Intent.ACTION_CREATE_DOCUMENT`) to let the user pick a
         destination, then write the bytes there. Show progress
         during fetch (the same Resource progress callback
         we already surface in the link diagnostic).

- [ ] **True multi-select checkboxes.** Per Browser.py:230-236,
      multiple checkboxes sharing a single field name comma-join
      their values when submitted. Pre-v0.1.67 our `fieldValues`
      is `Map<String, String>` so two same-named boxes overwrite.
      Real upstream pages mostly use boolean checkboxes; this is
      a low-priority edge case but worth fixing for chatroom
      member-pickers etc. Change `fieldValues` to
      `Map<String, MutableList<String>>` and update
      buildSubmitData to comma-join.

- [ ] **Partial periodic refresh on link reuse.** v0.1.67 partials
      do periodic refresh per upstream's `partial_refresh` field,
      but each refresh fires through `viewModel.fetchNomadPageNow`
      which holds the link reuse cache. Confirm under load that
      the loop doesn't accidentally tear down + re-establish the
      shared link — should "just work" given v0.1.66's reuse logic
      keys on destHash, but worth a manual test against a real
      chatroom page.

## Tests

- [x] **2026-05-13 PARTIALLY SHIPPED — 2 of 3 `EngineSendBugTest`
      cases unignored.**
  - ✅ `concurrent sendMessage calls produce distinct msgIds` — fixed
    with `advanceTimeBy(2000) + runCurrent()` instead of
    `advanceUntilIdle()`.
  - ✅ `attach resets the announce throttle` — fixed with
    `runCurrent()` instead of `advanceUntilIdle()`.
  - ❌ `transport-send-throws marks message failed and logs exception
    class` — re-ignored with a clarified comment. This one isn't a
    coroutine-plumbing issue: `broadcast()` now wraps each transport
    in `runCatching.onFailure(log; no rethrow)`, swallowing the
    exception. It's a behavioral question (should one transport
    throwing fail the message?) not a test-harness one.

## UI

- [x] **2026-05-13 SHIPPED — Announce stream: show "last announced"
      age per node.** Already in place via the Nodes-row `meta` line
      ("seen Xm ago"), formatted by `formatAge(ageMs)` in
      `NodesScreen.kt:292` / `NodesView.swift` `meta`.

- [x] **2026-05-13 SHIPPED — Announce stream: message without
      favoriting.** Row name-tap already opened a conversation, but
      the gesture was invisible. Added an explicit envelope
      `IconButton` (Android `Icons.Default.Email`,
      `NodesScreen.kt:330`) and `Image(systemName: "envelope")` (iOS,
      `NodesView.swift:199`) next to the favorite star for messagable
      rows. Commit `795a281`.

- [x] **Durable fix for the recurring "composer covers the last message"
      bug (issue #30). ✅ 2026-06-23 IMPLEMENTED (Android; compiles;
      pending sideload verify).** Android done via `reverseLayout = true`
      + `bubbles.asReversed()` on the message `LazyColumn`, and the
      one-shot `snapshotFlow` scroll pin (+ its import) was deleted —
      the list is now bottom-anchored structurally. iOS half
      (`.defaultScrollAnchor(.bottom)`) still pending (needs a Mac).
      Symptom was: enter a conversation
      with many messages and the last bubble — unless it's long — is
      tucked behind the input box. Fixed three times already
      (`f15662b` → `d3e8065` → `22a9138`, all one-shot
      `scrollToItem`-to-bottom patches). The 2026-06-23 investigation
      confirmed the current `snapshotFlow` pin
      (`MessagesScreen.kt` ~526–559) is byte-identical to the last fix
      and the layout/host `Scaffold` insets are correct — so it's NOT a
      reverted change. Root cause is the pin being **one-shot**: it
      fires when the bubble count grows, but rows below the fold finish
      measuring *after* that (async image decode, reply-quote previews,
      reaction chips, resource-progress glyph) and push the newest bubble
      down behind the composer with no re-pin. (Short last message →
      fully hidden; long one → partly visible, which matches the report.)

      **Durable fix (chosen over another one-shot patch because the blast
      radius keeps growing): anchor the list to the bottom structurally.**
      - Android `MessagesScreen.kt` ConversationView: set
        `LazyColumn(reverseLayout = true)`, render
        `items(bubbles.asReversed(), key = { it.id })` (index 0 = newest,
        at the bottom), and **delete** the whole `LaunchedEffect(listState)`
        snapshot-flow pin + comment (~526–559). `listState` is used
        nowhere else but `state =`; no index/ordering deps in the items
        block, so reversing is safe. Newest then stays pinned through
        keyboard-open, async row growth, and entry — structurally, no
        scroll hacks. Net ≈ +2 / −35 lines, one file.
      - iOS `ConversationView.swift`: same bug class — it uses
        `ScrollViewReader` + `.onChange(messages.count){ scrollTo(messages.last.id) }`,
        which is both the one-shot pattern AND targets `messages.last`
        (can be a *filtered-out* row — the iOS analog of the #30
        "scroll past the rendered end" bug). Replace with iOS-17
        `.defaultScrollAnchor(.bottom)` on the `List` (deployment target
        is 17.0). Compile-unverified without a Mac.
      - No shared/engine/DB/model changes — UI layer only, 2 files.

      Verify on device (the real cost, not the LOC): busy convo with a
      **short** last message; last message is an **image** (async
      decode); open the keyboard; receive a message while at bottom and
      while scrolled up; check a sparse 2-message thread (reverseLayout
      packs it at the bottom near the composer — standard chat UX but a
      visible change). Effort ≈ 1–1.5 h edits + Android sideload verify
      (+ a Mac build for the iOS half).

## Investigations

- [x] **2026-05-13 SUSPECTED FIX shipped — UI/state bug: Settings shows
      "Disconnected" while two TCP sockets to MichMesh are
      ESTABLISHED.** Root cause analysis from
      `ReticulumService.kt`: each transport supervisor (`startTcp`,
      `startBle`, `startBtClassic`) had two cancellation hazards:

      1. **Orphan socket leak.** The supervisor's `try { val transport
         = TcpInterface(...); transport.connect(); ...
         currentTransports[kind] = transport; ... }` declared the
         local `transport` *inside* the try. If `cancelConnect(kind)`
         landed between `transport.connect()` finishing and the
         `currentTransports[kind]` assignment, the catch block did
         `currentTransports[kind]?.disconnect()` → null. The local
         reference fell out of scope; the OS-level ESTABLISHED TCP
         socket / BLE GATT then leaked until GC finalized the
         wrapper. Two cancellation races in a row leaves two
         ESTABLISHED sockets, which matches what `ss -tn` showed.

      2. **Clobber of the replacement transport.** If a cancelled
         supervisor's catch block ran AFTER a fresh `startTcp` had
         already done `currentTransports[kind] = transport2;
         engine.attach(transport2, kind)`, the cancelled supervisor
         executed `engine.detach(kind)` + `currentTransports.remove(kind)`,
         pulling transport2 out from under itself. Settings then
         reads `engine.connections` as empty → renders "Disconnected"
         while the new socket is happily ESTABLISHED in the
         background.

      Fix in both directions: hoist `var transport` to the
      while-loop scope so the catch always sees the local
      reference (closes the orphan), and identity-guard the
      detach/remove pair with `if (currentTransports[kind] ===
      transport)` so a cancelled supervisor can't clobber a fresh
      one. The logcat-mirror commit (`33f9279`) is still useful for
      verifying — sideload, reproduce by spam-tapping Connect TCP,
      and confirm `ss -tn` no longer shows orphan sockets after a
      cancellation race.

- [x] **2026-05-03 PARTIAL FIX:** the announce-visibility half is
      resolved. `Identity.rotateRatchet()` now runs on every
      `sendAnnounce`, so consecutive announces carry distinct ratchet
      pubs. After installing the fix and reconnecting through the TCP
      sniffer proxy:
      ```
      07:13:21 announce #1, ratchet d990ea060a6af0427bbd4ab83c14e771...
      07:14:23 announce #2, ratchet 2b24c0db47557f7a74d3d48a026f2ff9...   (different!)
      ```
      and the controlled receiver (sibling client on chicagonomad)
      logged BOTH:
      ```
      07:13:22 Valid announce for <d76006…> 2 hops away ... Remembering ratchet <f31b3a60c1fa84090d82>
      07:14:24 Valid announce for <d76006…> 2 hops away ... Remembering ratchet <4763e0b345fd7aa676e4>
      ```
      Compare to the pre-fix log where every retry of `path?` got
      "Ignoring path request, no path known" — now the path is known.

- [x] **2026-05-03 RESOLVED in v0.1.40 — DATA transit fix.** Root cause
      was NOT a public-rnsd-blocks-DATA policy (the prior speculation
      below). Localized via offline replay-decrypt: our outbound crypto
      was correct, but every DATA we sent was HEADER_1 with no
      transport_id. Upstream RNS Transport.py:1497 forwards inbound
      DATA only when transport_id != None; HEADER_1 DATA from a leaf to
      a non-locally-attached destination got its hash added to the
      dedup hashlist and silently dropped. Sideband works because it
      runs through a shared instance which performs the §2.3
      conversion; we're a direct TCP leaf and have to do it ourselves.

      Fix landed in v0.1.40 (commits 2032dd9, fe735bf, 8828602, eef4d55):
      - protocol/Packet.kt: buildPacket emits HEADER_2 wire layout
        when given a transport_id
      - store/Models.kt + Room schema v5: StoredDestination.nextHop
        captures pkt.transportId from inbound HEADER_2 announces
      - engine/ReticulumEngine.kt: handleAnnounce stores
        hopCount = pkt.hops + 1 (matches upstream Transport.inbound:1395
        +=1 semantic) and persists nextHop; sendMessage emits HEADER_2
        with the cached nextHop when hopCount > 1 && nextHop != null

      End-to-end verified 2026-05-03 against tools/test_lxmf_receiver.py
      via local transport node: send → proof in 8ms, sig=OK at receiver.

## Spec compliance gaps (still open)

These are spec items the §2.3 fix didn't cover. None are blocking
opportunistic LXMF delivery now that v0.1.40 is in.

- [x] **2026-05-03 RESOLVED in v0.1.45 — §12.5.2 link packets need DEST_LINK.**
      After v0.1.43 fixed LINKREQUEST routing through transit, the link
      handshake completed (LRPROOF returned) but the page request still
      timed out. Spec §12.5.2 (verified via nomad node loglevel=7):
      packets addressed to a link_id must have `dest_type = LINK (0x03)`,
      otherwise the relay's `link_table[link_id]` lookup never fires and
      the relay falls through to `path_table` (which doesn't have the
      link_id) and silently drops. We were sending all link-context DATA
      with the buildPacket default `DEST_SINGLE`. Fixed sites:
      - `LinkSession.request()` → CTX_REQUEST DATA
      - `LinkSession.handlePacket(LRPROOF)` → CTX_LRRTT DATA emitted on
        proof success — without this the responder never transitions
        the link to ACTIVE
      - `PropagationClient.identify()` → CTX_LINKIDENTIFY DATA
      Already-correct sites: ResponderLinkSession KEEPALIVE pong,
      sendPacketProof, ReticulumEngine LRPROOF emit, and
      LinkSession.finalizeResource RESOURCE_PRF. Test in
      LinkSessionTest now asserts `parsed.destType == DEST_LINK` on the
      outbound REQUEST packet.

- [x] **2026-05-03 RESOLVED in v0.1.43 — §2.3 LINKREQUEST conversion.**
      Same shape as the v0.1.40 DATA bug, but for PACKET_LINKREQ. Both
      `fetchNomadPage` and `syncPropagation` were sending HEADER_1
      LINKREQs to multi-hop destinations; upstream RNS Transport
      silently dropped them at the inbound forwarding branch
      (transport_id required). Reproduced 2026-05-03 with v0.1.42
      against tools/test_nomadnet_node.py via local transport node:
      app sent path?, transport answered, app sent HEADER_1 LINKREQ,
      transport never forwarded, nomad node never saw it, fetch
      timed out at 45s with "no LRPROOF received". Fix mirrors the
      sendMessage §2.3 path: when `dest.hopCount > 1 && dest.nextHop != null`,
      build LINKREQ as HEADER_2 with transport_id. Test in
      EngineSendBugTest asserts headerType == HEADER_2 and transport_id
      bytes match the cached nextHop.

- [x] **2026-05-03 RESOLVED in v0.1.42 — §11.1 path_hash truncation.**
      `LinkSession.request` now requires 16 bytes (was 32). Both call
      sites (`PropagationClient.pollAll`, `ReticulumEngine.fetchNomadPage`)
      truncate `SHA256(path)` to 16 bytes before passing in. Upstream
      `RNS/Destination.register_request_handler` keys its handler dict on
      `SHA256(path)[:16]`, so the prior 32-byte envelope[1] never
      matched any handler — NomadNet pages went out, server saw a
      REQUEST with an unknown path_hash, and silently dropped it.
      Tests in `LinkSessionTest` updated to use 16-byte hashes and
      assert envelope[1].size == 16.

- [ ] **§6.7 Initiator-side KEEPALIVE on Links.** ResponderLinkSession
      handles inbound KEEPALIVE correctly, but our initiator-side
      `engine/LinkSession.kt` never emits the periodic 0xFF ping. Any
      outbound link we open will tear down after `keepalive` seconds
      (defaults to 360s before first RTT measured).

- [ ] **§5.7 LXMF stamps for spam control.** Modern Sideband 1.x
      treats stamp-less inbound messages as spam in the UI. Not a
      protocol-layer break — messages still deliver — but the
      recipient may never see them. Need to compute a PoW stamp
      (3000-round HKDF over message_id, target_cost leading zero
      bits) and include it in the optional 5th element of the LXMF
      msgpack payload.

- [ ] **§6.5.5 PROOF receiver tolerance + signature verification.**
      Our PROOF handler at `engine/ReticulumEngine.kt:950` matches
      inbound proofs to outgoing messages by dest_hash only — it does
      NOT verify the Ed25519 signature over the proven packet's hash.
      That makes our delivery confirmation forge-able. Spec §6.5.1
      defines the verification recipe; we need to fetch the
      destination's long-term Ed25519 pub from the contacts table
      and verify the signature before flipping state to "delivered".


## iOS port (Phases 1-4)

Goal: ship the same Reticulum mobile app for iPhone, building on
Xcode Cloud against an `App Store Connect` provisioning profile.
Broken into four phases so each one is independently shippable.

- [x] **Phase 1 — KMP iOS targets + XCFramework production (v0.1.84
      branch `ios-phase1-xcframework`).** `shared/build.gradle.kts`
      configures `iosArm64`, `iosSimulatorArm64`, `iosX64` with a
      static `Shared.xcframework`. CI smoke test on `macos-14` runs
      `./gradlew :shared:assembleSharedXCFramework`. Existing
      `iosMain/TcpSocket.ios.kt` stays as TODO; new
      `iosMain/codec/Bz2.ios.kt` stub throws `NotImplementedError`.
      No iOS app shell, no real platform actuals, no useful runtime
      behavior — this phase only proves the framework links.

- [x] **Phases 2–3 SHIPPED — iOS platform actuals + app shell.**
      Every platform actual landed: `Bz2.ios.kt`, `TcpSocket.ios.kt`,
      `IosCryptoProvider` (CommonCrypto + the CryptoKit Curve25519
      bridge), SQLDelight storage, `IosBleTransport`. The app shell
      is SwiftUI (Option A) — five tabs, shipped and at full feature
      parity with Android (Messages / Nodes / Graph / Map / NomadNet
      rich Micron / RRC / Settings) as of `ios-v1.0.62`. iOS
      Bluetooth Classic stays intentionally skipped — needs MFi
      certification.

- [x] **Phase 4 SUPERSEDED — release pipeline.** Not Xcode Cloud /
      App Store Connect / TestFlight: the project ships an *unsigned*
      `.ipa` for personal sideloading (AltStore / Sideloadly), built
      by `.github/workflows/ios-release.yml` on an `ios-vX.Y.Z` tag —
      mirrors the Android tag pattern, skips the $99/yr Apple
      Developer fee + App Review. See the workflow header for the
      rationale.

- [ ] **iOS unread-message badge on the app icon — half-wired.**
      `iosApp/iosApp/Store/IosNotifications.swift:87` already requests
      `UNAuthorizationOptions.badge` so the user is prompted for badge
      permission, but no code anywhere ever sets the badge count.
      User grants the permission and never sees the red number on the
      home-screen icon — just the notification banner + Notification
      Center row.

      To finish:
        1. Track per-contact "last opened" timestamps. Cheapest:
           `UserDefaults["lastSeen.<contactHash>"] = epochMs`
           updated when ConversationView appears. More-correct:
           tiny `unread_seen` table (single row per destination
           hash) in SQLDelight schema so the value survives
           `UserDefaults.removeAll()` cases.
        2. On every incoming `EngineEvent.MessageReceived` (the
           same event that fires `IosNotifications.shared.post(...)`),
           recompute `unread = totalIncomingMessagesAcrossAllContacts -
           sum(messagesPriorToLastSeen)`. The
           `messageRepo.observeIncomingContactHashes()` flow already
           tracks unique senders; we'd need a count-per-contact
           helper or a derived flow.
        3. Push the count via iOS 17+ `UNUserNotificationCenter.
           current().setBadgeCount(unread, withCompletionHandler:
           nil)` (preferred), with the iOS 16 fallback
           `UIApplication.shared.applicationIconBadgeNumber =
           unread` for older targets. App's deploymentTarget is
           17.0 per `iosApp/project.yml:41` so the modern API is
           the only one we strictly need; keep the fallback for
           safety.
        4. Reset the contact's lastSeen + recompute on
           `ConversationView.onAppear` so the badge decrements as
           the user catches up.

      Optional polish: also set `content.badge = unread` on each
      `UNMutableNotificationContent` in
      `IosNotifications.deliver(_:)` so iOS auto-displays the
      count even before our store recomputes — backstop in case
      the app is suspended when the notification arrives. Use the
      "expected unread after this message" value, which is
      `currentUnread + 1` at notification-post time.

      Effort: ~half a day for the per-contact-correct version,
      ~30 min for the quick "increment on each post, reset to 0
      on any conversation open" version that doesn't track per-
      contact accurately. The quick version is fine for v1; the
      lastSeen table can come later if users complain about the
      count being slightly off.

## LXMF image attachments (10–20 KB JPEGs)

Tester request 2026-05-10. Send a small JPEG attached to an LXMF
message; recipient receives + displays inline. Target compression
budget 10–20 KB.

The protocol-shaped piece: a single LXMF packet carries ~280 bytes
of msgpack payload after RNS HEADER_1 (19 B) + token-encrypt
(eph X25519 32 + HMAC 32 + IV 16) + LXMF source_hash (16) +
Ed25519 sig (64) + AES-CBC pad. A 10–20 KB image therefore cannot
ride a single packet and must be fragmented. Reticulum's standard
fragmentation is the Resource (SPEC §10.2 / §10.5). Our codebase
already PARSES inbound Resources (`shared/.../resource/Resource.kt`
+ `LinkSession.kt`) but does not SEND them — implementing the
sender unlocks image attachments AND finishes §10 spec compliance
on our outbound path (we currently only produce single-packet
LXMF + multi-packet outbound Resources is the standing gap that
this work closes).

User picked "spec-compliant Resource sender" path 2026-05-10
during planning (vs the alternative "custom chunked-LXMF only-our-
apps interop" path which would have been faster but wire-
incompatible with Sideband / NomadNet).

**Wire format is pinned**: confirmed 2026-05-11 by reading
`torlando-tech/columba` (`MessagingScreen.kt`, `MessageMapperTest.kt`,
etc.) that the on-wire key for the image attachment is the **LXMF
integer field 6** (`FIELD_IMAGE`), not the string `"image"` an
earlier draft of this plan assumed. Using integer 6 makes us
bidirectionally wire-compatible with Sideband and Columba out of
the box — inbound images from either client decode, and outbound
images we send are renderable on either client. Animated images
also ride field 6 in Columba (their bubble code branches on the
decoded mime type), so the same wire slot handles still + animated.

Effort: ~5.5 dev-days total.

- [x] **SHIPPED — all three phases (outbound Resource sender, image
      picker + JPEG compression, storage + bubble rendering).** LXMF
      `FIELD_IMAGE` (integer key 6) send + receive works on both
      platforms, bidirectionally wire-compatible with Sideband and
      Columba; the outbound RNS Resource sender (§10) — including
      multi-segment / HASHMAP_REQ — shipped in `android-v1.1.49`.
      Images render inline with tap-to-zoom; EXIF is stripped. The
      receive path's defensive size ceiling is the model the
      `FIELD_FILE_ATTACHMENTS` work below reuses.

## Columba interop parity (2026-05-13 delta survey)

Surveyed `torlando-tech/columba` (their tree, mainly
`MessageMapper.kt`, `NativeTelemetryHandler.kt`, `AppDataParser.kt`,
`BleGattServer.kt`) and identified the following gaps. Columba runs
upstream Python RNS+LXMF via Chaquopy, so most of their "protocol
code" is the Python reference; the gaps below are what actually
matters for behavior parity.

### Wire-touching gaps (would change interop)

- [x] **2026-05-18 SHIPPED — `FIELD_FILE_ATTACHMENTS` (LXMF key 5)
      receive.** Built in 5 increments. Per RULE #1 the wire shape was
      pinned in `reticulum-specifications` SPEC §5.9.7 first — from
      upstream Sideband source (`core.py` / `ui/messages.py`); it had
      been an UNVERIFIED field. `extractFileAttachments` decodes the
      list of `[filename, file_bytes]` pairs (unit-tested);
      `sanitizeAttachmentName` reduces to a base name and strips
      control chars; `INBOUND_FILE_MAX_BYTES` = 256 KB cap.
      `StoredMessage.attachmentName` / `attachmentBytes` + Room
      `MIGRATION_14_15` (v15) / SQLDelight `6.sqm`. All three inbound
      LXMF paths (propagation / link / opportunistic) persist the
      first file. Both UIs render a `📎 name · size` chip → tap opens
      the OS save dialog (Android SAF `CreateDocument` / iOS
      `.fileExporter`); bytes are never auto-opened.

      Security requirements: **met** — #1 hard cap, #3 never
      auto-open, #4 filename sanitised. **Deviations:** #2 (defer the
      Resource fetch until the user taps) was *not* done — like
      `FIELD_IMAGE`, the attachment rides in with the message and the
      cap is a post-decode drop; a true ADV-stage deferred fetch is a
      separate enhancement the image path also lacks. #5 — an
      attachment on an unverified bubble already inherits the amber
      "Unverified sender" treatment; no attachment-specific warning
      was added. **Receive-only** — outbound file *send* is a
      separate, unstarted item.

- [ ] **Allow-list / contacts-only inbound mode (deferred — raised
      2026-05-18, not yet greenlit).** The real "not anyone → me"
      control: optionally drop ALL inbound LXMF (text + attachments)
      from senders who aren't known contacts. Distinct from MED-6
      (which only drops *unverified* messages). Product decision —
      blocks first-contact entirely — so left as a noted option, not
      scheduled.

- [ ] **`rncp` inbound — decision needed, probably WON'T-FIX.**
      `rncp` (the `rns` package's scp-over-mesh CLI) copies a file to
      a destination running an `rncp` listener; the app has none, so
      `rncp <our-lxmf-hash>` fails outright (reported 2026-05-18).
      Implementing an `rncp` receiver means standing up a separate
      non-LXMF destination + the rncp transfer protocol + an
      accept/decline UI + background-service listener lifecycle —
      large, and it duplicates what `FIELD_FILE_ATTACHMENTS` above
      already solves the messaging-native way. Default stance:
      decline; tell senders to attach the file to a Sideband/LXMF
      message instead. Revisit only if a real workflow needs CLI →
      app file push that LXMF can't cover.

- [x] **`FIELD_AUDIO` (key 7) — receive path. ✅ 2026-06-23 VERIFIED SHIPPED** (receive + inline playback AND outbound send: `extractAudioField`/`buildAudioField` + `AudioMode` in `ReticulumEngine.kt`, `audioBytes`/`audioMode` on `StoredMessage`, record+play UI in `MessagesScreen`; voice clips shipped in 1.2.79). Original ask — value shape:
      `[mode_byte, audio_bytes]` (see SPEC.md §5.9.3 — Codec2 /
      Opus mode bytes enumerated there). Half-day port to add an
      audio-attachment bubble that plays the clip. Don't worry
      about outbound for v1 — receive parity is the interop win.

- [ ] **`FIELD_TELEMETRY` (key 2) + `FIELD_TELEMETRY_STREAM` (key 3)
      — embedded in LXMF.** We parse telemetry only from RLR
      beacons; Sideband ships telemetry snapshots embedded in LXMF
      messages (location, environmentals, etc.). Columba's
      `NativeTelemetryHandler.kt:90-180` does the full parse. Adds
      Sideband-location-share interop without us writing the
      telemetry collector role.

- [ ] **`FIELD_ICON_APPEARANCE` (key 4) — render contact avatars.**
      Sideband ships per-contact avatar hints in this field. Quick
      win — store on `StoredDestination` and render in contact
      list + message bubbles.

- [x] **Reactions + reply-to. ✅ 2026-06-23 VERIFIED SHIPPED (superseded
      the Sideband field-16 approach).** Implemented via the upstream
      spec fields instead — `FIELD_REACTION` (`0x40`, §5.9.8),
      `FIELD_REPLY_TO` (`0x30`) + `FIELD_REPLY_QUOTE` (`0x31`, §5.9.9),
      migrated in `34190ac`; `ReactionsJson` + `reactionsJson` /
      `replyToMessageId` columns on both Room and SQLDelight. Spec-
      compliant rather than app-extension. Original Sideband-convention
      note: `MessageMapper.kt:90-160` decodes
      `{"reactions": {emoji: [senderHash, ...]}, "reply_to":
      "msgid"}`. App-extension layer (NOT upstream LXMF spec),
      but visible win for cross-client conversations. Wire-touching
      but small surface — a new column on `StoredMessage` for
      reply-to + a sibling table for reactions.

- [ ] **Propagation node announce app_data parsing.**
      `AppDataParser.kt:50-90` extracts the node display name from
      msgpack key `0x01` inside the 7th array element of the
      `lxmf.propagation` announce, plus `parsePropagationStampMeta`
      reads `[stamp_cost, flexibility, peering]`. We surface
      propagation as nameless. Trivial port — already documented
      in our SPEC.md §5.8.5 and §5.9.5.

### Larger features (UX + protocol, separate efforts)

- [ ] **Voice calls (LXST telephony).** Columba uses Mark Qvist's
      `tech.torlando.lxst.telephone.Telephone` on a separate
      `lxst.telephony` Destination with its own signalling +
      Opus audio packet path. `reticulum/.../call/telephone/
      NativeCallManager.kt:54-100`. Whole new subsystem; defer
      unless voice becomes a roadmap item.

- [ ] **BLE GATT *server* / peripheral mode.** Phone advertises the
      NUS service and accepts BLE connections from other Reticulum
      peers (`reticulum/.../ble/server/BleGattServer.kt` +
      `BleAdvertiser.kt`). Enables direct phone-to-phone over BLE
      without an RNode in the middle. Wire-touching but role-
      reversed — same NUS UUIDs, opposite GATT role. Probably 2-3
      days; nontrivial Android `BluetoothGattServer` lifecycle.

- [ ] **AutoInterface (UDP multicast LAN discovery).** Upstream's
      `AutoInterface` protocol lets peers find each other on the
      local network without explicit host:port config. We have
      direct TCP only. Wire-touching, new Transport implementation
      in `commonMain/transport/`. **Wire format is already fully
      documented — SPEC.md §8.6 — so no spec writeup blocks this
      (RULE #1/#5 satisfied).** Protocol is simple; the cost is
      IPv6 link-local multicast plumbing + one hard iOS blocker.

      Protocol (SPEC §8.6, ref `RNS/Interfaces/AutoInterface.py`):
      - Discovery: each NIC multicasts a 32-byte token
        `SHA256(group_id + own_link_local_ipv6)` every 1.6s to a
        group addr derived from `SHA256(group_id)` (default
        `ff12:…`) on UDP **29716**. Peer accepted when token ==
        `SHA256(group_id + sender_ipv6)` (sender IP from UDP
        header). Reverse unicast probe on **29717** confirms
        bidirectionality. Drop peer after 22s silence.
      - Data: once peered, **raw** Reticulum packets as plain
        unicast UDP datagrams (no HDLC/KISS framing — one datagram
        = one packet) to each peer's link-local addr on port
        **42671**. HW_MTU 1196, fixed.
      - Default `group_id = b"reticulum"` → peers with any stock
        RNS node on the LAN. Keep default for max interop.

      Work breakdown (everything plugs into the existing
      `Transport` iface + `engine.attach(t, kind)`, like
      `TcpInterface`):
      - [ ] `UdpSocket` expect in `commonMain/transport/` — bind
            per-interface, joinMulticast, `sendTo(addr,port)`,
            `incoming()` flow that **emits source addr** (needed to
            validate peer token).
      - [ ] Android actual — `java.net.MulticastSocket` +
            `NetworkInterface` enumeration + `Inet6Address`
            scopeId. Pure JDK → FOSS-clean.
      - [ ] iOS actual — POSIX `AF_INET6`/`SOCK_DGRAM` +
            `setsockopt(IPV6_JOIN_GROUP)` + `if_nametoindex`. See
            iOS blocker below; stub to "unsupported on iOS" first.
      - [ ] `AutoInterface : Transport` in `commonMain/transport/`
            — NIC enumeration, group-addr derivation, peer table
            with aging, 1.6s announce loop, data send/recv. The
            bulk.
      - [ ] Engine: add `TransportKind.Auto`
            (`ReticulumEngine.kt`, enum ~line 5305).
            attach/detach/sendOn are already generic — no routing
            changes.
      - [ ] Service supervisor: clone the TCP branch in
            `ReticulumService.kt`; on Android acquire/release a
            `WifiManager` `MulticastLock` around connect/disconnect.
      - [ ] Settings UI: new "Local network (LAN)" connect option
            + enabled toggle in `SettingsScreen.kt`, feature-gated
            like `agnLoraEnabled`.
      - [ ] Manifest: add `CHANGE_WIFI_MULTICAST_STATE`
            (install-time, no runtime prompt).

      Platform gotchas:
      - Android: **MulticastLock is mandatory** — without it the OS
        silently drops inbound multicast when the screen is off,
        which kills the whole background-service use case. IPv6
        link-local needs the **scope id** (`fe80::…%wlan0`) via
        `Inet6Address.getByAddress(…, scopeId)` — don't
        string-concat. Skip junk NICs (`rmnet*`, `dummy0`, `tun0`).
      - **iOS BLOCKER:** Apple gates all multicast send/join behind
        the managed `com.apple.developer.networking.multicast`
        entitlement (iOS 14+), which needs a **manual approval
        request to Apple** and a provisioning profile that includes
        it. Can't ship or even test on real hardware until granted.
        → **Ship Android-only first**, stub the iOS `UdpSocket`
        actual; treat the iOS entitlement as separate, unbounded
        follow-up work off the critical path.

      Effort: **~2–3 days Android-only** (~2–2.5k LOC, 5–6 new
      files + small engine/service/UI/manifest edits). iOS parity
      ~1 day code + unbounded wait on Apple approval.

      Testing: interop is the point — acceptance gate is
      **discover + exchange an LXMF message with a desktop `rnsd`
      running `[[AutoInterface]] enabled = yes`** on the same LAN
      (config referenced in `FwdsvcHarness.kt`), NOT phone↔phone
      (self-roundtrip masks wire divergence, cf. §10 Resource
      chain). Pin unit vectors for the two pure functions:
      group-addr derivation from `group_id` and the peer token.

- [ ] **Location sharing + telemetry collector role.** Periodic
      LXMF location updates with start/duration UX, plus a
      "telemetry collector" mode that responds to `FIELD_COMMANDS`
      requests from peers. `app/.../service/
      LocationSharingManager.kt` + `TelemetryCollectorManager.kt`.
      Builds on the `FIELD_TELEMETRY` receive port above.

- [ ] **Encrypted migration bundle (full export/import).** We only
      export the bare `.rmid` identity; Columba's
      `MigrationImporter.kt` / `MigrationExporter.kt` ships an
      encrypted ZIP with identity + contacts + messages +
      interfaces + themes. ~1-day port; lets users move devices
      without losing chat history.

- [x] **Native RNS identity format — IMPORT. ✅ 2026-06-23 SHIPPED in
      1.2.85 (Android; compiles + unit-tested; sideload verify pending).**
      Reopened #33 (@drupol). The app's identity already IS a standard
      RNS identity — the stored private key is byte-identical to the
      reference `Identity.to_file()` blob (`X25519_priv(32) ||
      Ed25519_priv(32)`, no header / version / encryption, SPEC §1.3) —
      so import is a thin codec, not the cross-client standardization
      effort that was over-scoped (and wound down) earlier. Done:
      `ReticulumEngine.importRnsIdentity` (commonMain — splits the 64-byte
      blob, validates keys, fresh ratchet, shares `applyReplacementIdentity`
      teardown/re-announce with the `.rmid` path) +
      `IdentityArchive.isEncryptedArchive` detector; Android Settings
      "Import identity…" smart-detects `.rmid` (passphrase) vs a raw
      64-byte RNS blob (replace-confirm, no passphrase). Tests:
      detection + RNS byte-order contract. **iOS import UI still pending**
      — the engine method is shared/commonMain, so iOS only needs the
      SettingsView wiring (tracked under iOS parity).

- [ ] **Native RNS identity format — EXPORT (gated).** The other half of
      #33. Offer exporting our identity in the raw RNS `to_file()` format
      (the same `X25519_priv(32) || Ed25519_priv(32)` blob `importRnsIdentity`
      consumes — `Identity.exportPrivateKeys` / `crypto/Identity.kt`), so
      a user can move this app's identity OUT to rnsd / Sideband / etc.
      **Gate it behind a loud "⚠ UNENCRYPTED — anyone with this file IS
      you" confirm**, because the RNS format is plaintext by design: fine
      for a server's permission-protected `~/.reticulum/storage`, dangerous
      for a phone "Export" flowing through the OS share sheet / Downloads /
      cloud. The encrypted `.rmid` (PBKDF2 + AES-CBC + HMAC, passphrase +
      strength meter) stays the DEFAULT export; raw-RNS is the opt-in
      interop escape hatch. Ratchet is dropped on export (rotating
      forward-secrecy state, not identity). Both platforms' Settings UI.

- [ ] **Multi-identity management.** Switch between multiple
      identities in one install (`IdentityManagerScreen.kt`).
      Local-only UX.

- [ ] **Blocked-users list.** Hide messages from specific
      destinations. Local UX, trivial.

- [ ] **In-app RNode flasher + onboarding wizard + theme editor.**
      Pure UX polish (`ui/screens/flasher/*`, `ui/screens/
      onboarding/*`, `ThemeEditorScreen.kt`).

- [ ] **APK sharing over local hotspot.** Off-grid sideloading
      peer-to-peer via Wi-Fi hotspot + QR
      (`ApkSharingServer.kt`, `LocalHotspotManager.kt`). Cute,
      low priority.

### Bug-fix lessons to verify against our code

- [ ] **Audit `handleIncomingLxmf` for "telemetry-only" dropping
      attachments.** Columba's `NativeTelemetryHandler.kt:36-55`
      explicitly added `hasAttachmentContent` to stop classifying
      image/file/audio-bundled messages as "location-only"
      (and silently dropping them). Worth checking our path
      doesn't drop a Sideband image whose text body is empty but
      `fields[1]` (telemetry) is set.

- [ ] **`FIELD_FILE_ATTACHMENTS` positional-vs-object dispatch.**
      Sideband uses positional 2-tuples; Columba's earlier
      single-shape parser dropped them silently
      (`MessageMapper.kt:553` comment). When we add field 5,
      match both shapes from the start.

- [x] **Animated GIFs play instead of a frozen frame. ✅ 2026-06-23
      IMPLEMENTED (compiles; pending sideload verify).** Note's old
      "2-line / route to Coil" plan was stale — Coil isn't a dependency.
      Done the AOSP way (no new dep): `isGifHeader` sniffs the 6-byte
      `GIF8?a` magic, then `decodeAnimatedImage` decodes via
      `ImageDecoder` → `AnimatedImageDrawable` (API 28+), rendered in an
      `AnimatedGifImage` (`ImageView`, FIT_CENTER, `.start()`) for both
      the inline bubble and the zoom view (`MessagesScreen.kt`). Non-GIFs
      and API < 28 fall through to the existing bitmap path (GIF shows its
      first frame on old devices). Only matters for inbound GIFs from
      other clients — our outbound path is JPEG.

## Security audit follow-ups (2026-05-13)

Full audit findings recorded; the three highest-priority items
shipped same day. Outstanding items below.

- [x] **HIGH-2 lockscreen notification leak** — fixed in
      `ReticulumService.kt:614-651` via
      `setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` plus a
      `setPublicVersion(...)` that hides decrypted content on the
      lockscreen.
- [x] **HIGH-1 Android Auto Backup carve-out** — fixed in
      `AndroidManifest.xml:27-37` by setting
      `android:allowBackup="false"`. Identity keys no longer leak
      via `adb backup` / Google-Drive restore.
- [x] **MED-1 KISS parser size cap** — fixed in `Kiss.kt:90-100`
      with a 64 KB `maxFrameBytes` ceiling mirroring HDLC. Closes
      the BLE-proximity OOM vector.

- [x] **2026-05-13 SHIPPED — HIGH-3 `.rmid` export passphrase
      strength.** New `crypto/PassphraseStrength.kt` exposes
      `assessPassphrase(passphrase)` which classifies as
      `TooWeak / Acceptable / Strong` under the policy:
      - ≥ 20 chars (any classes) → Strong
      - ≥ 12 chars AND ≥ 3 of {lower, upper, digit, symbol} → Strong
      - ≥ 12 chars AND ≥ 2 classes → Acceptable
      - else → TooWeak (rejected)

      `IdentityArchive.pack` re-runs the check before encrypting
      so a programmatic caller can't bypass the UI. Android
      `SettingsScreen.kt` export dialog shows a live strength
      meter (red/amber/green) and gates the Export button on
      acceptable. iOS `SettingsView.swift` mirrors the policy in
      `assessPassphraseSwift` (Kotlin source of truth, Swift
      duplicate for UI gating) and spells the requirements out
      in the alert message. Unit tests at
      `IdentityArchiveTest.kt:pack_weakPassphrase_rejected` and
      a new `PassphraseStrengthTest.kt` pin the policy.

      Future: swap in zxcvbn-kmp if we want dictionary-word /
      leet-substitution detection. Current bar is length +
      character class only — raises the floor meaningfully but
      doesn't catch `Password123!`-class submissions.

- [x] **2026-05-13 SHIPPED — MED-2 Announce-flood eviction.**
      `DestinationRepository.evictUnfavoritedOldest(keepCount)`
      added (Room + SQLDelight + InMemoryDestRepo for tests).
      Engine calls it from `maybeEvictDestinations()` after every
      50 announce upserts (throttle); keeps 5000 unfavorited rows
      max, evicting by `lastSeen` ASC. Favorites and user-
      renamed entries are exempt — those are deliberate state.
      Soft-deleted (hidden) rows are also exempt from churn.
      Eviction count logged via `EngineEvent.Log` so admins can
      see flood pressure in diagnostics.

- [x] **2026-05-13 PARTIALLY SHIPPED — MED-3/4 BLE/BT proximity.**
      The bonded-device-only filter is deferred (standard NUS
      RNodes don't enforce BLE bonding, so a hard switch would
      break the existing user flow). Instead, both Android
      `SettingsScreen` and iOS `SettingsView` now render a
      proximity threat-model notice at the top of the BLE
      section: "BLE attaches over NUS, which is unauthenticated
      by default. Anyone within ~30 m who can impersonate the
      RNode could inject crafted packets. Pair the RNode in
      Settings → Bluetooth first to harden." Closed the KISS
      OOM vector via MED-1; injection is still possible but
      bounded.

      Follow-up: add an opt-in toggle that requires a bonded
      device when the user wants strict mode. Requires
      changes to `BleTransport.deviceByAddress` to reject
      unbonded devices and a UI gate in the scanner picker
      (`BleScanDialog` / iOS `BleScannerSheet`).

- [x] **2026-05-13 SHIPPED — MED-5 TCP operator-trust warning.**
      Was already present at the BOTTOM of the TCP section on
      both platforms; lifted to the TOP of the section
      (`SettingsScreen.kt` + `SettingsView.swift`) and wrapped
      in a styled callout with warning icon so the user sees
      it before picking a host/port. Expanded the copy to
      mention "log when you're online" and "message contents
      stay encrypted, but metadata is not" — concrete enough
      to inform the trust decision.

- [x] **2026-05-13 SHIPPED — MED-6 Unverified-message UX.**
      Engine gained a `dropUnverifiedProvider: () -> Boolean`
      hook wired to the Settings toggle on both platforms
      (Android: `Preferences.dropUnverified`, iOS:
      `@AppStorage("security.dropUnverified")`). When ON,
      inbound LXMF with no verifiable signature is silently
      dropped in all three reception paths (opportunistic,
      link-delivered, propagation `/get`) instead of being
      persisted as `state="unverified"`. Default OFF preserves
      the legacy retroactive-verify UX. UI affordance:
      unverified bubbles render with an amber tint, amber 1dp
      border (Android) / 1pt stroke (iOS), and an
      "⚠ Unverified sender" header above the message text so
      they're impossible to mistake for vouched-for messages.

- [x] **2026-05-13 SHIPPED (Android) — HIGH-1 follow-up: Android
      Keystore wrap of identity keys.** New
      `crypto/IdentityVault` interface; Android impl
      `androidApp/storage/AndroidKeystoreIdentityVault` uses
      Android Keystore-backed AES-256-GCM. Wrapping key is bound
      to the TEE (StrongBox where available) with
      `setUnlockedDeviceRequired(true)` (API 28+). New
      `encPrivKeyEnc`/`sigPrivKeyEnc`/`ratchetPrivKeyEnc` columns
      added in Room schema v9→v10 + SQLDelight v2→v3 migrations.
      Engine's `ensureIdentity` re-saves on first load so existing
      installs migrate from plaintext to sealed columns on next
      launch; the plaintext columns are then zeroed (empty
      ByteArray) — schema-level drop deferred to a future version
      so users have a rollback path.

- [ ] **iOS vault — Secure Enclave / Keychain implementation.**
      iOS currently uses `PlaintextIdentityVault` (pass-through);
      the on-disk format is identical so swapping in a real
      vault is a drop-in replacement. Implementation plan:
      generate an AES-256 key under
      `kSecAttrTokenIDSecureEnclave` on first identity save,
      wrap private keys with AES-GCM via CryptoKit /
      CommonCrypto. Same `setUnlockedDeviceRequired`-equivalent
      via `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.
      Separately verify the SQLDelight DB file is created under
      `NSFileProtectionComplete` rather than the default
      `NSFileProtectionCompleteUntilFirstUserAuthentication`.

- [x] **2026-05-13 SHIPPED — LOW-4 msgpack decoder per-array
      length cap.** `MessagePack.readArray` and `readMap` now
      `require(n in 0..65_536)` before allocating. The
      `MAX_CONTAINER_LEN` ceiling is well above legitimate
      Reticulum / LXMF / NomadNet traffic (the largest real
      structural container has ~10 elements) but blocks the
      attack where a peer advertises 4 GB on a `0xDD`/`0xDF`
      length prefix and forces `ArrayList(Int.MAX_VALUE)`.
      Container BLOBs (bin8/16/32) are intentionally unbounded
      here because they're already gated by the HDLC 64 KB
      frame cap upstream.

- [x] **2026-05-13 SHIPPED — LOW-7 constant-time HMAC compare.**
      Extracted `constantTimeEquals` from `IdentityArchive` into
      `crypto/ConstantTime.kt` as an `internal` helper.
      `TokenCrypto.decryptOpportunistic` (line 90) and
      `decryptWithDerivedKey` (line 128) now use it instead of
      `ByteArray.contentEquals`. Not exploitable in practice
      over noisy LoRa / BLE / TCP transport, but the safe
      primitive everywhere is hygiene at zero cost.

## iOS feature parity (2026-05-17 audit)

The iOS app shell (the `## iOS port` Phase 3 above) shipped — SwiftUI,
five tabs, shared engine wired. A full parity audit on 2026-05-17 put
it at ~85%: Messages (incl. images / EXIF / fullscreen zoom / copy-
text / reactions / replies), Nodes, Graph, Settings, and plain-text
NomadNet are all at parity, and the shared protocol layer is CI-built
for iOS on every `shared/` change (`ios-build.yml`). Outstanding gaps,
ranked:

- [x] **2026-05-17 SHIPPED — iOS RRC Rooms UI.** `ios-v1.0.59`.
      Increment 1: `IosDatabase` RRC repository actual + `IosEngineFactory`
      Kotlin↔Swift bridge (`engineEventAsRrcActivity`, `openRrcSessionBridge`).
      Increment 2: `RoomsView.swift` — hub list → hub detail
      (connect / browse-rooms `/list` dialog / edit-nick / join) →
      room chat, plus `ReticulumStore` RRC state + the `experimental.rrc`-
      gated Rooms tab in `ContentView`. At parity with Android RRC,
      including the 2026-05-17 ordering + inline-`/`-command fixes
      (`ios-v1.0.60`).

- [x] **2026-05-17 SHIPPED — iOS rich Micron / NomadNet rendering.**
      The 2026-05-17 audit was stale: `MicronView.swift` (a full
      ~600-line port of `MicronView.kt` — headings, paragraphs,
      literals, tables, partials, rules, text/checkbox/radio fields,
      GET + POST links, inline styles, hex colours) already exists and
      is wired into `NomadView`. Closed the one remaining gap this
      session: in-page links now dispatch through the shared
      `parseLinkTarget` (commonMain), so cross-node `<hex>:/path` and
      `lxmf@<hex>` links route exactly as Android's NomadScreen does —
      `NomadPageView` swaps the browsed node in place, with the page
      history stack carrying (node, title, path) across cross-node
      hops. (Was iOS-only same-node before.)

- [x] **2026-05-17 SHIPPED — iOS propagation node list + sync.**
      The 2026-05-17 audit was stale: `SettingsView` already has a
      `Section("Propagation")` (node count, closest candidate by hop,
      "Sync now" → `store.syncPropagationAuto()`) and `ReticulumStore`
      already exposes `propagationNodes`. The one real gap — no
      post-sync result tally on iOS — was closed by moving the
      summary into the engine (`ReticulumEngine.propagationSummary`,
      emitted as an `EngineEvent.Log`), so both platforms now show an
      identical "N queued, M stored" line.

- [x] **2026-05-18 SHIPPED — iOS Nodes map view.** Added a third
      `Map` pane to `NodesView` (Nodes / Graph / Map), the iOS
      counterpart of Android's osmdroid `MapBlock`. `NodeMapView`
      renders every destination with a populated lat/lon on a MapKit
      `Map` (iOS 17 API), `.automatic` camera framing all markers;
      tapping a marker raises `NodeMapCard` (name / hash / hops·RSSI·
      coordinate + a Message shortcut) — the info-window equivalent.
      This was the last open iOS feature-parity item.

## iOS parity gaps (2026-06-23 audit)

Fresh 4-domain parity sweep (messaging / nodes-map-nomad / settings-
connect-identity / notifications-background) after the per-network
announce + node-list-cleanup work. The shared `commonMain` engine is
identical to both platforms by construction; everything below is an
iOS UI/platform feature that trails Android. (Supersedes the
2026-05-18 "last open item" note above — these are the remaining
real gaps.) Pick off as time allows.

Not parity gaps (recorded so they don't get re-flagged): Bluetooth
Classic (iOS platform restriction), USB serial (exposed on neither),
notification vibration (no iOS API), battery-optimization warning
(Android-only need), foreground-service vs background-modes
(architectural). Resend/retry of failed sends and home-screen
widgets/quick-actions are missing on BOTH — not iOS-specific.

### Tier 1 — significant, user-facing

- [ ] **iOS voice messages (record + playback).** Android ships the
      full FIELD_AUDIO clip flow — record/stop/cancel bar, inline
      ▶/⏸ playback, size label, RECORD_AUDIO permission
      (`MessagesScreen.kt:450–930, 1037–1092, 1464–1469`). iOS has
      none of it: no mic UI, no `sendVoiceClip`, no
      NSMicrophoneUsageDescription. Receive-side persistence is in
      shared code, so iOS can store but neither record nor play.
      Needs an `AVAudioRecorder`/`AVAudioPlayer` bridge + a record
      bar in `ConversationView.swift` + a playback control in
      `MessageBubble.swift` + the Info.plist mic key. (Related to the
      Columba-survey `FIELD_AUDIO` item above, but that one is about
      the shared receive path; this is the iOS UI.)

- [ ] **iOS live reconnect supervisor.** Android auto-reconnects
      mid-session on a transport drop with exponential backoff per
      kind (`ReticulumService.kt:260–595`). iOS only reconnects on
      cold-start/app-relaunch (BLE) or a network-path-satisfied event
      (TCP) — `ReticulumStore.swift:789–891`. If a transport drops
      while the user is active, iOS won't recover until they leave and
      return. Reliability gap; needs a session-lifetime supervisor
      watching transport state and retrying with backoff.

### Tier 2 — moderate

- [ ] **iOS full emoji picker for reactions.** Android exposes the
      overflow "+" → full system emoji grid
      (`MessagesScreen.kt:1699–1788`); iOS is limited to the 6-emoji
      quick palette (`MessageBubble.swift:304–311`). Add a system
      emoji-picker sheet behind a "+" in the iOS reaction palette.

- [ ] **iOS background BLE keep-alive.** State restoration is
      currently *disabled* (reverted due to AltStore crashes —
      `IosBleScanManager.swift:60–70`), so there's no daemon-style
      background reconnect; the app must be foregrounded to recover a
      dropped BLE link. Re-enabling needs the AltStore crash root-
      caused first (architectural; coupled to the Tier-1 reconnect
      supervisor).

### Tier 3 — minor / cosmetic

- [ ] **iOS Nodes-row metadata: `source=…` + "waiting for announce".**
      Android's node meta line shows the source (announce/manual/qr)
      and a "waiting for announce" flag for manual entries lacking a
      public key (`NodesScreen.kt:412–413`); iOS `NodesView` omits
      both.

- [ ] **iOS Nomad "Cached" filter + cached indicator.** Android has
      an All/Favorites/**Cached** filter plus a blue-dot "cached"
      marker on rows (`NomadScreen.kt:87–92, 675–683`); iOS `NomadView`
      has only All/Favorites and no cached indicator.

- [ ] **iOS per-contact notification grouping.** Android indexes
      notifications per contact (`ReticulumService.kt:113, 969`); iOS
      uses one id scheme (`IosNotifications.swift:132`) so messages
      don't group/stack per sender. Use `threadIdentifier` +
      per-contact ids.

- [ ] **iOS Announce-button feedback.** Android's "Send announce"
      shows a spinner + transient "sent on X / no transports" status
      (`SettingsScreen.kt:853–876`); iOS button
      (`SettingsView.swift:474–477`) has no loading/result feedback.

### Low priority — experimental

- [ ] **iOS agnostic-LoRa-Net (ALN) transport.** Android has a full
      BLE connect UI + a Features toggle gating it
      (`SettingsScreen.kt:470–562, 1090–1111`); iOS has zero ALN
      support. **Low priority — ALN is still mostly experimental**, so
      iOS parity here can wait until the contract stabilises. (BLE-
      based, so no iOS platform blocker when we do get to it.)

## RRC follow-ups (Android)

- [x] **2026-05-17 SHIPPED — Edit your RRC nick after adding a hub.**
      `HubDetailView` now shows "Your nick: …" with an Edit button →
      `EditNickDialog` → `viewModel.setRrcHubNick` → engine
      `setRrcHubNick` persists `StoredRrcHub.nick` (get + copy +
      upsert). Takes effect on the next connect, since
      `openRrcSession` reads the persisted nick. Previously the nick
      could only be set in the "Add hub" dialog.

## Connectivity & app lifecycle

- [x] **2026-05-18 SHIPPED — remember the active transport across app
      restarts + auto-reconnect on launch.** Built in 6 increments.
      `ConnectionMemory` (commonMain) — the pure, unit-tested
      restore-decision core (BLE/BtClassic by MAC, TCP by host:port;
      USB excluded — needs a re-granted permission). Android:
      `Preferences` gained `ble_address`/`ble_name`,
      `last_transport_kind`, `auto_reconnect`; `ReticulumService`
      records the kind on reaching Connected, clears it on an explicit
      Disconnect, and `ACTION_RESTORE` re-runs the matching supervisor;
      `MainActivity` triggers restore on launch; Settings → Connection
      has a "Reconnect on app launch" toggle. iOS: `ReticulumStore`
      persists the BLE peripheral UUID / TCP host:port to UserDefaults,
      `restoreLastConnection()` runs from `init()` (BLE waits on the
      central powering on, then `retrievePeripherals`), Settings has
      the matching toggle.

- [x] **2026-05-18 SHIPPED — re-open RRC hub sessions across app
      restarts.** Increment 7. The set of hubs with a live session is
      persisted (Android `Preferences.live_rrc_hubs` StringSet, iOS
      `connectivity.liveRrcHubs`), added on `openRrcSession` / removed
      on an explicit close. Cold-start restore re-opens them once any
      transport reaches Connected (an RRC session needs a live link);
      the engine's existing room auto-rejoin then restores the joined
      rooms. Fires once per app session, gated on the experimental RRC
      feature. Full restore chain: transport → RRC hub session → rooms.

## Speculative future features

- [ ] **Short video messages (Marco Polo style).** Record a 5-30s
      video in-app, send asynchronously to one contact, recipient
      plays inline in the bubble. Compose flow:
      record → preview/retake → send. Receive flow: video bubble
      with poster frame, tap to play.
      Open design questions:
      - **Wire slot**: Sideband ships `FIELD_AUDIO` for clips;
        a `FIELD_VIDEO` slot doesn't exist in upstream LXMF. Two
        options: (a) propose a new field allocation to upstream
        + spec it in `reticulum-specifications`, or (b) use
        `FIELD_FILE_ATTACHMENTS` with a `.mp4` / `.webm`
        extension and let the receive side detect it via MIME +
        render as a video bubble (interop-safe, no upstream
        coordination needed — Sideband would just see a file
        attachment). Option (b) is the pragmatic v1.
      - **Codec + size**: HEVC / VP9 / AV1 yield the smallest
        files but compatibility is fragmented. H.264 baseline +
        AAC in MP4 is the safe bet; ~300-500 KB for 5s at 480p.
        Even at LoRa bandwidth this is hours of airtime —
        practical only over TCP / Wi-Fi-shared rnsd, NOT over
        radio. Surface this honestly in the UI ("This will take
        ~1 minute to send over LoRa, ~3 seconds over TCP" with
        link awareness).
      - **Resource framing**: 500 KB at the spec'd SDU=464 is
        ~1100 chunks. Within our existing pull-style sender's
        capacity, but the user-visible progress bar matters
        more for video than for images (longer total time).
      - **Recording UX**: tap-and-hold to record (Marco Polo
        style), release to preview, swipe-up to cancel.
        `androidx.camera` on Android, `AVCaptureSession` on iOS.
        Probably 2-3 days for the capture + send half, another
        1-2 days for inline playback.
