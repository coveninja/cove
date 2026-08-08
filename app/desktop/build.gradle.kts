import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.hot.reload)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    // The backend-gate screens shown before :ui takes over (starting / failed /
    // update-pending) need Material widgets in this module directly.
    implementation(compose.material3)
    // Swing dispatcher integration — required so Compose Desktop coroutines land
    // on the right thread after process events.
    implementation(libs.kotlinx.coroutines.swing)
    // Core coroutines used by the backend supervisor package.
    implementation(libs.kotlinx.coroutines.core)
    // In-process libmpv via JNA.
    implementation(libs.jna)
    // JOGL for the OpenGL render path — GLJPanel composites as Swing pixels so
    // it z-orders and clips correctly inside Compose Desktop's SwingPanel.
    // Native JARs cannot be expressed in the version catalog (no classifier
    // support), so they are declared here with explicit coordinates.
    val joglVersion = libs.versions.jogl.get()
    implementation(libs.jogl.all)
    runtimeOnly("org.jogamp.jogl:jogl-all:$joglVersion:natives-linux-amd64")
    implementation(libs.gluegen.rt)
    runtimeOnly("org.jogamp.gluegen:gluegen-rt:$joglVersion:natives-linux-amd64")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.coveninja.cove.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName    = "Cove"
            packageVersion = "0.31.3"
        }
    }
}
