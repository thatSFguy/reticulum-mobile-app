# App Store listing

Pre-drafted text for App Store Connect's *App Information*, *Pricing and Availability*, *App Privacy*, and *Version Information* sections. Adjust voice as needed; the wording below leans informational over promotional because the app's audience is technical (mesh / ham / off-grid users) and won't tolerate marketing.

## App name (30 char max)

```
Reticulum Mobile
```

## Subtitle (30 char max)

```
Off-grid LoRa messaging
```

Alternates if the above is taken:
- `LoRa mesh messaging`
- `Off-grid messaging`
- `Mesh networking client`

## Primary category

**Utilities**

## Secondary category

**Social Networking** (the app is fundamentally a messenger, but classifying as Social Networking primary draws comparisons to centralized chat apps that don't apply)

## Bundle ID

```
io.github.thatsfguy.reticulum.ios
```

(Matches `iosApp/project.yml` and the App Store Connect record will need to use this exact string. If the volunteer needs a different prefix for their team — e.g. their organization's reverse-DNS — change it in `project.yml` first, then regenerate the Xcode project, then create a fresh App Store Connect record with the new ID.)

## Description (4000 char max)

```
Reticulum Mobile is a native iOS client for the Reticulum mesh network — an open-source, off-grid, internet-independent communication stack built around LoRa radio.

This is a messaging app for situations where the internet isn't available, isn't trustworthy, or simply isn't desired. Pair the app with an RNode (an open-source LoRa modem you build yourself or buy from a community vendor) over Bluetooth, and you can exchange messages and browse NomadNet pages across a multi-hop LoRa mesh — no carrier, no Wi-Fi, no account, no cloud.

KEY CAPABILITIES

• LXMF messaging — encrypted, end-to-end, point-to-point. Sender and recipient identities are generated on-device using Curve25519 and Ed25519; no usernames, no servers, no registration.

• NomadNet browser — render and post to text-based mesh pages with the upstream micron markup format. Forms, links, page caching, cross-node navigation all work.

• Multi-transport — connect to a paired RNode over BLE for true LoRa, or connect to a remote Reticulum transport node over TCP/IP for when you're on the internet but want to participate in the mesh.

• Identity portability — export your identity to a passphrase-encrypted .rmid archive and re-import on another device. The archive format is wire-compatible with the Android version of this app.

• Image attachments — send a compressed JPEG (up to 20 KB after the auto-compression ladder) alongside any message. Wire-compatible with Sideband and Columba.

• Foreground listening — the app posts a system notification when a new message arrives while it's backgrounded. Tap the notification to jump into the conversation.

WHO THIS IS FOR

This app is built for people who already understand what Reticulum is. It assumes you have or are willing to acquire an RNode (or have access to a TCP-attached Reticulum transport node like RNS.MichMesh.net). If you've never heard of Reticulum, start at https://reticulum.network/ to understand the network before installing.

PRIVACY

This app does not collect, log, transmit, or sell any personal data. No analytics SDKs. No advertising SDKs. No third-party services. No login. No cloud sync. The only outbound traffic is whatever transport you explicitly attach (LoRa via your RNode, or TCP to a transport node you select). The map view fetches OpenStreetMap tiles when you observe a destination that advertises GPS coordinates — those tile fetches are the single HTTP-style call the app makes.

OPEN SOURCE

The full source code is available at https://github.com/thatSFguy/reticulum-mobile-app under the MIT license. Builds are reproducible from the public repository.
```

## Promotional text (170 char max)

```
Send LXMF messages over LoRa and browse NomadNet pages without internet. Pairs with an RNode over Bluetooth or connects to a Reticulum transport node over TCP.
```

## Keywords (100 char max, comma-separated)

```
reticulum,lora,mesh,off-grid,lxmf,nomadnet,rnode,encrypted,radio,messenger,ham,sdr
```

(Avoid `chat` and `messenger` as standalone words — Apple flags those as too-broad and they don't help discoverability for this niche audience anyway.)

## Support URL

```
https://github.com/thatSFguy/reticulum-mobile-app/issues
```

## Marketing URL (optional)

```
https://reticulum.network/
```

(Project doesn't have its own marketing site; point users at the upstream Reticulum project page.)

## What's New (release notes, 4000 char max)

Per-release; here's a starter for the **first** App Store submission:

```
Initial App Store release. Reticulum Mobile has been available as a sideload-only IPA since v1.0.1; this release brings the same build through Apple's App Review process unchanged. No new user-facing features versus the latest sideload tag at submission time.
```

For subsequent releases, the GitHub release notes for the matching `ios-vX.Y.Z` tag are an acceptable starting point — strip developer-internal phrasing (spec section references, commit hashes) and rewrite in user-facing voice.

## Pricing

**Free**. No in-app purchases. No subscription. Do **not** opt into the App Store's "Apps not on app store" cross-marketing because users have asked for the app to remain completely separate from Apple's promotional surfaces.

## Availability

All territories where Apple permits distribution. There are no geographic restrictions inherent to the app — Reticulum's mesh works wherever LoRa license-free ISM-band operation is permitted, which varies by country, but the app itself doesn't transmit anything on its own.

## Age rating

Walk through the questionnaire in [age-rating.md](age-rating.md). Expected outcome: **4+**.

## Encryption / export compliance

See [export-compliance.md](export-compliance.md). Answer set boils down to: yes, uses encryption; only uses encryption already provided by iOS (Curve25519 / Ed25519 / AES via CryptoKit + CommonCrypto); qualifies for the standard exemption; no ECCN classification needed.
