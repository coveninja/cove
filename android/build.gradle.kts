// build.gradle.kts (root) — declares plugin versions without applying them.
// Each subproject applies the plugins it actually needs in its own build script.
plugins {
    // 8.9.2: minimum AGP line with compileSdk 36 support (needed by
    // androidyoutubeplayer 13.x); still compatible with Gradle 8.11.1.
    id("com.android.application")            version "9.3.1"  apply false
    id("org.jetbrains.kotlin.android")       version "2.4.10"  apply false
    // Kotlin 2.x separates the Compose compiler into its own plugin; the old
    // composeOptions.kotlinCompilerExtensionVersion block is gone for this toolchain.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
