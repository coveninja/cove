package com.coveninja.cove.backend.addons

import java.net.InetAddress
import java.net.URI

val DesktopAddonUrlPolicy = AddonUrlPolicy { raw ->
    BasicAddonUrlPolicy.validate(raw)
    validateResolvedPublicUrl(raw)
}

internal fun validateResolvedPublicUrl(raw: String) {
    val uri = URI(raw)
    require(uri.rawUserInfo == null) { "addon URL must not contain credentials" }
    val host = uri.host ?: throw IllegalArgumentException("addon URL has no host")
    val addresses = runCatching { InetAddress.getAllByName(host).toList() }
        .getOrElse { throw IllegalArgumentException("addon host could not be resolved") }
    require(addresses.isNotEmpty() && addresses.none(InetAddress::isPrivateOrSpecial)) {
        "addon URL must resolve only to public addresses"
    }
}

private fun InetAddress.isPrivateOrSpecial(): Boolean =
    isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress
