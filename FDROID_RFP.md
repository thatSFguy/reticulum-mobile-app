# F-Droid RFP draft

This is the draft body for the Request-For-Packaging issue you'll
file at <https://gitlab.com/fdroid/rfp/-/issues/new>.

**Don't submit yet** — review, then copy the body below into a new
issue. Title: `Reticulum` (no version suffix; F-Droid tracks
versions automatically once added).

---

## Title

```
Reticulum
```

## Categories

```
- Internet
- Connectivity
- Security
```

(F-Droid's category list lives at <https://f-droid.org/en/categories/>.
"Internet" and "Connectivity" are the closest matches; "Security" fits
the off-grid / no-account angle.)

## Body

```markdown
**Native FOSS Android client for the Reticulum LoRa mesh network. No
accounts, no servers, no Google Play Services, no tracking, no
analytics.**

Reticulum is an open mesh-networking protocol (https://reticulum.network)
typically transported over LoRa radio — works fully off-grid, no carrier
infrastructure required. This app is one of two end-user mobile clients
(the other is Markqvist's Sideband, also FOSS but Plyer/Kivy-based).
Built from scratch in Kotlin Multiplatform so the protocol layer is
shared between Android and a future iOS port; only the transport
plumbing differs per-platform.

## Links

- **Source**: https://github.com/thatSFguy/reticulum-mobile-app
- **Issue tracker**: https://github.com/thatSFguy/reticulum-mobile-app/issues
- **License**: AGPL-3.0 (`LICENSE` at repo root)
- **Releases**: https://github.com/thatSFguy/reticulum-mobile-app/releases
  (signed APKs attached to each `android-vX.Y.Z` tag)

## Why F-Droid

- 100% FOSS code, FOSS dependencies — see audited list below.
- Off-grid / no-tracking aesthetic aligned with F-Droid's audience.
- Already structured for F-Droid: fastlane metadata at
  `fastlane/metadata/android/en-US/` (title, descriptions, icon,
  feature graphic, 5 screenshots, per-version changelogs).
- Reproducible build: two `./gradlew :androidApp:clean
  :androidApp:assembleRelease` runs of the same tag produce
  byte-identical APKs. See `REPRODUCIBLE.md` in the repo for the
  pinned-toolchain table and the verifier recipe.

## Build

```
./gradlew :androidApp:assembleRelease \
    -PversionName=X.Y.Z \
    -PversionCode=N
```

Pinned toolchain (from `REPRODUCIBLE.md`):

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.2.2
- Kotlin 2.0.21
- Compose Gradle Plugin 1.7.3
- KSP 2.0.21-1.0.25
- compileSdk / targetSdk 34, minSdk 26

versionName / versionCode are passed in by the tag-driven CI (`-P`
properties consumed in `androidApp/build.gradle.kts`). F-Droid's
build server can derive them from the tag name (`android-vX.Y.Z`
→ versionName X.Y.Z; versionCode is the manifest value at that
tag, recorded as e.g. `10128` for v1.1.28).

## Dependencies (all FOSS)

- AndroidX Compose UI, Material3, Activity, Navigation, Lifecycle,
  Room (all Apache 2.0)
- Bouncy Castle 1.78.1 (MIT)
- ZXing 3.5.3 + ZXing Android Embedded 4.3.0 (Apache 2.0)
- osmdroid 6.1.18 (Apache 2.0) — OpenStreetMap tile rendering
- SQLDelight 2.0.2 (Apache 2.0) — used by the iOS port; built but
  not exercised on Android
- Apache Commons Compress 1.27.1 (Apache 2.0)
- kotlinx-coroutines 1.8.1, kotlinx-datetime 0.6.1 (Apache 2.0)

No Google Play Services, no Firebase, no analytics SDKs, no
Crashlytics. The only outbound network traffic is whatever
transport the user attaches (BLE to a local RNode, TCP to an
`rnsd` of the user's choice — including 127.0.0.1 for purely
local testing) plus OpenStreetMap tile fetches when at least one
observed Reticulum destination carries lat/lon coordinates.

## Anti-Features check

I don't believe any anti-feature flags apply, but flag any of
these if your audit disagrees:

- `NonFreeNet`: not applicable. Default TCP transport node is
  picked at random from a curated rotation of community-run rnsd
  servers (commonly MichMesh, Reticulum2-Qortal, etc.) — the user
  can change it to any host:port or 127.0.0.1. OpenStreetMap tile
  fetches go to OSM's free service.
- `Tracking`, `Ads`, `NonFreeAdd`, `UpstreamNonFree`,
  `NonFreeAssets`, `NonFreeDep`: none.

## Security audit

A full security review landed 2026-05-13 with zero CRITICAL
findings; every HIGH-priority item closed by v1.1.28. The audit
findings + closures are public in `todo.md` § "Security audit
follow-ups", and the README has a "Security model & known
limitations" section that spells out what's protected, what's
not, and the one outstanding iOS follow-up. Notable hardening
shipped:

- Identity private keys wrapped at rest with Android Keystore
  (AES-256-GCM, TEE-bound, StrongBox where available,
  `setUnlockedDeviceRequired(true)`).
- Android Auto Backup disabled so identity DB doesn't flow
  through `adb backup` / Google Drive restore.
- Lockscreen notifications hide message previews.
- `.rmid` identity export passphrase strength gated
  (PBKDF2-HMAC-SHA256 600k iters + AES-CBC + HMAC).
- Constant-time HMAC compares on Token decrypt paths.
- msgpack decoder bounds container element counts.
```

