package com.coveninja.cove.backend.addons

import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private val hostResolver = AddonHostResolver()

val DesktopAddonUrlPolicy = AddonUrlPolicy { raw ->
    BasicAddonUrlPolicy.validate(raw)
    validateResolvedPublicUrl(raw, hostResolver::resolve)
}

/** The plugin and scraper brokers use a synchronous callback on their worker threads. */
internal fun validateResolvedPublicUrl(raw: String) = runBlocking {
    validateResolvedPublicUrl(raw, hostResolver::resolve)
}

internal suspend fun validateResolvedPublicUrl(
    raw: String,
    resolve: suspend (String) -> List<InetAddress>,
) {
    val uri = URI(raw)
    require(uri.rawUserInfo == null) { "addon URL must not contain credentials" }
    val host = uri.host ?: throw IllegalArgumentException("addon URL has no host")
    val addresses = try {
        resolve(host)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IllegalArgumentException("addon host could not be resolved")
    }
    require(addresses.isNotEmpty() && addresses.none(InetAddress::isPrivateOrSpecial)) {
        "addon URL must resolve only to public addresses"
    }
}

private fun InetAddress.isPrivateOrSpecial(): Boolean =
    isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress
