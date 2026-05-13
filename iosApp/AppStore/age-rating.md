# Age rating

App Store Connect runs a questionnaire to derive the age rating automatically. Below is the answer set for Reticulum Mobile and the resulting rating.

**Expected result: 4+**

The 4+ rating reflects that the app contains zero objectionable content out of the box — it's a communication tool, similar in classification to Mail or Messages. User-generated content carried over the mesh is theoretically anything, but Apple's policy explicitly does not require a higher rating for messaging apps unless the app itself markets adult content, includes a public-feed-style discovery surface, or fails the user-generated-content moderation question (which we answer below).

## Questionnaire answers

The questionnaire walks through about 20 categories. Most are trivially "None" for this app; the only ones worth thinking about are the user-generated-content questions.

| Category | Answer | Rationale |
|---|---|---|
| Cartoon or fantasy violence | None | App has no violence content. |
| Realistic violence | None | App has no violence content. |
| Sexual content or nudity | None | App has no sexual content. |
| Profanity or crude humor | None | App has no profanity content. |
| Alcohol, tobacco, or drug use | None | App has no such references. |
| Mature/suggestive themes | None | App has no mature themes. |
| Horror/fear themes | None | App has no horror content. |
| Prolonged graphic or sadistic realistic violence | None | App has no violence content. |
| Graphic sexual content and nudity | None | App has no sexual content. |
| Medical/treatment information | No | App has no medical content. |
| Gambling and contests | No | App has no gambling or contests. |
| Gambling — simulated | No | App has no simulated gambling. |
| Contests | No | App has no contests. |
| Unrestricted web access | **No** | App does NOT contain an open web browser. The Nomad browser only renders NomadNet pages from the mesh, which is a closed, opt-in network — not the public Internet. Map view fetches OpenStreetMap tiles but does not render arbitrary user-navigable web URLs. |
| User-generated content | **Yes — moderated** | The app delivers user-to-user messages. Apple's "User-generated content" question covers this. Answer: **Yes**, then for the follow-up "does this content reach a public audience" answer **No** — messages are point-to-point or small-group within a mesh the user explicitly attaches to; there is no public feed, no discoverability surface, no broadcast-to-strangers function. |
| Moderation in place | **Yes, blocking + reporting** | The app supports per-contact block (achievable via the "Delete destination" action which removes the contact and stops further delivery). Reporting is local-only (no central reporting endpoint exists for a decentralized mesh); document this in the App Review Notes (see [review-notes.md](review-notes.md)) so the reviewer doesn't reject for missing moderation. |
| In-app purchases | No | App has no IAPs and no subscription. |
| Tracking | No | App has no tracking SDKs. See [PrivacyInfo.xcprivacy](PrivacyInfo.xcprivacy) — `NSPrivacyTracking` is false. |

## "Unrestricted web access" — common rejection point

Apple frequently rejects apps with embedded browsers that don't restrict navigation. The Nomad tab in this app is a custom renderer for `*.mu` micron pages over a closed mesh protocol — it cannot navigate to `https://...` URLs and has no address bar. If the reviewer flags "unrestricted web access", the response is:

> *The Nomad browser is a custom renderer for the NomadNet content format (`*.mu` files using micron markup), operating exclusively over the Reticulum mesh protocol. It cannot navigate to arbitrary HTTP/HTTPS URLs — there is no address bar, no URL-parsing layer, and no web view component (no `WKWebView`, no `UIWebView`, no `SFSafariViewController`). All "links" in rendered pages resolve to other Reticulum destinations via destination hash lookup, not URL navigation. The protocol is open-source and documented at https://reticulum.network.*

## "User-generated content" — the moderation question

If the reviewer asks how user content is moderated:

> *Reticulum Mobile is a peer-to-peer messaging client; there is no centralized message store and no server-side moderation point. Local moderation is achieved by the user (block / delete a destination removes that party's ability to deliver further messages to them and removes the message history). The app does not include a public feed, a discoverable user directory, or any function that lets a stranger reach the user without the user first announcing their existence to the mesh. The closest analogue in Apple's ecosystem is the on-device part of Mail or Messages — no server, no moderation queue, blocking is the moderation mechanism.*
