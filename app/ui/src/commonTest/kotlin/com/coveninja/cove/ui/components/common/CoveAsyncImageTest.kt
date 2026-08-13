package com.coveninja.cove.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoveAsyncImageTest {
    @Test
    fun `network images retry three times with backoff`() {
        assertEquals(300L, imageRetryDelayMillis(0))
        assertEquals(1_000L, imageRetryDelayMillis(1))
        assertEquals(3_000L, imageRetryDelayMillis(2))
        assertNull(imageRetryDelayMillis(3))
    }
}
