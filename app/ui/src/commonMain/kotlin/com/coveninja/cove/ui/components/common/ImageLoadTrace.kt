package com.coveninja.cove.ui.components.common

/** Async trace slices are visible in Macrobenchmark/Perfetto captures without logging URLs. */
internal expect object ImageLoadTrace {
    fun begin(model: Any?): Int
    fun end(cookie: Int, outcome: String)
}
