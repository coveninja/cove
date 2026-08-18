package com.coveninja.cove.backend

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The scope every piece of background work in the backend runs in.
 *
 * The important part is the handler, and it is worth being explicit about why a `SupervisorJob`
 * alone is not enough. A supervisor stops one failed child from cancelling its siblings — it
 * does nothing about the exception itself, which continues on to the thread's default uncaught
 * handler. On Android that handler kills the process.
 *
 * So every background failure was fatal. Not a class of exotic failures: any of them. The one
 * that found this was a television whose clock was wrong, which made certificate validation
 * fail, which killed the app on launch — but a phone on a captive portal, a set-top box
 * starting before its Wi-Fi associates, or a provider returning something unexpected would all
 * have done the same thing. A media app cannot treat "the network was not there yet" as fatal.
 *
 * Failures are printed rather than swallowed. Not crashing must not mean not knowing: the
 * states that matter to a viewer already surface as `Failed` in the UI, and this is the net
 * under everything else.
 */
fun backendScope(area: String): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + backendExceptionHandler(area))

/** Exposed for the scope's own test; not meant to be installed by hand. */
internal fun backendExceptionHandler(area: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        System.err.println("Cove $area: background work failed: $error")
        error.printStackTrace()
    }
