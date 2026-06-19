plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}

/**
 * Derive `(versionName, versionCode)` from the git tag when HEAD is
 * sitting *exactly* on an `android-v*` tag — e.g. `android-v1.1.58`
 * → `("1.1.58", 10158)`. `versionCode = major*10000 + minor*100 +
 * patch`, the same scheme `android-release.yml` computes from the tag.
 *
 * This is the from-source fallback: CI still passes `-PversionName` /
 * `-PversionCode` explicitly and those always win. When they're absent
 * but the checkout is a tagged release commit — F-Droid builds exactly
 * that — the build now reads the real version off the tag instead of
 * shipping the `0.0.0-dev` / `1` placeholder. `--exact-match` means a
 * mid-development local build (commits past the last tag) still gets
 * `0.0.0-dev`, so "this isn't a release artifact" stays obvious.
 *
 * Returns null when git isn't available, HEAD isn't on a matching
 * tag, or the tag doesn't parse.
 */
fun gitDerivedVersion(): Pair<String, Int>? {
    val tag = runCatching {
        val proc = ProcessBuilder(
            "git", "describe", "--tags", "--exact-match", "--match", "android-v*",
        ).directory(rootDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) out else null
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

    val m = Regex("""android-v(\d+)\.(\d+)\.(\d+)""").matchEntire(tag) ?: return null
    val (maj, min, pat) = m.destructured
    return "$maj.$min.$pat" to (maj.toInt() * 10_000 + min.toInt() * 100 + pat.toInt())
}

android {
    namespace = "io.github.thatsfguy.reticulum.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.thatsfguy.reticulum.native"
        minSdk = 26
        targetSdk = 34
        // Tag is the source of truth. Precedence:
        //   1. `-PversionName` / `-PversionCode` — what `android-release.yml`
        //      passes, derived from the `android-vX.Y.Z` tag. Always wins.
        //   2. [gitDerivedVersion] — read back from the tag when the
        //      checkout sits exactly on an `android-v*` tag. This is the
        //      from-source path: F-Droid (and any tagged-commit build)
        //      gets the real version with no `-P` flags.
        //   3. `0.0.0-dev` / `1` — a local mid-development build with no
        //      tag; the About screen showing "0.0.0-dev" makes it obvious
        //      this is not a release artifact.
        val gitVersion = gitDerivedVersion()
        versionName = (project.findProperty("versionName") as? String)
            ?: gitVersion?.first
            ?: "0.0.0-dev"
        versionCode = (project.findProperty("versionCode") as? String)?.toInt()
            ?: gitVersion?.second
            ?: 1
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    if (releaseKeystore != null) {
        val storePass = System.getenv("RELEASE_STORE_PASSWORD")
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = storePass
                keyAlias      = System.getenv("RELEASE_KEY_ALIAS")
                // Fall back to the store password when no separate key
                // password is supplied. GitHub Actions exports a missing
                // secret as the empty string (not null), so check for
                // both — otherwise we sign with "" and JCE rejects the
                // key with "Given final block not properly padded".
                keyPassword   = System.getenv("RELEASE_KEY_PASSWORD")
                    ?.takeIf { it.isNotEmpty() }
                    ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Distinct applicationId so a debug build installs side-by-side
        // with a release install (e.g. for on-device testing) instead of
        // failing on the signature mismatch. No content providers use a
        // hardcoded authority, so the suffix is conflict-free.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // F-Droid reproducible-build hygiene. AGP's "dependency
    // metadata" block stamps the APK with build-environment
    // info (toolchain versions, dependency list with timestamps)
    // that differs between developer machines and the F-Droid
    // build server, so two byte-different APKs result from
    // identical source. Turning it off means the APK doesn't
    // carry that metadata; users can still derive the same info
    // from the lockfile / build.gradle.kts checked into the
    // repo. Audit reference: 2026-05-13 F-Droid prep.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Pin the AGP packaging task's timestamp to the Unix epoch +
    // a small constant so two builds of the same source tree
    // produce byte-identical APKs regardless of when they ran.
    // Combined with the AbstractArchiveTask config in the root
    // build.gradle.kts this covers both the AAR/JAR archive
    // path (used by :shared) and the APK packaging path (used
    // by :androidApp).
    androidComponents {
        onVariants { variant ->
            variant.packaging.jniLibs.useLegacyPackaging.set(false)
        }
    }

    // Generate BuildConfig so the About screen can show the actual
    // versionName at runtime instead of a hard-coded literal that
    // drifts every release.
    buildFeatures {
        buildConfig = true
    }

    // Export Room schemas for diffing across migrations.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    // Full system emoji grid (search + recents) for the reaction picker —
    // the 6-emoji quick palette is the fast path, this is the "+" overflow.
    implementation("androidx.emoji2:emoji2-emojipicker:1.4.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // QR code generation + scanner Activity. Transitive AppCompat is required
    // by CaptureActivity; fine to pull in alongside Material3.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines (kotlinx-coroutines-android adds Main dispatcher for Android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
