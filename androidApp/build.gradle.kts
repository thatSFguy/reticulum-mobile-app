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

android {
    namespace = "io.github.thatsfguy.reticulum.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.thatsfguy.reticulum.native"
        minSdk = 26
        targetSdk = 34
        versionCode = 50
        versionName = "0.1.49"
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
