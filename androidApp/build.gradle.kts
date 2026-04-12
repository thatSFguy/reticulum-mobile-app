plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.thatsfguy.reticulum.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.thatsfguy.reticulum.native"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // TODO: Add signingConfigs for release builds, same pattern
            // as the webclient's build-apk.yml (env-driven keystore).
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
