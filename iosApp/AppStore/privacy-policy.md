# Privacy policy

Apple's App Store requires every app to declare a public-facing privacy policy URL. The text below is what should be served at that URL — pick wherever you want to host (your own site, GitHub Pages on this repo, etc.) and paste the URL into App Store Connect's *App Privacy* section.

---

# Reticulum Mobile — Privacy Policy

**Last updated: <fill-in-on-submission-day>**

Reticulum Mobile is an open-source, off-grid messaging client for the Reticulum mesh network. This privacy policy describes the data the app handles and what is (and is not) sent off your device.

## We do not collect anything

Reticulum Mobile is built around the principle that the app should be invisible to its developers, to Apple, to advertisers, and to anyone else who isn't on the other end of a conversation you initiated.

- **No accounts.** There is no sign-up, no login, no email collection, no phone number collection.
- **No analytics.** No third-party analytics SDKs are integrated. No telemetry is sent back to us or to anyone else.
- **No advertising.** No advertising SDKs are integrated. No ad-tracking identifiers are accessed.
- **No usage tracking.** We do not record what features you use, how long you use them, or which destinations you communicate with.
- **No crash reporting beyond Apple's default.** Apple's TestFlight and App Analytics may produce crash reports if you have those opt-in features enabled at the system level; we receive only what Apple chooses to forward through App Store Connect, and we do not request crash diagnostics from users.

## What stays on your device

All of the following live exclusively on your device's local storage:

- Your Reticulum identity (Curve25519 + Ed25519 keypair, generated on first launch).
- Your contact list (other Reticulum destinations you've heard from or manually added).
- Your message history (sent and received LXMF messages, including any image attachments).
- Your cached NomadNet pages.
- Your settings (display name, theme, transport preferences).

iOS' standard system-level backup (iCloud Backup, if you have it enabled) may copy this data as part of its routine app-backup procedure. We have no control over and no visibility into that backup. To opt out for this specific app, use *Settings → [Your Name] → iCloud → Manage Storage → Backups → This iPhone → Reticulum Mobile* and toggle it off.

## What leaves your device

- **Whatever you send over Reticulum** — the entire purpose of the app. Outbound mesh traffic is routed via the transport (LoRa via an attached RNode, or TCP to a transport node you selected). End-to-end encrypted to the recipient using Curve25519 + AES-256 + HMAC-SHA256.
- **OpenStreetMap tile requests** — *only* when you open the Nodes / Map view and have at least one destination in your contact list that advertises GPS coordinates. The tile server (`tile.openstreetmap.org` by default) sees your IP address and which map regions you panned to. No other data is included. You can avoid these requests entirely by not opening the Map view.

That's the complete list. There are no other network connections initiated by the app.

## Open source

The full source code is available at <https://github.com/thatSFguy/reticulum-mobile-app> under the MIT license. You can verify everything claimed here by inspecting the code — there is no proprietary or obfuscated component that hides additional data flows.

## Contact

For privacy questions or concerns: file an issue at <https://github.com/thatSFguy/reticulum-mobile-app/issues>.

If a future version of this policy meaningfully expands the data the app handles, this document will be updated and the *Last updated* date above will reflect the change.
