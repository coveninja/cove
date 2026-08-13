package com.coveninja.cove.ui.components.common

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

internal actual object ImageLoadTrace {
    private val nextCookie = AtomicInteger(1)

    actual fun begin(model: Any?): Int {
        val cookie = nextCookie.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("Cove image load", cookie)
        }
        return cookie
    }

    actual fun end(cookie: Int, outcome: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection("Cove image load", cookie)
            Trace.beginSection("Cove image $outcome")
            Trace.endSection()
        }
    }
}
