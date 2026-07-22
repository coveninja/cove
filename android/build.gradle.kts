// build.gradle.kts (root) — declares plugin versions without applying them.
// Each subproject applies the plugins it actually needs in its own build script.
plugins {
    // AGP 9.x provides built-in Kotlin support and requires Gradle 9.5+.
    id("com.android.application")            version "9.3.0"  apply false
    // Kotlin 2.x separates the Compose compiler into its own plugin; the old
    // composeOptions.kotlinCompilerExtensionVersion block is gone for this toolchain.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
