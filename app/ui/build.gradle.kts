plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm("desktop")
    android {
        namespace = "com.coveninja.cove.ui"
        compileSdk = 36
        minSdk = 28
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)

            implementation(compose.materialIconsExtended)

            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)

            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
