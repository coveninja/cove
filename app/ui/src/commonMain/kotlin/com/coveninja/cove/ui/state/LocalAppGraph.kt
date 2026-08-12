package com.coveninja.cove.ui.state

import androidx.compose.runtime.staticCompositionLocalOf
import com.coveninja.cove.shared.data.AppGraph

// staticCompositionLocalOf is correct here: the graph is created once per
// CoveApp invocation and never swapped out during the composition's lifetime.
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("No AppGraph provided — wrap content in CompositionLocalProvider(LocalAppGraph provides graph)")
}
