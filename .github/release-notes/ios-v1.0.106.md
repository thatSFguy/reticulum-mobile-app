A Relay Chat room link now does something on iOS.

The link format and its parser have been shared code since 1.0.104, so a room link was understood underneath — nothing on iOS ever called the parser with one. Three surfaces, all read-side:

- **NomadNet pages.** `LinkTarget.RrcRoom` / `RrcHub` had no branch in `NomadView`, so a room link on a page fell through to "Unrecognized link". Android has routed these since the shorthand landed.
- **Direct messages.** `linkifyAttributedString` gained a third pass after the http and NomadNet ones, matching every form the v2 grammar reads plus the v1 links already in the wild.
- **Room timelines.** They rendered a bare `Text`, so every link in a room was inert — room links included. The same defect Android fixed in 1.2.112 by moving `linkify` into its own file.

Taps route through an internal `reticulum-rrc://` scheme intercepted by `OpenURLAction`, the pattern the NomadNet deep link already uses. A string that matches the recogniser but does not parse — an upper-case `RRC://`, a hub on a non-default aspect — stays inert text rather than becoming a tap that goes nowhere, because the URL is only built from a successful parse.

Also fixed on both platforms: tapping a link to a room you are **already in** upserted a fresh room row, resetting `lastReadMessageId` to 0 and `notifyMode` to "all". 0 means "never read" — the invented-unread-backlog shape the v19→v20 migration repaired in 1.2.105, re-created by a tap instead of a schema change. The muted-room reset is the one a user would notice.

Still Android-only in Rooms: the share sheet and create-field name validation. iOS can receive a room link now; it cannot make one.
