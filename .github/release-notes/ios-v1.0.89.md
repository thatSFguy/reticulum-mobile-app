## Highlights — message parity with Android

- **Delete a message** — long-press a message → **Delete** (with confirm). Local-only; it doesn't unsend, and the other side keeps their copy.
- **Message info** — long-press → **Info** for a metadata sheet: time, delivery state, signal (RSSI / hops), attempts / last error, and message / packet IDs.
- **Draft text is kept per conversation** — leave a thread (or switch tabs / background the app) with unsent text and it's still there when you come back.
- **Per-conversation unread badge** — each thread in the Messages list now shows an unread count that clears when you open it (previously only the app icon badge updated).

These bring the iOS Messages experience to parity with Android.

## What didn't change

- No wire-format, protocol, or message-handling changes. The shared engine is byte-identical; this is iOS UI + local storage only.
