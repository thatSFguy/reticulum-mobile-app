import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    // Phase 2: SQLDelight powers the iOS-side storage actual. The .sq
    // files live under shared/src/iosMain/sqldelight/ so the generated
    // Kotlin code is iosMain-only — Android continues using Room and
    // doesn't see SQLDelight at all.
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("ReticulumIosDatabase") {
            packageName.set("io.github.thatsfguy.reticulum.storage")
            // Default srcDirs (commonMain/sqldelight) keeps the
            // generated Database class accessible to both platforms,
            // but at runtime only iOS instantiates it — Android uses
            // Room instead. The SQLDelight runtime is a small commonMain
            // dep paid by both.
        }
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget()

    // Apply the default source-set hierarchy template so `iosMain` is
    // available as a parent of iosArm64Main / iosSimulatorArm64Main /
    // iosX64Main. Without this explicit call, `val iosMain by getting`
    // can fail at config time depending on KGP target-eval order.
    applyDefaultHierarchyTemplate()

    // iOS Phase 1 (v0.1.84): produce a `Shared.xcframework` consumable by
    // an Xcode project. This is a "linker-clean stubs" milestone — every
    // expect in commonMain has an actual reachable from the iOS source
    // set, so the framework links cleanly. The actuals throw at runtime
    // for now; Phase 2 fills them in (CryptoKit, CoreBluetooth,
    // SQLDelight, NWConnection, libbz2 cinterop). Static linking is the
    // KMP convention — it sidesteps dyld + sim-vs-device fat-binary
    // headaches and keeps the Xcode integration single-framework.
    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        // Phase 2B: per-target -L pointing at the slice of
        // libReticulumCrypto.a that buildIosCryptoBridge produces for
        // this target. The library exports rcr_* functions wrapping
        // CryptoKit's Curve25519 surface (see shared/iosCryptoBridge/).
        val cryptoBridgeLibDir = layout.buildDirectory
            .dir("iosCryptoBridge/${target.name}")
            .get().asFile.absolutePath
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
            linkerOpts("-L$cryptoBridgeLibDir")
        }
        target.compilations.getByName("main").cinterops {
            // Phase 2: cinterop bridge to the bzip2 library that ships
            // with every iOS install at /usr/lib/libbz2.tbd.
            create("bz2") {
                defFile(project.file("src/nativeInterop/cinterop/bz2.def"))
                packageName("io.github.thatsfguy.reticulum.codec.cinterop.bz2")
            }
            // Phase 2B: cinterop to the Swift CryptoKit wrapper. Decls
            // are inlined in the def file; the static library is built
            // separately by buildIosCryptoBridge (Mac-only) and linked
            // via the per-target -L set on the framework above.
            create("reticulumcrypto") {
                defFile(project.file("src/nativeInterop/cinterop/reticulumcrypto.def"))
                packageName("io.github.thatsfguy.reticulum.crypto.cinterop")
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                // No msgpack lib: we ship a small in-tree codec under
                // io.github.thatsfguy.reticulum.codec because the third-party
                // multiplatform options drift from upstream LXMF on numeric
                // widths and we already need the dual-variant verify path.
                // SQLDelight runtime — Phase 2 storage actual on iOS.
                // Android pulls this in too because the generated
                // Database class lives in commonMain, but the actual
                // SQLite driver (and any DB instantiation) is iOS-only;
                // Android's Room storage is unchanged.
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
        val androidMain by getting {
            dependencies {
                // Bouncy Castle for Ed25519, X25519, HKDF
                implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
                // Room for SQLite storage
                implementation("androidx.room:room-runtime:2.6.1")
                implementation("androidx.room:room-ktx:2.6.1")
                // osmdroid for the Nodes map view
                implementation("org.osmdroid:osmdroid-android:6.1.18")
                // bzip2 — used by RNS.Resource when transferring large
                // payloads (LXMF propagation /get round 2 responses, etc.)
                implementation("org.apache.commons:commons-compress:1.27.1")
            }
        }
        val iosMain by getting {
            dependencies {
                // SQLDelight Native driver — wires the generated
                // Database class to a real SQLite instance running in
                // each iOS target's address space.
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }
        val iosTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

android {
    namespace = "io.github.thatsfguy.reticulum.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26  // Android 8.0 — BLE APIs stable, Bluetooth permissions model
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Phase 2B build of shared/iosCryptoBridge/ReticulumCrypto.swift into a
// per-target static library. Macros only — `swiftc` lives in Xcode. On
// Linux/Windows runners this task fails at execution but it's only a
// dependency of iOS link tasks which themselves can't run off macOS.
val buildIosCryptoBridge = tasks.register<Exec>("buildIosCryptoBridge") {
    description = "Compile the CryptoKit Swift wrapper to per-target static libraries."
    group = "build"
    workingDir = project.file("iosCryptoBridge")
    commandLine = listOf("bash", "build.sh")
    inputs.file("iosCryptoBridge/ReticulumCrypto.swift")
    inputs.file("iosCryptoBridge/build.sh")
    outputs.dir(layout.buildDirectory.dir("iosCryptoBridge"))
}

// The link step needs the .a in place; the cinterop step only reads the
// def file's C declarations and doesn't touch the binary, so we don't
// need to gate cinterop on the build (and gating cinterop would make the
// Native cinterop tasks fail on non-Mac configuration loads).
afterEvaluate {
    tasks.matching {
        it.name.startsWith("link") &&
            (it.name.contains("IosArm64") ||
                it.name.contains("IosSimulatorArm64") ||
                it.name.contains("IosX64"))
    }.configureEach {
        dependsOn(buildIosCryptoBridge)
    }
}
