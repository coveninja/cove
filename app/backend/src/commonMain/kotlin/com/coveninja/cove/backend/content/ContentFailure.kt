package com.coveninja.cove.backend.content

/**
 * Turns a network failure into something a viewer can act on.
 *
 * The raw messages here come from TLS and socket internals — "Chain validation failed" is the
 * one that prompted this — and they name the symptom rather than the cause. The cause is very
 * often a clock: certificate validity and OCSP responses are both checked against device time,
 * so a television or an emulator whose date is wrong cannot verify any certificate at all and
 * every image, every search and every stream fails at once. That is unguessable from the text,
 * and it is a thing the viewer can actually fix.
 *
 * Matched on the message rather than on exception types on purpose: this is `commonMain`, the
 * JVM's SSL classes are not visible here, and the same wording reaches us through Ktor from
 * more than one engine.
 */
internal fun describeContentFailure(error: Throwable, fallback: String): String {
    val text = buildString {
        append(error.message.orEmpty())
        var cause = error.cause
        var depth = 0
        // The useful sentence is usually on a cause several levels down: a handshake failure
        // wrapping a chain failure wrapping the validity-interval complaint.
        while (cause != null && depth < CAUSE_DEPTH) {
            append(' ')
            append(cause.message.orEmpty())
            cause = cause.cause
            depth++
        }
    }

    return when {
        text.containsAnyIgnoringCase(CLOCK_MARKERS) ->
            "Could not verify a secure connection. Check this device's date and time — a clock " +
                "that is wrong makes valid certificates look expired."

        text.containsAnyIgnoringCase(OFFLINE_MARKERS) ->
            "Could not reach the internet. Check this device's network connection."

        text.containsAnyIgnoringCase(TIMEOUT_MARKERS) ->
            "The connection timed out. The network may be slow or blocking the request."

        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private fun String.containsAnyIgnoringCase(markers: List<String>): Boolean =
    markers.any { marker -> contains(marker, ignoreCase = true) }

/**
 * Deliberately includes the generic chain failure. A chain that will not validate has other
 * possible causes — a proxy, a stripped trust store — but on a device the clock is far and away
 * the likeliest, and it is the only one of them the viewer can do anything about.
 */
private val CLOCK_MARKERS = listOf(
    "validity interval",
    "certificate expired",
    "certpathvalidator",
    "chain validation failed",
    "not yet valid",
)

private val OFFLINE_MARKERS = listOf(
    "unable to resolve host",
    "network is unreachable",
    "enetunreach",
    "failed to connect",
    "no address associated",
)

private val TIMEOUT_MARKERS = listOf("timeout", "timed out")

private const val CAUSE_DEPTH = 6
