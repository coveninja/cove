import java.io.FileInputStream
import java.util.Properties

// Load developer secrets from local.properties (gitignored).
// TMDB_API_KEY is passed into BuildConfig so CoveApplication can forward it
// to the Go backend at startup without hardcoding it in source.
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(FileInputStream(f))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x Compose compiler plugin — replaces the old composeOptions block.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.coveninja.cove"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coveninja.cove"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Expose TMDB_API_KEY as a BuildConfig constant so CoveApplication can
        // pass it to the Go backend. Defaults to empty string so builds without
        // a local.properties still compile (TMDB calls will fail at runtime, as
        // they do on desktop without a .env file).
        val tmdbApiKey = localProps.getProperty("TMDB_API_KEY", "")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        val backendUrl = localProps.getProperty("BACKEND_URL", "http://127.0.0.1:6969/api")
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        // Supabase auth — publishable anon key only; service key must never enter BuildConfig.
        val supabaseUrl = localProps.getProperty("SUPABASE_URL", "")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        val supabaseAnonKey = localProps.getProperty("SUPABASE_ANON_KEY", "")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildFeatures {
        // AGP 8 defaults buildConfig generation to false; we use BuildConfig for
        // TMDB_API_KEY and VERSION_NAME so this must be explicitly enabled.
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // gomobile-generated AAR containing the embedded Go backend.
    // Built by `make android-aar`; see android/README.md for prerequisites.
    implementation(files("libs/cove.aar"))

    // Jetpack Compose — BOM pins all compose artifacts to a single tested set.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // activity-compose bridges ComponentActivity.setContent with Compose.
    implementation("androidx.activity:activity-compose:1.9.3")

    // OkHttp — health-check polls to /api/ping from MainActivity; kept here
    // for future backend API communication from the Compose layer.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // kotlinx-serialization JSON — on hand for parsing backend responses
    // once the full Compose UI (Phase 4) starts consuming typed API payloads.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil for async image loading in Compose.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // ViewModel + lifecycle integration for Compose.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // ProcessLifecycleOwner — fires ON_START/ON_STOP for whole-app foreground events.
    // Used by SyncCoordinator.syncOnResume() to mirror the desktop window-focus sync.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // Extended material icons (FavoriteBorder, List, etc. not in core set).
    implementation("androidx.compose.material:material-icons-extended")

    // Keystore-backed encrypted storage for the Supabase JWT + refresh token.
    // 1.0.0 is the stable release; works correctly on minSdk 23+ (our minSdk is 29).
    implementation("androidx.security:security-crypto:1.0.0")

    // libmpv — Findroid's prebuilt mpv for Android. Ships all 4 ABIs:
    // arm64-v8a, armeabi-v7a, x86, x86_64 (confirmed via unzip -l inspection).
    // 0.5.1 uses the classic static API (MPVLib.create/init/etc.) and has
    // minCompileSdk=1, compatible with our compileSdk=35.
    // 1.0.0 requires compileSdk 36 (AAR metadata enforcement) — skip for now.
    implementation("dev.jdtech.mpv:libmpv:0.5.1")

    // YouTube player for trailer embeds in the media detail sheet.
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")
}
