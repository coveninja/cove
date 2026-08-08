package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val name: String,
    @SerialName("is_primary")    val isPrimary: Boolean = false,
    @SerialName("supabase_uid")  val supabaseUid: String? = null,
)
