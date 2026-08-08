package com.coveninja.cove.shared.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageUrlsTest {

    // Mutation applied to verify: changed `"$baseUrl/api/img/$size$path"` to
    // return `path` verbatim → test failed with "/abc.jpg" ≠ expected URL.
    @Test
    fun `raw TMDB path is wrapped exactly once with size and base url`() {
        val result = resolveImageUrl("http://127.0.0.1:6969", "/abc.jpg", "w500")
        assertEquals("http://127.0.0.1:6969/api/img/w500/abc.jpg", result)
    }

    // Mutation applied to verify: removed the `startsWith("http")` guard →
    // test failed because the result was double-wrapped.
    @Test
    fun `already-absolute proxied url passes through verbatim`() {
        val input = "http://127.0.0.1:6969/api/img/w500/abc.jpg"
        val result = resolveImageUrl("http://127.0.0.1:6969", input, "w500")
        assertEquals(input, result)
    }

    // Mutation applied to verify: removed `path ?: return null` early-return →
    // NPE / wrong-type failure made the test fail.
    @Test
    fun `null path returns null`() {
        assertNull(resolveImageUrl("http://127.0.0.1:6969", null))
    }

    // Verifies the size segment is substituted correctly for non-default sizes.
    // Mutation applied to verify: hardcoded "w500" instead of using the size
    // param → test failed when called with "original".
    @Test
    fun `size segment is used verbatim in the proxy url`() {
        val result = resolveImageUrl("http://127.0.0.1:6969", "/logo.png", "original")
        assertEquals("http://127.0.0.1:6969/api/img/original/logo.png", result)
    }
}
