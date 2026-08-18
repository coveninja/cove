package com.coveninja.cove.backend.torrent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePreloadsTest {
    @Test
    fun `glibc preloads run only on Linux`() {
        assertTrue(needsLinuxNativePreloads("Linux"))
        assertFalse(needsLinuxNativePreloads("Mac OS X"))
        assertFalse(needsLinuxNativePreloads("Windows 11"))
    }
}
