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

- [ ] **Unignore the 3 `EngineSendBugTest` cases that are currently `@Ignore`'d.**
  - `transport-send-throws marks message failed and logs exception class`
  - `concurrent sendMessage calls produce distinct msgIds`
  - `attach resets the announce throttle so the new transport gets a fresh announce`
  - **Why ignored:** runTest's structured-concurrency check fires
    `UncompletedCoroutinesError` after its 60s dispatch timeout because the
    engine's `reannounceJob` is a `while (true) { ... delay(N) }` loop on
    the TestScope. `engine.detach()` + `coroutineContext.cancelChildren()`
    don't cancel cleanly enough for runTest's checker.
  - **What to try next:** move the engine onto `backgroundScope` (auto-
    cancelled by runTest) — earlier attempt regressed the announce-
    throttle test because backgroundScope launches didn't fire under
    `advanceUntilIdle`. The fix is probably `runCurrent()` instead of
    `advanceUntilIdle()` in the throttle test, plus wiring engine.scope
    to `backgroundScope` in the test rig only.

## UI

- [ ] **Announce stream: show "last announced" age per node.** On the
      diagnostics / announce-stream view, each incoming announce line
      should display when that node was last seen announcing (e.g.
      "first time" or "last seen 2m ago"). Helps spot relays vs.
      first-contact peers at a glance and surfaces dedup behavior in
      the propagation client.

- [ ] **Announce stream: add a message icon that opens a conversation
      without favoriting.** Currently the only way to message a peer
      from the stream is to star them (which moves them to Messages).
      Add a separate envelope/message icon next to each entry that
      jumps straight into a one-off conversation view without
      flipping the favorite flag.

## Investigations

- [ ] **UI/state bug: Settings shows "Disconnected" while two TCP sockets to
      MichMesh are ESTABLISHED.** Found via `adb shell ss -tn` during
      screenshot capture. The logcat-mirror commit
      (`33f9279`) is in to make this debuggable — sideload that build, watch
      `adb logcat -s ReticulumEngine`, and grep for the connection-state
      transitions.

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
      - [ ] `Bz2.ios.kt` — `cinterop` to `/usr/lib/libbz2.dylib`,
            specifically `BZ2_bzBuffToBuffDecompress`. Match the
            Android implementation's `maxBytes` decompression-bomb
            cap (`commons-compress` cap behavior).
      - [ ] `TcpSocket.ios.kt` — replace four TODOs with
            `Network.framework` `NWConnection`. iOS 12+. Mirrors
            Android's blocking-IO read loop cancel-on-close pattern;
            on iOS the equivalent is `NWConnection.cancel()` on the
            connection plus a continuation-bridged `receive` loop.
      - [ ] `IosCryptoProvider` (new file in iosMain/platform/) —
            implements the `CryptoProvider` interface using
            `CryptoKit` (`Curve25519.Signing`,
            `Curve25519.KeyAgreement`, `HKDF<SHA256>`,
            `HMAC<SHA256>`) and `CommonCrypto` for AES-CBC
            (`CCCrypt`). Match the test-vector outputs in
            `reference/test-vectors.json`.
      - [ ] iOS storage actual — `SQLDelight` for the
            `IdentityRepository`/`DestinationRepository`/
            `MessageRepository`/`NomadPageCacheRepository`
            interfaces. Schema parity with the Room v7 migration.
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
