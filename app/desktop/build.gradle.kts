import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion

val coveVersion = rootProject.file("../VERSION").readText().trim()
val generatedCoveConfig = layout.buildDirectory.dir("generated/cove-config")
val generateCoveConfig by tasks.registering {
    val keys = listOf(
        "TMDB_API_KEY",
        "SUPABASE_URL",
        "SUPABASE_PUBLISHABLE_KEY",
        "TRAKT_CLIENT_ID",
        "TRAKT_CLIENT_SECRET",
        "UPDATE_PUBLIC_KEYS",
        "PLUGIN_PUBLIC_KEYS",
        "PLUGIN_CATALOG_API_BASE",
    )
    val values = keys.associateWith { providers.environmentVariable(it).orElse("") }
    // Avoid capturing the non-serializable build script in doLast.
    val version = coveVersion
    val outputDir = generatedCoveConfig
    inputs.property("COVE_VERSION", version)
    values.forEach { (key, value) -> inputs.property(key, value) }
    outputs.dir(outputDir)
    doLast {
        val output = outputDir.get().file("cove-build.properties").asFile
        output.parentFile.mkdirs()
        val lines = buildList {
            add("COVE_VERSION=${version.replace("\n", "")}")
            values.forEach { (key, value) ->
                value.orNull?.takeIf(String::isNotBlank)?.let {
                    add("$key=${it.replace("\\", "\\\\").replace("\n", "")}")
                }
            }
        }
        output.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.hot.reload)
}

val desktopJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

sourceSets.main {
    resources.srcDir(generatedCoveConfig)
}

tasks.named("processResources") {
    dependsOn(generateCoveConfig)
}

dependencies {
    implementation(project(":backend"))
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    // Used by backend-gate screens before :ui takes over.
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    // JOGL for the OpenGL render path — GLJPanel composites as Swing pixels so
    // it z-orders and clips correctly inside Compose Desktop's SwingPanel.
    // Native JARs cannot be expressed in the version catalog (no classifier
    // support), so they are declared here with explicit coordinates.
    val joglVersion = libs.versions.jogl.get()
    val osName = System.getProperty("os.name")
    val osArch = System.getProperty("os.arch")
    val joglNatives = when {
        osName.startsWith("Mac", ignoreCase = true) -> "natives-macosx-universal"
        osName.startsWith("Windows", ignoreCase = true) -> "natives-windows-amd64"
        osName.startsWith("Linux", ignoreCase = true) &&
            (osArch.equals("aarch64", ignoreCase = true) || osArch.equals("arm64", ignoreCase = true)) ->
            "natives-linux-aarch64"
        osName.startsWith("Linux", ignoreCase = true) -> "natives-linux-amd64"
        else -> error("Unsupported desktop platform: $osName ($osArch)")
    }
    implementation(libs.jogl.all)
    runtimeOnly("org.jogamp.jogl:jogl-all:$joglVersion:$joglNatives")
    implementation(libs.gluegen.rt)
    runtimeOnly("org.jogamp.gluegen:gluegen-rt:$joglVersion:$joglNatives")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        // Pin the launcher and bundled runtime to the provisioned Java 21 toolchain.
        javaHome = desktopJava.get().metadata.installationPath.asFile.absolutePath
        mainClass = "com.coveninja.cove.desktop.MainKt"
        nativeDistributions {
            // jlink cannot discover modules referenced only through JDBC,
            // Graal/polyglot, JNA, and other reflective service loading.
            modules(
                "java.management",
                "java.naming",
                "java.net.http",
                "java.scripting",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName    = "Cove"
            packageVersion = coveVersion
            // jpackage requires a platform-specific icon container.
            val iconDir = rootProject.file("../packaging/icons")
            linux { iconFile.set(iconDir.resolve("cove.png")) }
            windows { iconFile.set(iconDir.resolve("cove.ico")) }
            macOS {
                bundleID = "io.github.coveninja.Cove"
                iconFile.set(iconDir.resolve("cove.icns"))
                val entitlements = rootProject.file("../packaging/macos/entitlements.plist")
                entitlementsFile.set(entitlements)
                runtimeEntitlementsFile.set(entitlements)
            }
        }
    }
}

// libtorrent replaces HotSpot's SIGSEGV handler after torrent use. Preload libjsig
// before the JVM starts so both handlers remain active.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val runtime = desktopJava.get().metadata.installationPath.asFile
    val jsig = sequenceOf("lib/libjsig.so", "lib/libjsig.dylib")
        .map { runtime.resolve(it) }
        .firstOrNull { it.isFile }
    if (jsig != null) environment("LD_PRELOAD", jsig.absolutePath)
}
