package com.coveninja.cove.backend.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentFailureTest {

    /** The shape the real failure arrives in: the useful sentence is several causes down. */
    private fun handshakeFailure(): Throwable = RuntimeException(
        "Chain validation failed",
        RuntimeException(
            "Chain validation failed",
            RuntimeException("Response is unreliable: its validity interval is out-of-date"),
        ),
    )

    // The message a wrong clock produces names TLS internals and not the thing the viewer can
    // fix. This is the whole point of the mapping.
    // Mutation applied to verify: dropped "validity interval" and "chain validation failed" from
    // CLOCK_MARKERS → test failed, the raw TLS string came back through.
    @Test
    fun `a certificate failure points at the clock`() {
        val message = describeContentFailure(handshakeFailure(), "fallback")

        assertTrue(message.contains("date and time"), message)
        assertTrue(!message.contains("Chain validation"), message)
    }

    // Buried two levels down, which is why the cause chain is walked rather than only the top
    // message being read.
    // Mutation applied to verify: read only error.message instead of walking causes → test
    // failed for the nested case.
    @Test
    fun `a cause several levels down is still found`() {
        val nested = RuntimeException(
            "request failed",
            RuntimeException("wrapper", RuntimeException("certificate expired")),
        )

        assertTrue(describeContentFailure(nested, "fallback").contains("date and time"))
    }

    @Test
    fun `an unreachable network says so instead of blaming certificates`() {
        val offline = RuntimeException("failed to connect to api.themoviedb.org: ENETUNREACH")

        val message = describeContentFailure(offline, "fallback")
        assertTrue(message.contains("network connection"), message)
    }

    @Test
    fun `a timeout is its own answer`() {
        assertTrue(
            describeContentFailure(RuntimeException("Read timed out"), "fallback")
                .contains("timed out"),
        )
    }

    // Anything unrecognised keeps its own words: inventing a friendlier sentence would throw
    // away the only detail worth having.
    // Mutation applied to verify: returned the fallback for every unmatched error → test failed.
    @Test
    fun `an unrecognised failure is passed through untouched`() {
        assertEquals(
            "TMDB said 401",
            describeContentFailure(RuntimeException("TMDB said 401"), "fallback"),
        )
    }

    @Test
    fun `an error with nothing to say falls back`() {
        assertEquals("fallback", describeContentFailure(RuntimeException(), "fallback"))
    }
}
