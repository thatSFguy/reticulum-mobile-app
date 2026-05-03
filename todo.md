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

- [ ] **Outbound delivery on MichMesh TCP.** Inbound works (announces from
      other peers populate Nodes / Graph / Nomad), but other clients
      (Sideband, MeshChatX) don't see our announces or messages.
      Suspected server-side filter (`OUT = false` default on
      `TCPServerInterface`) or a TCP-specific routing handshake we
      haven't replicated. See README "Known issue" + `CLAUDE.md`
      diagnostic notes.
