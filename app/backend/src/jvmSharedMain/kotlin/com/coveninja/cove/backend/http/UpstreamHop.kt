package com.coveninja.cove.backend.http

/**
 * One hop of a manually followed upstream redirect chain.
 *
 * The chain cannot be handed to Ktor's redirect plugin: every hop is re-validated against the
 * SSRF policy and loses its credentials when the authority changes. It also cannot end by
 * returning the final [io.ktor.client.statement.HttpResponse] to the caller, because a response
 * whose body is still on the wire only exists inside `HttpStatement.execute` — Ktor cancels it
 * as soon as that block returns. A hop therefore reports either where to go next, or the value
 * the caller's consumer produced from a response it was given while the body was still open.
 */
internal sealed interface UpstreamHop<out T> {
    /** The response redirected; [location] is its `Location` header, still unresolved. */
    data class Redirect(val location: String) : UpstreamHop<Nothing>

    /** The response was final and has already been consumed, yielding [value]. */
    data class Consumed<out T>(val value: T) : UpstreamHop<T>
}
