package com.coveninja.cove.ui.components.media

import androidx.compose.ui.graphics.Color

enum class MyListCategory(
    val label: String,
    val icon: String,
    val accentColor: Color,
) {
    Watching(
        label = "Watching",
        icon = "lucide:play",
        accentColor = Color(0xFF0E94BD),
    ),
    WatchLater(
        label = "Watch Later",
        icon = "lucide:clock",
        accentColor = Color(0xFFE5851E),
    ),
    Finished(
        label = "Finished",
        icon = "lucide:check",
        accentColor = Color(0xFF2DAA24),
    ),
    Dropped(
        label = "Dropped",
        icon = "lucide:x",
        accentColor = Color(0xFFC11A16),
    ),
    NotInterested(
        label = "Not Interested",
        icon = "lucide:trash",
        accentColor = Color(0xFF959595),
    ),
}
