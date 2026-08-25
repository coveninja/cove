package com.coveninja.cove

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MobileArtifactContractTest {
    @Test
    fun `mobile artifact keeps the upgrade-compatible package identity`() {
        assertEquals("com.coveninja.cove", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `deployment values cannot inject additional build config lines`() {
        listOf(
            BuildConfig.TMDB_API_KEY,
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            BuildConfig.TRAKT_CLIENT_ID,
            BuildConfig.TRAKT_CLIENT_SECRET,
            BuildConfig.UPDATE_PUBLIC_KEYS,
            BuildConfig.UPDATE_API_BASE,
        ).forEach { value -> assertFalse(value.contains('\n')) }
    }

    // This artifact is installed by phones and televisions alike, and the two conditions below
    // are what make that possible: a required touchscreen hides it from every television, and a
    // missing leanback launcher category leaves it installed but unopenable on one — the app is
    // simply absent from the launcher. Requiring leanback would cost every phone instead.
    @Test
    fun `one artifact stays installable and launchable on both phones and televisions`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        val touchscreen = manifest
            .substringAfter("android.hardware.touchscreen")
            .substringBefore("/>")
        val leanbackFeature = manifest
            .substringAfter("android.software.leanback")
            .substringBefore("/>")
        assertTrue(touchscreen.contains("android:required=\"false\""), "touch must not be required")
        assertTrue(
            leanbackFeature.contains("android:required=\"false\""),
            "leanback must not be required",
        )
        assertTrue(manifest.contains("android.intent.category.LEANBACK_LAUNCHER"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }

    // Every surface that shows a mark shows the same one, all generated from
    // packaging/icons/cove.svg. The notification icon is the exception that proves it: Android
    // draws those as a single-colour mask, so the full-colour logo would come out as a filled
    // blob and it gets its own silhouette instead.
    @Test
    fun `the launcher wears Cove's own mark on every host`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val services = File("src/main/kotlin/com/coveninja/cove/MobileServices.kt").readText()

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        listOf(
            "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
            "src/main/res/drawable/ic_cove_foreground.xml",
            "src/main/res/drawable/ic_cove_notification.xml",
        ).forEach { path -> assertTrue(File(path).isFile, "missing $path") }

        // A gradient in the status bar is a white blob; the mask has to be its own drawable.
        assertTrue(services.contains("R.drawable.ic_cove_notification"))
        assertFalse(services.contains("setSmallIcon(R.drawable.ic_cove)"))
    }

    // A television launcher shows android:banner, not android:icon, and an app without one is
    // drawn as a blank tile rather than falling back to the icon.
    @Test
    fun `the television launcher has a banner to draw`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:banner=\"@drawable/tv_banner\""))
        assertTrue(File("src/main/res/drawable/tv_banner.xml").isFile)
    }

    // The shell is chosen from the device, not the build. If this detection is ever dropped, a
    // television silently gets the touch UI — which looks correct and is entirely unusable,
    // because none of its affordances can be reached without a pointer.
    @Test
    fun `the activity picks its shell from the device`() {
        val activity = File("src/main/kotlin/com/coveninja/cove/MainActivity.kt").readText()

        assertTrue(activity.contains("FEATURE_LEANBACK"))
        assertTrue(activity.contains("UI_MODE_TYPE_TELEVISION"))
        assertTrue(activity.contains("CoveTvApp("))
    }

    @Test
    fun `mobile playback permits cleartext only through its loopback boundary`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val policy = File("src/main/res/xml/network_security_config.xml").readText()

        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(policy.contains("<base-config cleartextTrafficPermitted=\"false\""))
        assertTrue(policy.contains(">127.0.0.1</domain>"))
        assertTrue(policy.contains(">localhost</domain>"))
    }

    @Test
    fun `mobile updater keeps the package install permission and result receivers`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains(".backend.updater.AndroidPackageInstallerReceiver"))
        assertTrue(manifest.contains(".backend.updater.AndroidPostUpdateReceiver"))
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        val installerReceiver = manifest
            .substringAfter(".backend.updater.AndroidPackageInstallerReceiver")
            .substringBefore("/>")
        val replacementReceiver = manifest
            .substringAfter(".backend.updater.AndroidPostUpdateReceiver")
            .substringBefore("</receiver>")
        assertTrue(installerReceiver.contains("android:exported=\"false\""))
        assertTrue(replacementReceiver.contains("android:exported=\"false\""))
    }

    @Test
    fun `mobile artifact packages libmpv and every supported torrent abi`() {
        val mobileBuild = File("build.gradle.kts").readText()
        val backendBuild = File("../backend/build.gradle.kts").readText()

        assertTrue(mobileBuild.contains("implementation(libs.mpv.android)"))
        listOf("arm", "arm64", "x86", "x86_64").forEach { abi ->
            assertTrue(
                backendBuild.contains("jlibtorrent-android-$abi"),
                "missing jlibtorrent native dependency for $abi",
            )
        }
    }
}
