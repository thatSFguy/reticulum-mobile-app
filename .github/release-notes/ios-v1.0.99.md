## Highlights — Relay Chat rooms catch up with Android

iOS has been on 1.0.98 through five Android releases. This brings the
experimental **Rooms** tab up to par, and picks up the shared Relay Chat
fixes made in that time.

### The fix that matters most

- **A room that showed "Joined" but never displayed a message.** Joining
  a room by typing its name with a leading hash — which is how every
  room is displayed, so it is the natural thing to type — created a room
  the hub knew by a different name. The hub delivered your messages
  perfectly; they were filed where nothing looked for them, and the
  room's history was split in two. Affected rooms are repaired
  automatically on upgrade.

### New in Rooms

- **Unread counts** on each room, on each hub, and on the Rooms tab.
  Muted by default; red only when someone actually names you.
- **Replies** — long-press a message → Reply. The reply quotes what it
  answers, and one whose original has scrolled away still reads as an
  ordinary message.
- **Reactions** — long-press for a quick emoji palette. Reactions gather
  under the message as chips with a count; tap one to add or remove
  your own. Long-press a chip to see exactly who reacted.
- **Copy text**, a **member list**, day-aware timestamps, `/me` shown as
  an action, and mention highlighting.
- **Drafts are kept per room** — leaving a room no longer discards a
  half-written message.
- **Hub replies appear in the conversation** rather than a banner over
  the app, and a hub refusal now reads as an error.
- **Slash commands** — the composer is a command line. `/join`, `/part`,
  `/nick`, `/me` and `/clear` are handled by the app; everything else
  goes to the hub, and `/help` lists what your hub supports.

### What didn't change

- No changes to ordinary LXMF messaging, and none to the Reticulum wire
  format beyond the published Relay Chat extension for replies and
  reactions, which other clients either understand or show as ordinary
  messages.
- Rooms remains **experimental and off by default**, and is still not
  verified against a live hub on a device.

### Not yet on iOS

The slash-command completion list, the mention-completion list, and
room notifications are Android-only for now. iOS understands the full
command set — only the completion UI is missing.
