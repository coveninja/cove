plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm("desktop")
    android {
        namespace = "com.coveninja.cove.backend"
        compileSdk = 37
        minSdk = 28
        withHostTest {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // Android API 28 does not guarantee a system Ed25519 provider. Bundle
            // one so signed update manifests verify identically on every host.
            implementation(libs.bouncycastle.provider)
        }
        val desktopMain by getting {
            kotlin.srcDir("src/jvmSharedMain/kotlin")
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.server.partial.content)
                implementation(libs.ktor.server.sse)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.jlibtorrent)
                // Only for FatalSignalHandlers: libtorrent takes SIGSEGV over from
                // HotSpot, and sigaction() is how it is handed back.
                implementation(libs.jna)
                implementation(libs.graal.polyglot)
                implementation(libs.graal.js)
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                val torrentVersion = libs.versions.jlibtorrent.get()
                val nativeArtifact = when {
                    os.contains("win") -> "jlibtorrent-windows"
                    os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "jlibtorrent-macosx-arm64"
                    os.contains("mac") -> "jlibtorrent-macosx-x86_64"
                    arch.contains("aarch64") || arch.contains("arm64") -> "jlibtorrent-linux-arm64"
                    else -> "jlibtorrent-linux-x86_64"
                }
                runtimeOnly("com.frostwire:$nativeArtifact:$torrentVersion")
            }
        }
        androidMain {
            // Portable JVM services back the same embedded API on desktop and
            // Android; native media and scraper isolation remain platform-owned.
            kotlin.srcDirs(
                "src/jvmSharedMain/kotlin",
            )
            resources.srcDir("src/desktopMain/resources")
            dependencies {
                implementation(libs.sqldelight.android.driver)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.server.partial.content)
                implementation(libs.ktor.server.sse)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.jlibtorrent)
                implementation(libs.quickjs.android)
                val torrentVersion = libs.versions.jlibtorrent.get()
                implementation("com.frostwire:jlibtorrent-android-arm:$torrentVersion")
                implementation("com.frostwire:jlibtorrent-android-arm64:$torrentVersion")
                implementation("com.frostwire:jlibtorrent-android-x86:$torrentVersion")
                implementation("com.frostwire:jlibtorrent-android-x86_64:$torrentVersion")
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
                implementation(libs.ktor.server.test.host)
            }
        }
    }
}

sqldelight {
    databases {
        create("CoveDatabase") {
            packageName.set("com.coveninja.cove.backend.db")
        }
    }
}
