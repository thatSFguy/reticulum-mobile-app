# Reproducible build

This app is built reproducibly. Two clean builds of the same source tree, run on any machine with the same toolchain versions pinned below, produce **byte-identical APKs** (before signing). F-Droid's verification server uses this to prove the APK it ships matches the one the developer signed and tagged on GitHub.

If you want to verify a release yourself:

```bash
git clone https://github.com/thatSFguy/reticulum-mobile-app
cd reticulum-mobile-app
git checkout android-vX.Y.Z

# Pin the toolchain via the values in build.gradle.kts (below).
# JDK 17 is required; OpenJDK 17.0.x works.

./gradlew :androidApp:assembleRelease \
    -PversionName=X.Y.Z \
    -PversionCode=N

sha256sum androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

Compare the hash to the one in the release-page checksum file (e.g. `Reticulum-Android-X.Y.Z-release.apk.sha256` attached to the GitHub release). If they match (after the APK signing block is stripped — `apksigner verify --print-certs` lets you separate signature from contents), the build is reproducible.

## Pinned toolchain

The versions below are the ones every released APK is built against. They live in the build files; updating them is a per-tag decision so a "what was this tag built with" answer is always available.

| Tool | Version | Source of truth |
|---|---|---|
| JDK | 17 (via `kotlin { jvmToolchain(17) }`) | `androidApp/build.gradle.kts:10`, `shared/build.gradle.kts` |
| Gradle | 8.7 | `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl`) |
| Android Gradle Plugin | 8.2.2 | `build.gradle.kts:4-5` |
| Kotlin | 2.0.21 | `build.gradle.kts:6-7,9` |
| Compose Gradle Plugin | 1.7.3 | `build.gradle.kts:8` |
| KSP | 2.0.21-1.0.25 | `build.gradle.kts:10` |
| Android compileSdk / targetSdk | 34 | `androidApp/build.gradle.kts:15,20` |
| Android minSdk | 26 | `androidApp/build.gradle.kts:19` |
| SQLDelight | 2.0.2 | `build.gradle.kts:14` |

App dependencies (Compose 1.7.6, Material3 1.3.1, Room 2.6.1, ZXing 4.3.0 / 3.5.3, osmdroid 6.1.18, Bouncy Castle 1.78.1, kotlinx-coroutines 1.8.1, kotlinx-datetime 0.6.1, Apache Commons Compress 1.27.1) are pinned exactly in `androidApp/build.gradle.kts` and `shared/build.gradle.kts` — no dynamic ranges.

## What was done to make this reproducible

Two settings turn off the non-deterministic bits AGP / Gradle would otherwise stamp into every APK:

1. **All archive tasks (JAR, AAR, APK) zero out file timestamps and sort entries:**
   ```kotlin
   // build.gradle.kts (root)
   allprojects {
       tasks.withType<AbstractArchiveTask>().configureEach {
           isPreserveFileTimestamps = false
           isReproducibleFileOrder = true
       }
   }
   ```

2. **AGP's `dependenciesInfo` block is disabled.** It otherwise stamps the APK with dependency metadata that includes build-environment info (toolchain versions, timestamps) and varies between developer machines and the F-Droid build server:
   ```kotlin
   // androidApp/build.gradle.kts
   android {
       dependenciesInfo {
           includeInApk = false
           includeInBundle = false
       }
   }
   ```

The information `dependenciesInfo` would have included is recoverable from the lockfile / `build.gradle.kts` checked into the repo, so this isn't a loss of audit data.

## Signing

Releases on GitHub are signed with a keystore held in GitHub Actions secrets (`RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). The signing certificate fingerprint (SHA-256) is:

```
TODO: capture from the signed v1.1.28 release APK with:
  apksigner verify --print-certs Reticulum-Android-1.1.28-release.apk | grep "SHA-256 digest"
```

Anyone verifying a build should:

1. Build the unsigned APK locally (steps above).
2. Pull the signed APK from the GitHub release.
3. Strip the APK signing block from the signed APK using `apksigner` and compare the resulting v2-signing-stripped content against the local unsigned APK. They should match byte-for-byte.

## F-Droid

F-Droid's reproducible-build flow:

1. F-Droid pulls our git tag.
2. F-Droid's build server runs the same `./gradlew :androidApp:assembleRelease -PversionName=X.Y.Z -PversionCode=N` we use.
3. F-Droid compares the resulting unsigned APK to ours (signature-stripped).
4. If they match → F-Droid publishes **our signed APK** unchanged. The APK on F-Droid is bit-identical to the one on GitHub.
5. If they don't match → F-Droid flags the build and asks us to investigate. (This has only happened once historically when we forgot to disable `dependenciesInfo`.)

## What can still break reproducibility

A short list of things to watch for if a future change causes the SHA-256 to drift between builds:

- **Timestamps in source files.** Generated source files (Compose `_$LiveLiterals$*.kt`, Room generated DAOs) should be deterministic in modern KSP / AGP, but a Kotlin compiler upgrade can change this. Re-pin the version if so.
- **Dependency version drift.** A `+` or open range in any `implementation(...)` line would let Maven Central serve different versions to different builders. Always pin exact versions.
- **Plugin output ordering.** Some Gradle plugins emit files in filesystem-discovery order rather than alphabetical. The `isReproducibleFileOrder = true` in the root build script covers archive tasks; if a new plugin lands that creates its own output directory directly, it may need its own ordering fix.
- **System locale.** Some text-comparison code paths sort by locale. We don't currently have any such code, but ProGuard/R8 mapping files when enabled in the future can be locale-sensitive — set `LC_ALL=C` in CI if you turn minification on.

Run `./gradlew :androidApp:clean :androidApp:assembleRelease ...` twice in a row on the same machine, sha256 the outputs, and confirm they match before tagging any release.
