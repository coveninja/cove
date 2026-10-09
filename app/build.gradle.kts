import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Plugin versions are declared here and applied by subprojects.
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
    alias(libs.plugins.android.test)           apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// Gradle's default test output names the exception type and the line it was thrown from, but
// not its message — so a helper that builds a diagnostic string on timeout reports nothing a
// CI-only failure can be read from. The plugin-sandbox tests depend on exactly that message.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

// Aggregate the test suites behind the conventional root task.
tasks.register("test") {
    group = "verification"
    dependsOn(
        ":shared:allTests",
        ":backend:allTests",
        ":ui:allTests",
        ":desktop:test",
        ":mobile:testDebugUnitTest",
    )
}
