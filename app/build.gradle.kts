// Root build file: declares plugin versions, never applies them here.
// Each subproject applies only the plugins it needs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)  apply false
    alias(libs.plugins.kotlin.jvm)            apply false
    alias(libs.plugins.kotlin.compose)        apply false
    alias(libs.plugins.kotlin.serialization)  apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.hot.reload)    apply false
    alias(libs.plugins.sqldelight)             apply false
    alias(libs.plugins.android.application)    apply false
    alias(libs.plugins.android.kmp.library)    apply false
}

// Convenience: `./gradlew test` from the app root covers the shared KMP
// targets, presentation logic, desktop JVM suite, and mobile host logic.
tasks.register("test") {
    group = "verification"
    dependsOn(
        ":shared:allTests",
        ":backend:allTests",
        // Every presentation-logic suite lives here — the My List, calendar, explore, home
        // and playback models. Omitting it meant `make test` passed without running any of
        // them, which is the opposite of what a green run is supposed to mean.
        ":ui:allTests",
        ":desktop:test",
        ":mobile:testDebugUnitTest",
    )
}
