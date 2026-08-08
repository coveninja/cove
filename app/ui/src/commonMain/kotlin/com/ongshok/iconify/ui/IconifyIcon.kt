package com.ongshok.iconify.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Small source-compatible replacement for the former runtime Iconify client.
 * Cove only uses a bounded icon vocabulary, so mapping it to bundled vectors
 * avoids network fallback, a Java-21-only desktop dependency, and an Android
 * artifact that requires a newer compile SDK than the app itself.
 */
@Composable
fun IconifyIcon(
    icon: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Icon(
        imageVector = coveIcon(icon),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}

private fun coveIcon(icon: String): ImageVector = when (icon.substringAfter(':')) {
    "home" -> Icons.Filled.Home
    "bookmark", "bookmark-check" -> Icons.Filled.Bookmark
    "bookmark-plus" -> Icons.Filled.AddCircle
    "discover" -> Icons.Filled.Explore
    "search" -> Icons.Filled.Search
    "profile-circle" -> Icons.Filled.AccountCircle
    "arrow-left-1" -> Icons.AutoMirrored.Filled.ArrowBack
    "close", "x" -> Icons.Filled.Close
    "history", "clock", "clock-3" -> Icons.Filled.History
    "star", "star-fill" -> Icons.Filled.Star
    "align-left" -> Icons.AutoMirrored.Filled.Subject
    "badge-check", "shield-check" -> Icons.Filled.CheckCircle
    "building-2" -> Icons.Filled.Business
    "check" -> Icons.Filled.Check
    "chevron-down" -> Icons.Filled.KeyboardArrowDown
    "clapperboard", "film" -> Icons.Filled.Movie
    "eye-off", "filter-x" -> Icons.Filled.VisibilityOff
    "globe-2" -> Icons.Filled.Public
    "languages" -> Icons.Filled.Language
    "layout-grid", "library" -> Icons.Filled.Apps
    "message-circle" -> Icons.Filled.ChatBubbleOutline
    "pen-line" -> Icons.Filled.Edit
    "play", "play-circle" -> Icons.Filled.PlayArrow
    "sparkles" -> Icons.Filled.AutoAwesome
    "trash" -> Icons.Filled.Delete
    "triangle-alert" -> Icons.Filled.Warning
    "users" -> Icons.Filled.Group
    "circle-dot", "file-question", "info" -> Icons.Filled.Info
    else -> Icons.Filled.Info
}
