# TODO

Outstanding work that's not blocking but shouldn't be lost.

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
