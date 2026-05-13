# TODO

Outstanding work that's not blocking but shouldn't be lost.

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

- [ ] **`/file/` downloads with Resource metadata.** Server-side
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

- [ ] **Phase 2 — iOS platform actuals (real implementations).**
      The runtime work the Phase 1 stubs deferred.
      - [x] `Bz2.ios.kt` — `cinterop` to `/usr/lib/libbz2.dylib`
            (PR #2, merged). `BZ2_bzBuffToBuffDecompress` with the
            same `maxBytes` decompression-bomb cap the Android side
            gets from a running counter on `commons-compress`.
      - [x] `TcpSocket.ios.kt` — replaced four TODOs with POSIX
            sockets (PR #4, merged). Direct port of
            `TcpSocket.android.kt`'s blocking-IO + close-from-
            another-thread cancellation pattern. NWConnection
            upgrade deferred to Phase 4 if backgrounding behavior
            actually matters in deployments — the foreground-only
            POSIX path is fine for the personal-sideload target.
      - [x] **Phase 2A** — `IosCryptoProvider` CommonCrypto half
            (this branch): `sha256`, `truncatedHash`, `hmacSha256`,
            `aesCbcEncrypt`/`Decrypt` (CCCrypt with PKCS#7),
            `randomBytes` (SecRandomCopyBytes), and `hkdfDerive` as
            pure-Kotlin RFC 5869 on top of HMAC. Ed25519 / X25519
            stubbed with NotImplementedError until Phase 2B.
      - [ ] **Phase 2B** — Curve25519 via CryptoKit Swift wrapper.
            CommonCrypto has no Curve25519 surface; CryptoKit's
            types are Swift-only and don't bridge to Obj-C. Plan:
            small Swift package under `shared/iosCryptoBridge/`
            exposing C-callable functions that wrap
            `Curve25519.Signing.PrivateKey/PublicKey` (sign /
            verify / keygen / pub) and
            `Curve25519.KeyAgreement.PrivateKey` (keygen / pub /
            sharedSecretFromKeyAgreement). Build as a static
            library; cinterop from Kotlin/Native. Fills in the 7
            `phase2bStub` methods on `IosCryptoProvider`.
      - [ ] iOS storage actual — `SQLDelight` for the
            `IdentityRepository`/`DestinationRepository`/
            `MessageRepository`/`NomadPageCacheRepository`
            interfaces. Schema parity with the Room v8 migration.
      - [ ] `IosBleTransport` — `CoreBluetooth` `CBCentralManager` +
            `CBPeripheralDelegate` against the Nordic UART Service
            UUIDs. Adapter for the existing KissParser. Background
            modes: `bluetooth-central` in the iOS app's Info.plist.
      - [ ] iOS Bluetooth Classic — **NOT possible** without MFi
            certification. Document the limitation and skip.

- [ ] **Phase 3 — iOS app shell.** New `iosApp/` directory with an
      Xcode project consuming `Shared.xcframework`. Choose:
      - **Option A**: SwiftUI native UI. Five tabs ported by hand
        from the existing Android Compose screens. More work
        upfront, idiomatic on iOS.
      - **Option B**: Compose Multiplatform. Move the Android
        Compose screens into `commonMain` (or a new `uiMain` shared
        between Android and iOS), bridge via `compose-multiplatform`
        plugin. Less rewriting, but Compose Multiplatform on iOS is
        still beta-ish (1.7.x as of 2026-01) and the keyboard /
        accessibility story isn't at parity.
      - Recommendation: SwiftUI. The five screens are small enough
        that a hand-port is faster than fighting Compose Multiplatform
        edge cases, and we get fully-native iOS keyboard, haptics,
        and accessibility.

- [ ] **Phase 4 — Xcode Cloud + App Store Connect.** Set up an
      `Apple Developer` account, generate signing identities, add
      `ci_scripts/ci_post_clone.sh` that installs JDK 17 (`brew
      install openjdk@17`) and runs `./gradlew
      :shared:assembleSharedXCFramework` so the framework exists
      before Xcode's compile step. Configure an Xcode Cloud workflow
      for tag-triggered builds matching the Android `android-vX.Y.Z`
      pattern (`ios-vX.Y.Z`). TestFlight distribution to start.

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

- [ ] **Phase 1 — outbound Resource sender (~3 dev-days).**
      Add to `shared/src/commonMain/kotlin/io/github/thatsfguy/reticulum/resource/Resource.kt`:
      - `Resource.buildAdvertisement(plain, link, hashSeed): ByteArray`
        — emits CTX_RESOURCE_ADV byte layout per §10.2 step 1.
        Mirror upstream RNS `Resource.advertise()` and the C++
        reference at `../microReticulum_Faketec_Repeater/`.
      - `Resource.splitToParts(plain, sdu = DEFAULT_SDU): List<ByteArray>`
        — split into ≤433-byte chunks. Reuse the existing
        `Bz2.compress` expect/actual for opt-in pre-compression
        when it shrinks the payload >5%.

      Add to `LinkSession.kt`: `sendResource(plain): Boolean`
      (~250 LOC). Steps: (1) build adv + parts, send ADV via
      the existing sender lambda, (2) stream all parts at one
      chunk per ~10 ms throttle so we don't saturate a half-
      duplex LoRa link, (3) listen for HASHMAP_REQ + retransmit
      requested subset with bounded retries, (4) resolve true
      on CTX_RESOURCE_PRF or false on link.proofTimeoutMs × 4
      timeout.

      Add to `ReticulumEngine.kt` (engine layer,
      lines 1540–1789 region where `tryDeliverOverLink`
      currently lives): a sibling `tryDeliverImageOverLink`
      that calls `packLinkMessage(…, fields = mapOf(6 to imageBytes))`
      — **integer key 6 (LXMF `FIELD_IMAGE`), NOT the string
      `"image"`**, so the bytes are decodable by Sideband and
      Columba. Then dispatch via `session.sendResource(linkBody)`
      instead of `session.sendDataAndAwaitProof(linkBody)`.
      Falls back to single-packet text-only on Resource send
      failure (image silently dropped, content preserved).
      `sendMessage` gets a new optional
      `imageBytes: ByteArray? = null` parameter; when null,
      the existing path is unchanged.

- [ ] **Phase 2 — image picker + JPEG compression (~1 dev-day).**
      Common quality-decay ladder so users don't have to
      think about compression knobs: resize to max-dim 512 px,
      JPEG @ 60% → if >20 KB drop to 40% → if still >20 KB drop
      to 25% + max-dim 384 px → if STILL >20 KB refuse with a
      user-visible "Image too large to send" error.

      Android: `ActivityResultContracts.PickVisualMedia()` (no
      manifest perms). New `androidApp/.../platform/ImageCompress.kt`
      with `compressForLxmf(uri, ctx): ByteArray?` using
      `Bitmap.createScaledBitmap` + `Bitmap.compress(JPEG, q, stream)`.
      Paperclip IconButton in the compose Row before Send;
      preview chip with × to remove appears above the TextField.

      iOS: SwiftUI `PhotosPicker` (PhotosUI, iOS 16+, no
      Info.plist usage description for read-only). New
      `iosApp/iosApp/ImageCompress.swift` with
      `compressForLxmf(_ image: UIImage) -> Data?` — same
      ladder via `UIImage.draw(in:)` to a CGSize-clamped
      context + `jpegData(compressionQuality:)`. Paperclip
      Button + same preview pattern. New
      `IosEngineFactory.kt` bridge for the `imageBytes`
      parameter on `sendMessage`.

- [ ] **Phase 3 — storage migration + bubble rendering (~1.5
      dev-days).**
      `StoredMessage.imageBytes: ByteArray? = null` added to
      `shared/.../store/Models.kt:56–77`.
      Android Room: `MessageEntity` in
      `androidApp/.../storage/Entities.kt:62–78` gets the new
      column; `MIGRATION_8_9` in `ReticulumDatabase.kt` runs
      `ALTER TABLE messages ADD COLUMN imageBytes BLOB`,
      same shape as v7→v8 hopCount migration.
      iOS SQLDelight: `shared/src/commonMain/sqldelight/.../ReticulumIosDatabase.sq:102–117`
      `messages` table gets `imageBytes BLOB`. SQLDelight
      schema bump triggers auto-migration.

      Receive path: `ReticulumEngine.handleIncomingLxmf`
      already extracts msgpack `fields`. Look up the integer
      key 6 (`fields[6]`) after unpack — Sideband + Columba
      both pack image attachments under this LXMF
      `FIELD_IMAGE` key. Be defensive: msgpack decoders may
      surface the key as `Int`, `Long`, or `Short` depending
      on encode width, so match on `(it as? Number)?.toInt() == 6`
      rather than reference equality. If present and ≤32 KB,
      persist on `StoredMessage.imageBytes`. >32 KB → log
      diagnostic + drop (defensive ceiling against a hostile
      peer shipping a 10 MB blob).

      UI: Android `MessageBubble` (in `MessagesScreen.kt:315–368`)
      decodes via `BitmapFactory.decodeByteArray` cached
      behind `remember(msg.id)`, renders
      `Image(bitmap.asImageBitmap())` with rounded corners +
      tap-to-zoom in a full-screen sheet with native pinch.
      iOS `MessageBubble.swift:11–82` same shape with
      `UIImage(data:)` + `Image(uiImage:)` +
      `.fullScreenCover` for tap-to-zoom.

      Verification: 10 KB image Android↔iOS over BLE LoRa,
      same over TCP, same against **Sideband AND Columba** on
      TCP (proves Resource framing is spec-compliant AND the
      LXMF field 6 wire key is correct on both send and
      receive paths — bidirectional with each), 4032×3024
      phone-camera photo auto-decays to ≤20 KB before send,
      20-KB-too-many image refuses with the user-visible
      error, mid-Resource-stream lock+unlock doesn't crash
      (cf. v1.0.14 crash-guard work), Room v8→v9 migration
      preserves existing rows.

      Out of scope for this v1: animated GIF / video / audio
      (needs HASHMAP_REQ HMU per §10.5 — separate ~2-day
      work), gallery view, image forwarding between
      conversations, iCloud / Drive auto-backup of images
      (orthogonal — standard OS backup paths already cover
      Room / SQLDelight).

## Columba interop parity (2026-05-13 delta survey)

Surveyed `torlando-tech/columba` (their tree, mainly
`MessageMapper.kt`, `NativeTelemetryHandler.kt`, `AppDataParser.kt`,
`BleGattServer.kt`) and identified the following gaps. Columba runs
upstream Python RNS+LXMF via Chaquopy, so most of their "protocol
code" is the Python reference; the gaps below are what actually
matters for behavior parity.

### Wire-touching gaps (would change interop)

- [ ] **`FIELD_FILE_ATTACHMENTS` (LXMF integer key 5) — receive +
      render.** Columba's `MessageMapper.kt:540-620` decodes both
      shapes Sideband uses: positional `[filename_bytes, hex_data]`
      AND object `{filename, size, data}`. Pairs naturally with our
      completed `FIELD_IMAGE` work — same Resource framing,
      different LXMF key + different value structure. Receive +
      attachment-bubble + tap-to-save via SAF (mirror our
      `/file/` download UX). Outbound send is a separate item.
      **Recommended first port — completes the attachment story
      against Sideband + Columba.**

- [ ] **`FIELD_AUDIO` (key 7) — receive path.** Value shape:
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

- [ ] **Reactions + reply-to on LXMF field 16 (Sideband
      convention).** `MessageMapper.kt:90-160` decodes
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
      direct TCP only. Wire-touching, would be a new Transport
      implementation in `commonMain/transport/`.

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

- [ ] **Animated-GIF magic-byte sniff before `BitmapFactory`.**
      `BitmapFactory.decodeByteArray` returns a static first
      frame, losing animation. Columba's `ImageUtils.isAnimatedGif`
      checks for the `GIF8?a` magic bytes before decode and hands
      animated GIFs to a different render path. 2-line fix on our
      side — sniff first byte sequence, route to Coil's
      `ImageRequest` for the animated case.

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
