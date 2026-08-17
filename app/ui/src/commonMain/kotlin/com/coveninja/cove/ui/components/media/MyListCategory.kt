package com.coveninja.cove.ui.components.media

import androidx.compose.ui.graphics.Color
import com.coveninja.cove.ui.CoveColors

enum class MyListCategory(
    val label: String,
    val icon: String,
    val accentColor: Color,
) {
    Watching(
        label = "Watching",
        icon = "lucide:play",
        accentColor = CoveColors.Category.Watching,
    ),
    WatchLater(
        label = "Watch Later",
        icon = "lucide:clock",
        accentColor = CoveColors.Category.WatchLater,
    ),
    Finished(
        label = "Finished",
        icon = "lucide:check",
        accentColor = CoveColors.Category.Finished,
    ),
    Dropped(
        label = "Dropped",
        icon = "lucide:x",
        accentColor = CoveColors.Category.Dropped,
    ),
    NotInterested(
        label = "Not Interested",
        icon = "lucide:trash",
        accentColor = CoveColors.Category.NotInterested,
    ),
}
