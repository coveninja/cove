package com.coveninja.cove.ui.components.media.details
public enum class MediaSharedPart {
    Container,
    Poster,
}

public data class MediaSharedKey(
    val mediaId: String,
    val part: MediaSharedPart,
)
