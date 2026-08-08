package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType(val wireName: String) {
    @SerialName("movie") Movie("movie"),
    @SerialName("tv")    Tv("tv"),
}
