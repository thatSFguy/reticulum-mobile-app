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

- [ ] **Outbound LXMF delivery: opportunistic DATA does not transit
      between TCP clients on public rnsds.** Confirmed via controlled
      test (`tools/test_lxmf_receiver.py`) on 2026-05-03:
      - App → MichMesh TCP → ChicagoNomad-attached receiver: 0/3 retries
        delivered, message marked `failed`.
      - App → ChicagoNomad TCP → ChicagoNomad-attached receiver
        (same rnsd, both as TCP clients): also 0/3 retries delivered.
      - **Reverse direction** (Python `test_lxmf_sender.py` →
        ChicagoNomad → app on ChicagoNomad): also no proof within 30s.
      - Announces propagate fine in both directions (we see other
        peers via the public rnsds). Path resolution succeeds (1.5s).
      - Conclusion: public TCP transport nodes (MichMesh, ChicagoNomad)
        forward ANNOUNCE between clients but **block opportunistic
        DATA transit** — likely an `OUT = false` / mode-boundary
        config to prevent abuse.

      - **Smoking gun in receiver log:** when the Python sender (a
        sibling TCP client on the same chicagonomad as the app) issued
        a `path?` for the app's destHash, chicagonomad replied
        `Ignoring path request for <605fda26…>, no path known` — i.e.
        chicagonomad had never propagated our app's announce to its
        other TCP clients in 6+ minutes, even though the app's
        announce is reaching upstream transports (we know this because
        the announce did flow MichMesh → ChicagoNomad in test 1 within
        2s when the app was on a different rnsd). So sibling-client
        announce visibility on a single TCPServerInterface is the
        actual gap, not transit DATA generally.

      **Likely fix:** switch the LXMF send path from opportunistic DATA
      to a Reticulum **Link** delivery. Link packets (LINKREQUEST →
      LRPROOF → encrypted CONTEXT_NONE on the established link) ride
      through transport nodes that filter opportunistic DATA, because
      they look like control traffic. We already implement Link for
      NomadNet page fetch and propagation `/get` — see
      `engine/LinkSession.kt`. Generalize that to LXMF send.

      Test loop is now reproducible end-to-end:
      ```
      python tools/test_lxmf_receiver.py    # in one terminal
      # note the printed destHash, paste it as a contact in the app,
      # send a message; watch the receiver log.
      ```
