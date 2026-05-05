plugins {
    // Versions are declared here and applied in submodules via `apply false`.
    // Update these when upgrading the KMP or Android Gradle Plugin version.
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    // SQLDelight powers the iOS-side storage actual (Phase 2). Android
    // continues to use Room — SQLDelight's iosMain `.sq` files don't
    // affect the Android build.
    id("app.cash.sqldelight") version "2.0.2" apply false
}
