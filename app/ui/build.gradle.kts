plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm("desktop")

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)

            implementation(compose.materialIconsExtended)

            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)
            implementation("com.ongshok:iconify:1.0.4")

            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
