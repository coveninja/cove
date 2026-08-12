package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TvEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path")    val stillPath: String? = null,
    @SerialName("air_date")      val airDate: String? = null,
    val runtime: Int = 0,
)
