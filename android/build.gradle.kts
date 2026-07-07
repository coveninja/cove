// build.gradle.kts (root) — declares plugin versions without applying them.
// Each subproject applies the plugins it actually needs in its own build script.
plugins {
    id("com.android.application")            version "8.7.3"  apply false
    id("org.jetbrains.kotlin.android")       version "2.1.0"  apply false
    // Kotlin 2.x separates the Compose compiler into its own plugin; the old
    // composeOptions.kotlinCompilerExtensionVersion block is gone for this toolchain.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
