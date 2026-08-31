## Highlights — composer completion in Relay Chat rooms

The last two Rooms features that were Android-only. iOS already
understood every command; this adds the suggestions that make them
discoverable.

- **Slash-command suggestions** — type `/` in a room and the commands
  appear with their usage and a one-line description. Tap one to insert
  it. The list is built into the app, so it works before the hub has
  answered and costs no round trip; `/help` still asks your hub what it
  actually supports.
- **Mention suggestions** — type an "at" sign and the people who have
  spoken in the room are offered. If nobody has spoken yet, the list
  offers to ask the hub who is present rather than doing it for you: a
  membership request costs a round trip and leaves a line in the room,
  and neither should happen because you typed a character. Members with
  no nickname complete to their identity hash, which is the exact form
  anyway.

## What didn't change

- No wire-format or protocol changes, and no change to how messages are
  sent or stored. The rules for reading what you are typing now live in
  the shared module, so both apps use one implementation rather than a
  copy each.
- Rooms remains **experimental and off by default**, and is still not
  verified against a live hub on a device.

## Not yet on iOS

Room notifications are still Android-only — this is now the only
remaining difference between the two Rooms tabs.
