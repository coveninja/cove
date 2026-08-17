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
    )
    val values = keys.associateWith { providers.environmentVariable(it).orElse("") }
    // Captured as locals so the doLast lambda holds plain values and Providers rather than a
    // reference to this build script — the configuration cache cannot serialize the latter.
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
    // The backend-gate screens shown before :ui takes over (starting / failed /
    // update-pending) need Material widgets in this module directly.
    implementation(compose.material3)
    // Swing dispatcher integration — required so Compose Desktop coroutines land
    // on the right thread after process events.
    implementation(libs.kotlinx.coroutines.swing)
    // Core coroutines used by the backend supervisor package.
    implementation(libs.kotlinx.coroutines.core)
    // mpv reports its track list as a JSON string property; parsing it is the
    // only place this module needs serialization.
    implementation(libs.kotlinx.serialization.json)
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
        // Compose's run/jlink/jpackage tasks otherwise use the JVM that happens
        // to run Gradle. Iconify and Cove target Java 21, so pin the actual app
        // launcher and bundled runtime to the same provisioned toolchain.
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
                "jdk.unsupported",
            )
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName    = "Cove"
            packageVersion = coveVersion
        }
    }
}

// libtorrent installs its own SIGSEGV handler once a torrent has been added, and
// that is the handler HotSpot needs: JIT-compiled code omits null tests and lets
// the CPU fault, then turns the fault into a NullPointerException. With libtorrent
// owning it, the next null dereference anywhere in the process is fatal — on
// whichever thread reaches one first, which is why the crash never landed near the
// torrent code.
//
// libjsig is the JDK's answer: it interposes sigaction so both handlers live. It
// has to be preloaded before the JVM installs its own handlers, though, so it
// cannot be loaded from inside the app the way the libstdc++ fix is — hence this,
// on the task that launches the JVM.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val runtime = desktopJava.get().metadata.installationPath.asFile
    val jsig = sequenceOf("lib/libjsig.so", "lib/libjsig.dylib")
        .map { runtime.resolve(it) }
        .firstOrNull { it.isFile }
    if (jsig != null) environment("LD_PRELOAD", jsig.absolutePath)
}
