// Root build file: declares plugin versions, never applies them here.
// Each subproject applies only the plugins it needs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)  apply false
    alias(libs.plugins.kotlin.jvm)            apply false
    alias(libs.plugins.kotlin.compose)        apply false
    alias(libs.plugins.kotlin.serialization)  apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.hot.reload)    apply false
}

// Convenience: `./gradlew test` from the app root runs both the shared KMP
// tests (desktopTest target) and the desktop JVM test suite.
tasks.register("test") {
    group = "verification"
    dependsOn(":shared:allTests", ":desktop:test")
}
