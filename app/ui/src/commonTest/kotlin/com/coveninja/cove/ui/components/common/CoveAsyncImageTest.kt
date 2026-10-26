package com.coveninja.cove.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoveAsyncImageTest {
    @Test
    fun `network images retry with backoff long enough to outlast a reconnecting vpn`() {
        assertEquals(300L, imageRetryDelayMillis(0))
        assertEquals(1_000L, imageRetryDelayMillis(1))
        assertEquals(3_000L, imageRetryDelayMillis(2))
        assertEquals(10_000L, imageRetryDelayMillis(3))
        assertEquals(30_000L, imageRetryDelayMillis(4))
        assertNull(imageRetryDelayMillis(5))
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
        assertTrue(imageFailureClassIsTransient("java.nio.channels.UnresolvedAddressException"))
        assertTrue(imageFailureClassIsTransient("java.net.NoRouteToHostException"))
        assertFalse(imageFailureClassIsTransient("coil3.decode.DecodeException"))
        assertFalse(imageFailureClassIsTransient("java.lang.IllegalStateException"))
    }
}
