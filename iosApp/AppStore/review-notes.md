# App Review notes

App Store Connect provides a *Notes for App Review* free-text field that is shown to the human reviewer assigned to your submission. Use it to head off the rejections most likely to fire on a niche communication app that requires hardware.

The text below is what to paste into that field for the **first** submission. For subsequent submissions, abbreviate to "no significant changes vs the previously approved version, see GitHub release notes for ios-vX.Y.Z" and rely on this baseline note staying available in the prior submission's history.

---

## Notes for App Review

> Reticulum Mobile is a native iOS client for the open-source [Reticulum mesh network](https://reticulum.network/). The app is fundamentally a messaging tool that operates over LoRa radio (sub-GHz license-free ISM band) or over TCP/IP to remote relay nodes. Below are the points most likely to come up in review.
>
> ## Required hardware for full review
>
> The app's primary function — LoRa mesh messaging — requires an external Bluetooth LE accessory called an "RNode" (an open-source LoRa modem). RNodes are not available through Apple's MFi program, but they enumerate as a standard Bluetooth Low Energy peripheral exposing the Nordic UART Service (NUS) and connect via the standard `CoreBluetooth` API. A reviewer testing the app will not have an RNode and will not see live mesh traffic.
>
> **To test the app end-to-end without an RNode**, use the *Connect over Internet* option in Settings → Transports. The app ships with a default suggestion of `RNS.MichMesh.net:7822`, a public Reticulum transport node. Once connected:
> - Wait 30-60 seconds for the announce flood to populate the Nodes tab. You'll see multiple destinations appear.
> - Tap any destination in the Nodes tab to navigate to a conversation.
> - Send a "test from App Review" message; if the destination is online, you'll see a delivery proof come back within ~10 seconds.
>
> ## "Unrestricted web access" classification
>
> The Nomad tab (third bottom-nav tab) is a custom renderer for the NomadNet `.mu` (micron) page format, operating exclusively over the Reticulum mesh protocol. It is **not** a web browser:
> - No URL bar, no URL-parsing code path.
> - No `WKWebView`, `UIWebView`, or `SFSafariViewController` is instantiated anywhere in the binary. (Confirmable from `otool -L` on the shipped IPA — no WebKit framework is linked.)
> - "Links" resolve to other Reticulum destination hashes (16-byte identifiers), not URLs.
> - The protocol is documented at <https://github.com/markqvist/Reticulum/blob/master/docs/RNS_Reference_Manual.pdf>.
>
> The age-rating questionnaire's *Unrestricted web access* answer is correctly **No**.
>
> ## Encryption
>
> The app implements end-to-end encryption of messages using:
> - Curve25519 for ECDH key agreement (`CryptoKit`)
> - Ed25519 for digital signatures (`CryptoKit`)
> - AES-256-CBC for symmetric encryption (`CommonCrypto`)
> - HMAC-SHA256 for message authentication (`CommonCrypto`)
> - HKDF-SHA256 for key derivation (`CommonCrypto`)
> - PBKDF2-HMAC-SHA256 for passphrase-encrypted identity backups (`CommonCrypto`)
>
> All primitives are iOS-provided; the binary does not statically link an external crypto library. Per Note 4 of Category 5 Part 2 of the U.S. Export Administration Regulations, the app qualifies for the standard mass-market encryption exemption — no ECCN classification required.
>
> ## Privacy
>
> The app does not collect any user data. No analytics SDKs, no advertising SDKs, no third-party services. No account creation. No iCloud sync. The `PrivacyInfo.xcprivacy` manifest declares the three required-reason APIs in use (UserDefaults, FileTimestamp, SystemBootTime) — all categorized as "internal app use only".
>
> Outbound network traffic is limited to:
> - User-initiated Reticulum mesh traffic (encrypted, point-to-point) over the user's chosen transport.
> - OpenStreetMap tile fetches when the Nodes/Map view is opened and at least one observed destination advertises GPS coordinates. (Optional, never auto-triggered.)
>
> ## Moderation
>
> The app delivers user-to-user messages over a decentralized peer-to-peer network. There is no central server, no public feed, no discoverable user directory. Moderation is on-device: any received message can result in the sender being added to the contact list, and the user can delete the contact + message history (Settings → contact row → Delete) which prevents further messages from being persisted from that sender. Apple's standard "blocking + reporting" UGC moderation question is satisfied by the block path; centralized reporting is not applicable to a peer-to-peer architecture.
>
> ## Open source
>
> Full source is at <https://github.com/thatSFguy/reticulum-mobile-app> (MIT license). The binary in this submission is built from a public tag on that repository.
>
> ## Demo account
>
> Not applicable — the app does not have user accounts. First-launch generates a fresh on-device cryptographic identity. The TCP transport flow described above is the equivalent of a "demo account" for review purposes.

---

## Tips for the volunteer

- If the reviewer comes back asking for a way to test BLE without an RNode, **direct them firmly to the TCP transport flow**. Several apps in this niche have been rejected because reviewers didn't realize the BLE path was optional.
- If rejected for "app doesn't perform a function described in the listing", reply citing the TCP transport instructions above and request escalation if needed.
- Expect the first cycle to take 24-48h. Subsequent submissions with no significant changes are typically faster (4-12h).
- If you get a request for additional encryption documentation despite the exemption answers, link Apple's own page: <https://developer.apple.com/documentation/security/complying_with_encryption_export_regulations> and cite Note 4 to Category 5 Part 2.
