import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.androidx.baselineprofile)
}

val coveVersion = rootProject.file("../VERSION").readText().trim().removePrefix("v")
val localProperties = Properties().also { properties ->
    rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        FileInputStream(file).use(properties::load)
    }
}
val repositoryEnvironment = Properties().also { properties ->
    rootProject.file("../.env").takeIf { it.isFile }?.let { file ->
        FileInputStream(file).use(properties::load)
    }
}

fun deploymentValue(key: String): String =
    providers.environmentVariable(key).orNull
        ?: localProperties.getProperty(key)
        ?: repositoryEnvironment.getProperty(key)
        ?: ""

fun quotedBuildConfig(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
    append('"')
}

fun semanticVersionCode(version: String): Int = version.split('.')
    .take(3)
    .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { parts ->
        val normalized = parts + List(3 - parts.size) { 0 }
        normalized[0] * 10_000 + normalized[1] * 100 + normalized[2]
    }
    .coerceAtLeast(1)

android {
    namespace = "com.coveninja.cove"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coveninja.cove"
        minSdk = 28
        targetSdk = 36
        versionCode = semanticVersionCode(coveVersion)
        versionName = coveVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "BENCHMARK_FIXTURE", "false")
        manifestPlaceholders["benchmarkControlEnabled"] = "false"
        buildConfigField("String", "TMDB_API_KEY", quotedBuildConfig(deploymentValue("TMDB_API_KEY")))
        buildConfigField("String", "SUPABASE_URL", quotedBuildConfig(deploymentValue("SUPABASE_URL")))
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            quotedBuildConfig(
                deploymentValue("SUPABASE_PUBLISHABLE_KEY")
                    .ifBlank { deploymentValue("SUPABASE_ANON_KEY") },
            ),
        )
        buildConfigField("String", "TRAKT_CLIENT_ID", quotedBuildConfig(deploymentValue("TRAKT_CLIENT_ID")))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", quotedBuildConfig(deploymentValue("TRAKT_CLIENT_SECRET")))
        buildConfigField("String", "UPDATE_PUBLIC_KEYS", quotedBuildConfig(deploymentValue("UPDATE_PUBLIC_KEYS")))
        buildConfigField(
            "String",
            "UPDATE_API_BASE",
            quotedBuildConfig(
                deploymentValue("UPDATE_API_BASE")
                    .ifBlank { "https://api.github.com/repos/coveninja/cove" },
            ),
        )
    }

    signingConfigs {
        System.getenv("ANDROID_KEYSTORE_FILE")?.let { keystorePath ->
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmarkRelease") {
            // Macrobenchmarks must exercise the same optimized code that ships. A standalone
            // build type defaults to an unminified debug-like configuration, which made the
            // physical numbers pessimistic and hid R8/profile interactions seen by release.
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "BENCHMARK_FIXTURE", "true")
            manifestPlaceholders["benchmarkControlEnabled"] = "true"
            matchingFallbacks += listOf("release")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "BENCHMARK_FIXTURE", "true")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/AL2.0",
            "/META-INF/LGPL2.1",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }
    sourceSets.getByName("main").assets.srcDir("../backend/src/desktopMain/resources")
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":backend"))
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.coil3)
    implementation(libs.coil3.network.ktor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mpv.android)
    implementation(libs.youtubedl.android)
    baselineProfile(project(":benchmark"))

    testImplementation(libs.kotlin.test)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.1")
}
