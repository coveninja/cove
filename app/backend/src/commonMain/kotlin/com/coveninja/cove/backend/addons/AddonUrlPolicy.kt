package com.coveninja.cove.backend.addons

import io.ktor.http.Url

fun interface AddonUrlPolicy {
    suspend fun validate(url: String)
}

/** Fast structural validation shared by every platform. Desktop adds DNS/IP checks. */
val BasicAddonUrlPolicy = AddonUrlPolicy { raw ->
    val url = runCatching { Url(raw) }
        .getOrElse { throw IllegalArgumentException("invalid addon URL") }
    require(url.protocol.name == "http" || url.protocol.name == "https") {
        "addon URL must use http or https"
    }
    val host = url.host.lowercase()
    require(host.isNotBlank() && host != "localhost" && !host.endsWith(".localhost")) {
        "addon URL must use a public host"
    }
}
