package com.coveninja.cove.backend.tracker

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A device/PIN code as the UI needs it.
 *
 * [deviceCode] and [userCode] are separate because the two trackers poll with different
 * halves: Trakt posts the device code back, Simkl asks about the user code the viewer can
 * see. Whichever it is, the polling closure gets the whole object rather than a string, so
 * neither service has to smuggle one through the other's field.
 */
@Serializable
data class TrackerDeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

/** What one poll of the pending link said. */
sealed interface LinkPoll {
    data object Authorized : LinkPoll
    data object Pending : LinkPoll
    data object SlowDown : LinkPoll
    data object Denied : LinkPoll

    /** The code is not one this tracker recognises — terminal, like [Denied]. */
    data object Invalid : LinkPoll

    data object Expired : LinkPoll
}

/**
 * The polling half of a device flow, per profile.
 *
 * It lives beside the service rather than in the repository so that closing the settings
 * screen does not abandon a link the viewer is halfway through approving on their phone:
 * the job outlives the UI, and the repository only watches [state]. One job per profile,
 * because a profile switch mid-flow must not have two loops writing the same slot.
 */
class DeviceLinkCoordinator(
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val states = ConcurrentHashMap<String, String>()

    fun state(profileId: String): String = states[profileId] ?: IDLE

    fun reset(profileId: String) {
        jobs.remove(profileId)?.cancel()
        states[profileId] = IDLE
    }

    fun start(profileId: String, code: TrackerDeviceCode, poll: suspend () -> LinkPoll) {
        jobs.remove(profileId)?.cancel()
        states[profileId] = PENDING
        jobs[profileId] = scope.launch {
            val deadline = clock.millis() + code.expiresIn * 1_000L
            var interval = (code.interval.coerceAtLeast(1) + 1) * 1_000L
            while (isActive && clock.millis() < deadline) {
                delay(interval)
                when (runCatching { poll() }.getOrNull()) {
                    LinkPoll.Authorized -> return@launch
                    LinkPoll.Expired -> {
                        states[profileId] = EXPIRED
                        return@launch
                    }
                    LinkPoll.Denied, LinkPoll.Invalid -> {
                        states[profileId] = DENIED
                        return@launch
                    }
                    LinkPoll.SlowDown -> interval = (interval + 5_000).coerceAtMost(30_000)
                    LinkPoll.Pending, null -> Unit
                }
            }
            if (isActive) states[profileId] = EXPIRED
        }
    }

    /**
     * Records the success. The poll that authorises usually runs *inside* the job being
     * cancelled here, so cancelling it unconditionally would kill the coroutine before it
     * could return its own result.
     */
    suspend fun authorized(profileId: String) {
        states[profileId] = AUTHORIZED
        jobs.remove(profileId)
            ?.takeIf { it != currentCoroutineContext()[Job] }
            ?.cancel()
    }

    companion object {
        const val IDLE = "idle"
        const val PENDING = "pending"
        const val AUTHORIZED = "authorized"
        const val DENIED = "denied"
        const val EXPIRED = "expired"
    }
}
