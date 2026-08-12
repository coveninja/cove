import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
            isMinifyEnabled = false
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mpv.android)
    implementation(libs.youtubedl.android)

    testImplementation(libs.kotlin.test)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