---

## After-you-submit checklist

1. **Watch the issue**: F-Droid maintainers usually pick up RFPs
   within 1-2 weeks. They'll either tag it `Request: Add to F-Droid`
   and submit the MR themselves, or ask the developer to file the
   MR.

2. **If asked to file the MR yourself**: the YAML lives at
   `fdroiddata/metadata/io.github.thatsfguy.reticulum.native.yml`.
   Skeleton looks like:

   ```yaml
   Categories:
     - Internet
     - Connectivity
     - Security
   License: AGPL-3.0-only
   AuthorName: thatSFguy
   SourceCode: https://github.com/thatSFguy/reticulum-mobile-app
   IssueTracker: https://github.com/thatSFguy/reticulum-mobile-app/issues
   Changelog: https://github.com/thatSFguy/reticulum-mobile-app/releases

   AutoName: Reticulum

   RepoType: git
   Repo: https://github.com/thatSFguy/reticulum-mobile-app

   Builds:
     - versionName: 1.1.28
       versionCode: 10128
       commit: android-v1.1.28
       subdir: androidApp
       gradle:
         - yes
       output: build/outputs/apk/release/androidApp-release-unsigned.apk
       prebuild: |
         echo "versionName=1.1.28" >> gradle.properties
         echo "versionCode=10128" >> gradle.properties

   AutoUpdateMode: Version
   UpdateCheckMode: Tags ^android-v\d+\.\d+\.\d+$
   UpdateCheckData: androidApp/build.gradle.kts|versionCode\s+=\s+\(project\.findProperty.*?\?:\s+(\d+).*|.|versionName\s+=\s+\(project\.findProperty.*?\?:\s+"([\d.]+).*
   CurrentVersion: 1.1.28
   CurrentVersionCode: 10128
   ```

   The exact `UpdateCheckData` regex will probably need tweaking by
   F-Droid's tooling — version detection is the most finicky part.
   F-Droid's `fdroid checkupdates` runs locally to verify.

3. **Reproducible build verification**: F-Droid's reproducible build
   pipeline runs `fdroid build io.github.thatsfguy.reticulum.native`
   on their server, then compares the unsigned APK byte-for-byte
   against the developer's signed APK (after stripping signatures).
   If they match, F-Droid publishes the developer-signed APK. The
   verification page is <https://verification.f-droid.org/>.

4. **Signing-key caveat for users**: even with reproducible builds,
   F-Droid won't ship under our GitHub-release signing identity by
   default — they ship their own signed APK. To get the
   bit-identical-to-GitHub flow, set `ReproducibleVerifiedSigningKey:`
   in the metadata YAML (advanced; usually a follow-up after the
   first F-Droid release lands and we've confirmed the build
   matches).

## Don't submit until

- v1.1.28 release on GitHub is **green** (CI succeeded, the signed
  APK is attached, and the F-Droid metadata file is up to date in
  `fastlane/`).
- You've spent at least one day running v1.1.28 on a real device
  to catch any regression that escaped the security pass.
- You're ready to respond to F-Droid maintainer questions within
  the 1-2 week median review window — leaving an RFP stale for
  weeks is the most common reason packaging stalls.

## What to expect

- Median time from clean RFP → merged into the F-Droid repo:
  **2-6 weeks**.
- First-time submission may get a round of nits about the metadata
  YAML or the build recipe. Respond promptly; the maintainers are
  helpful and the bar is mostly "does it match the formatting
  conventions".
- After acceptance, every new tag → F-Droid picks up the update
  automatically via `UpdateCheckMode: Tags` within ~24 hours.
