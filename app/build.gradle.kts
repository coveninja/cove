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
