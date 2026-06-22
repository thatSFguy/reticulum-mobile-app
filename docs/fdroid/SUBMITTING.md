# Getting the app into F-Droid

F-Droid is the natural distribution channel for this app: it's the
store the privacy / off-grid audience already uses, it needs no Google
account, and it builds every app from source on its own infrastructure
— which fits an app that ships no Google services and has reproducible
builds.

This folder is a **staging area**. The actual F-Droid metadata lives in
F-Droid's own repository, submitted as a merge request.

## What's already done

The app side is ready:

- **`fastlane/metadata/android/en-US/`** is complete — `title.txt`,
  `short_description.txt`, `full_description.txt`, `images/icon.png`,
  `images/featureGraphic.png`, five `phoneScreenshots/`, and a
  per-`versionCode` `changelogs/` file for every release. F-Droid reads
  all of this **automatically** from the source repo — it does not go
  in the recipe.
- No proprietary dependencies: no Google Play Services, no Firebase, no
  analytics. All dependencies (Bouncy Castle, Room, osmdroid, …) are
  FOSS. There are **no F-Droid anti-features** to declare.
- One thing worth mentioning to reviewers up front: the Nodes map uses
  osmdroid, which fetches OpenStreetMap tiles over HTTP — a normal,
  user-visible network call, not tracking. Everything else is
  on-device.

## How to submit

1. Fork **https://gitlab.com/fdroid/fdroiddata**.
2. Add the recipe as `metadata/io.github.thatsfguy.reticulum.native.yml`
   — start from [`io.github.thatsfguy.reticulum.native.yml`](io.github.thatsfguy.reticulum.native.yml)
   in this folder.
3. Validate locally if you can:
   - `fdroid lint io.github.thatsfguy.reticulum.native`
   - `fdroid build io.github.thatsfguy.reticulum.native:10281`
4. Open a merge request. F-Droid maintainers review the recipe and
   iterate on the `Builds:` block with you.

The live submission CI runs on the `add-reticulum-native` branch of the
`thatSFguy/fdroiddata` fork — push the recipe there (NOT to the fork's
`master`, where fdroiddata's CI rules filter every job out and the
pipeline "fails" with zero jobs).

## The build version — resolved

`androidApp/build.gradle.kts` reads `versionName` / `versionCode` from
Gradle `-P` properties that CI passes (derived from the `android-vX.Y.Z`
tag). A plain `./gradlew :androidApp:assembleRelease` — which is what
F-Droid runs — used to get a `0.0.0-dev` / `1` fallback that wouldn't
match the recipe.

**This is now handled in the build itself.** `build.gradle.kts` has a
`gitDerivedVersion()` fallback: when the `-P` properties are absent but
the checkout sits exactly on an `android-v*` tag — which is precisely
what F-Droid builds (`commit: android-v1.1.58`) — it reads the version
back off the tag via `git describe --tags --exact-match`. So F-Droid's
plain `assembleRelease` produces the correct `versionCode` with **no
`prebuild` step and no `-P` flags** in the recipe.

(A mid-development local build, not on a tag, still falls back to
`0.0.0-dev` / `1` — so "this isn't a release artifact" stays obvious.)

Because that `versionCode` is *computed* (`major*10000 + minor*100 +
patch`) rather than a literal in `build.gradle.kts`, F-Droid's static
`checkupdates` scanner can't read it — it reports "Couldn't find any
version information". So the recipe uses **`UpdateCheckMode: None` +
`AutoUpdateMode: None`**: no automatic update detection. Each new release
needs a one-line `Builds:` entry (versionName / versionCode / `commit:
android-vX.Y.Z`) plus a `CurrentVersion*` bump, added via a follow-up MR.
(Future option: wire a `VercodeOperation` so auto-update can work despite
the computed code — leave that to the maintainer during review.)

## Build toolchain — JDK (resolved 1.2.81)

F-Droid's buildserver (Debian trixie) ships only JDK 21 and disables
Gradle toolchain auto-download. Up to 1.2.80, `:shared` and `:androidApp`
hard-pinned `jvmToolchain(17)`, which couldn't resolve there, so `fdroid
build` failed before compiling. Fixed in **android-v1.2.81**: the
toolchain pin is gone and only the *output* bytecode is pinned to 17
(Kotlin via `compilerOptions.jvmTarget`, Java via `compileOptions`), so
the build runs on any JDK ≥ 17. The recipe therefore targets 1.2.81, not
1.2.80, and needs no `sudo`/`prebuild` JDK shim.

## After acceptance

Update-check is `None`, so new releases do **not** appear automatically —
add a `Builds:` entry + `CurrentVersion*` bump per release via MR (see
above). Add an F-Droid badge/link to the README and the GitHub Pages site
once the first build is published.
