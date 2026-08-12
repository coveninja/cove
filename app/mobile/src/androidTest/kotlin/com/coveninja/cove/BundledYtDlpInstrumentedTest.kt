package com.coveninja.cove

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledYtDlpInstrumentedTest {
    @Test
    fun bundledRuntimeInitializesWithoutDownloadingAHelper() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as CoveMobileApplication

        assertNull(application.playerHost().prepareWebVideo(mayInstallHelper = false))
    }
}
