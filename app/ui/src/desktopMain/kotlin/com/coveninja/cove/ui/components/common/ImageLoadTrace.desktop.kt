package com.coveninja.cove.ui.components.common

internal actual object ImageLoadTrace {
    actual fun begin(model: Any?): Int = 0
    actual fun end(cookie: Int, outcome: String) = Unit
}
