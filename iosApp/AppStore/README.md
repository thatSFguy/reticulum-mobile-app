# iOS App Store submission — starter pack

This directory contains pre-drafted assets a volunteer iOS developer can use to shepherd Reticulum Mobile through Apple's App Store review process. The project owner does **not** own any Apple devices and cannot perform the device-side steps (Developer Program enrollment, App Store Connect uploads, on-device validation, real-device screenshot capture, TestFlight beta management). Everything in this directory is the **paperwork side** — wire-format-correct text and templates that need a real Apple Developer account + a real iPhone to actually push through.

Open an issue or email if you want to coordinate.

## What's here

| File | Purpose | Volunteer action |
|---|---|---|
| [app-store-listing.md](app-store-listing.md) | App name, subtitle, description, keywords, what's new, promotional text, categories | Read and tweak voice; paste into App Store Connect |
| [privacy-policy.md](privacy-policy.md) | Public-facing privacy policy. Standard form: no data collection, no analytics, no third parties | Host as a static page (your own site, GitHub Pages, etc.) and paste the URL into App Store Connect's *Privacy Policy URL* |
| [export-compliance.md](export-compliance.md) | Answer set for Apple's encryption / export-compliance questions | Carry the answers verbatim through the Export Compliance section in App Store Connect |
| [PrivacyInfo.xcprivacy](PrivacyInfo.xcprivacy) | Apple's required *Privacy Manifest* file declaring accessed APIs and tracking posture. Drop this into the iOS bundle | Verify the declared `NSPrivacyAccessedAPITypes` matches your local build (Xcode 15+ surfaces missing/extra declarations as warnings) |
| [icon-1024-spec.md](icon-1024-spec.md) | App Store icon requirements + what to produce | Render a 1024×1024 PNG (no alpha, no rounded corners — Apple rounds at display time) and feed it through Xcode's `Assets.xcassets` plus App Store Connect |
| [screenshots-spec.md](screenshots-spec.md) | Required dimensions per device class + capture flow | Capture on a real iPhone (6.7" + 6.5" sizes required; 5.5" still accepted for now). Show the five tabs — Messages / Nodes / Nomad / Graph / Settings |
| [age-rating.md](age-rating.md) | Answers to App Store Connect's age-rating questionnaire | Walk through the questionnaire with the answers below; expected outcome is 4+ |
| [review-notes.md](review-notes.md) | Notes to send to App Review explaining the LoRa hardware dependency — reviewers will not have an RNode | Paste into App Review Information when submitting; without this they'll likely reject for "app doesn't appear to work" |

## What's not here (volunteer's responsibility)

- An Apple Developer Program account ($99/year). Reticulum is an open-source non-commercial mesh app — submitting under an organizational account that already pays the fee is fine.
- A Mac to run Xcode + altool / Transporter.
- An iPhone for screenshots and on-device test. The Simulator is NOT acceptable for App Store screenshots and many App Review rejections cite "did not work as described in the Simulator" — they always run on real hardware.
- App-specific password / API key for upload automation. CI doesn't do this today by design.
- DUNS number — only needed for legal-entity-account enrollment. Individual accounts skip it.

## Submitting checklist

1. Verify everything in this directory still applies to the current `master` (this directory was last refreshed when Phase 3 image attachments landed; protocol changes may obsolete claims in the description / privacy policy).
2. Tag a release: `ios-vX.Y.Z`. The unsigned IPA from CI is sideload-only; you'll re-sign with your Developer Program credentials in Xcode/Transporter.
3. Capture screenshots on a real iPhone running the freshly-signed build.
4. Fill out App Store Connect using the assets here.
5. Submit for review. First-cycle rejections are common — see `review-notes.md` for the most likely sticking points and the rationale to send back.
