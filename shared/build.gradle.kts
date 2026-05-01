plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(17)
    androidTarget()

    // Uncomment when ready to add iOS:
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                // No msgpack lib: we ship a small in-tree codec under
                // io.github.thatsfguy.reticulum.codec because the third-party
                // multiplatform options drift from upstream LXMF on numeric
                // widths and we already need the dual-variant verify path.
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
