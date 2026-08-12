package com.coveninja.cove.backend.addons

import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android counterpart to the desktop policy for user-controlled network URLs. */
internal val AndroidAddonUrlPolicy = AddonUrlPolicy { raw ->
    BasicAddonUrlPolicy.validate(raw)
    withContext(Dispatchers.IO) {
        val uri = URI(raw)
        require(uri.rawUserInfo == null) { "addon URL must not contain credentials" }
        val host = uri.host ?: throw IllegalArgumentException("addon URL has no host")
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { throw IllegalArgumentException("addon host could not be resolved") }
        require(addresses.isNotEmpty() && addresses.none(InetAddress::isPrivateOrSpecial)) {
            "addon URL must resolve only to public addresses"
        }
    }
}

private fun InetAddress.isPrivateOrSpecial(): Boolean =
    isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress
