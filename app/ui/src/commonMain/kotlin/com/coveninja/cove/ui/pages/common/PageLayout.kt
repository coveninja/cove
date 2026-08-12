package com.coveninja.cove.ui.pages.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Host-controlled gutter shared by page headers, rails, toolbars, and result grids. */
internal val LocalPageHorizontalPadding = staticCompositionLocalOf { 24.dp }

object PageLayoutDefaults {
    val HorizontalPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = LocalPageHorizontalPadding.current
}
