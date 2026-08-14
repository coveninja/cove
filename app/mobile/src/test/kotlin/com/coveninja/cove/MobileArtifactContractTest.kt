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

    @Test
    fun `phone artifact requires touch and never advertises a TV launcher`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.hardware.touchscreen"))
        assertTrue(manifest.contains("android:required=\"true\""))
        assertFalse(manifest.contains("android.intent.category.LEANBACK_LAUNCHER"))
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
