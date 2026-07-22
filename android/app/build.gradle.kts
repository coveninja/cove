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
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.coveninja.cove"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coveninja.cove"
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CI passes the release tag via COVE_VERSION (e.g. "v1.2.3"); local
        // builds fall back to the dev placeholder. versionCode is derived as
        // major*10000 + minor*100 + patch so Play/package-manager upgrade
        // ordering follows semver.
        val coveVersion = System.getenv("COVE_VERSION")?.removePrefix("v")
        versionName = coveVersion ?: "0.1.0"
        versionCode = coveVersion?.split(".")
            ?.take(3)
            ?.map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            ?.let { (maj, min, pat) -> maj * 10000 + min * 100 + pat }
            ?.takeIf { it > 0 }
            ?: 1

        // Expose TMDB_API_KEY as a BuildConfig constant so CoveApplication can
        // pass it to the Go backend. Defaults to empty string so builds without
        // a local.properties still compile (TMDB calls will fail at runtime, as
        // they do on desktop without a .env file).
        val tmdbApiKey = localProps.getProperty("TMDB_API_KEY", "")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        val backendUrl = localProps.getProperty("BACKEND_URL", "http://127.0.0.1:6969/api")
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        val webUrl = localProps.getProperty("WEB_URL", "http://127.0.0.1:6969")
        buildConfigField("String", "WEB_URL", "\"$webUrl\"")
        // Supabase auth — publishable anon key only; service key must never enter BuildConfig.
        val supabaseUrl = localProps.getProperty("SUPABASE_URL", "")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        val supabaseAnonKey = localProps.getProperty("SUPABASE_ANON_KEY", "")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        // Trakt API credentials — used by the Go backend for watch-history sync.
        // Defaults to empty string so builds without local.properties still compile.
        val traktClientID = localProps.getProperty("TRAKT_CLIENT_ID", "")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientID\"")
        val traktClientSecret = localProps.getProperty("TRAKT_CLIENT_SECRET", "")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktClientSecret\"")
        // UPDATE_BASE_URL overrides the GitHub API endpoint for local end-to-end
        // update testing (e.g. "http://10.0.2.2:8000" pointing at python -m http.server).
        // Empty string (the default) means use the real api.github.com endpoint.
        val updateBaseUrl = localProps.getProperty("UPDATE_BASE_URL", "")
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
    }

    // Release signing — CI-only. The keystore never lives in the repo: the
    // workflow decodes the ANDROID_KEYSTORE_BASE64 secret to a temp file and
    // points ANDROID_KEYSTORE_FILE at it. Local builds (env unset) keep the
    // default debug signing so `gradlew assembleRelease` still works.
    val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    if (releaseKeystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // AGP 8 defaults buildConfig generation to false; we use BuildConfig for
        // TMDB_API_KEY and VERSION_NAME so this must be explicitly enabled.
        buildConfig = true
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

    // ComponentActivity (base for WebViewActivity) + onBackPressedDispatcher.addCallback
    // extension. Previously pulled in transitively by activity-compose.
    implementation("androidx.activity:activity-ktx:1.9.3")

    // lifecycleScope extension on LifecycleOwner. Previously pulled in
    // transitively by lifecycle-runtime-compose.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // OkHttp — health-check polls to /api/ping from WebViewActivity and
    // provides HTTP for CoveApiClient (sync, auth).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // kotlinx-serialization JSON — parsing backend responses in CoveApiClient
    // and SyncCoordinator.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ProcessLifecycleOwner — fires ON_START/ON_STOP for whole-app foreground events.
    // Used by SyncCoordinator.syncOnResume() to mirror the desktop window-focus sync.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Keystore-backed encrypted storage for the Supabase JWT + refresh token.
    // 1.0.0 is the stable release; works correctly on minSdk 23+ (our minSdk is 28).
    implementation("androidx.security:security-crypto:1.0.0")

    // libmpv — Findroid's prebuilt mpv for Android. Ships all 4 ABIs:
    // arm64-v8a, armeabi-v7a, x86, x86_64 (confirmed via unzip -l inspection).
    // 0.5.1 uses the classic static API (MPVLib.create/init/etc.) and has
    // minCompileSdk=1, compatible with our compileSdk=35.
    implementation("dev.jdtech.mpv:libmpv:0.5.1")

    // MediaSessionCompat — lock-screen / headset transport controls wired in
    // WebViewActivity for audio-focus and lock-screen media controls.
    implementation("androidx.media:media:1.7.0")

    // WebKit extensions — required for WebViewCompat / addDocumentStartJavaScript
    // in MpvBridge and WebViewActivity.
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
