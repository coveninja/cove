package com.coveninja.cove.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoveAsyncImageTest {
    @Test
    fun `network images retry three times with backoff`() {
        assertEquals(300L, imageRetryDelayMillis(0))
        assertEquals(1_000L, imageRetryDelayMillis(1))
        assertEquals(3_000L, imageRetryDelayMillis(2))
        assertNull(imageRetryDelayMillis(3))
    }

    @Test
    fun `only retryable HTTP statuses are transient`() {
        assertTrue(imageHttpStatusIsTransient(408))
        assertTrue(imageHttpStatusIsTransient(429))
        assertTrue(imageHttpStatusIsTransient(503))
        assertFalse(imageHttpStatusIsTransient(400))
        assertFalse(imageHttpStatusIsTransient(404))
    }

    @Test
    fun `transport class names retry while decoding failures do not`() {
        assertTrue(imageFailureClassIsTransient("java.net.SocketTimeoutException"))
        assertTrue(imageFailureClassIsTransient("okio.IOException"))
        assertFalse(imageFailureClassIsTransient("coil3.decode.DecodeException"))
        assertFalse(imageFailureClassIsTransient("java.lang.IllegalStateException"))
    }
}
