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
   - `fdroid build io.github.thatsfguy.reticulum.native:10158`
4. Open a merge request. F-Droid maintainers review the recipe and
   iterate on the `Builds:` block with you.

## The one build wrinkle

`androidApp/build.gradle.kts` reads `versionName` / `versionCode` from
Gradle `-P` properties:

```kotlin
versionName = (project.findProperty("versionName") as? String) ?: "0.0.0-dev"
versionCode = (project.findProperty("versionCode") as? String)?.toInt() ?: 1
```

CI passes them, derived from the `android-vX.Y.Z` tag. A plain
`./gradlew :androidApp:assembleRelease` — which is what F-Droid runs —
gets the `0.0.0-dev` / `1` fallback, so the built APK's `versionCode`
won't match the recipe.

Two ways to fix it, in order of preference:

1. **Make the build self-sufficient (recommended).** Have
   `build.gradle.kts` fall back to deriving the version from the
   tagged commit (`git describe --tags --match 'android-v*'`) when the
   `-P` properties are absent. Then F-Droid — and anyone building from
   a tag — gets the right version with no special handling, and the
   recipe's `Builds:` block needs nothing extra.
2. **Inject it in the recipe.** Add a `prebuild:`/`gradleprops` step to
   the `Builds:` entry that supplies the properties. Works, but every
   release needs the recipe touched (unless scripted).

Option 1 is a small, self-contained change to `build.gradle.kts` —
ask the maintainer to make it before submitting and the F-Droid recipe
stays trivial.

## After acceptance

Once merged, `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` mean
F-Droid picks up every future `android-vX.Y.Z` tag automatically — no
recipe edits per release. Add an F-Droid badge/link to the README and
the GitHub Pages site at that point.
