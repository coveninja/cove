package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.data.TrackerState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.TrackerProvider
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What a tracker's mark is showing.
 *
 * [Waiting] is its own tone rather than a shade of [Off] because a half-finished link is
 * the one state that needs the viewer to go and do something — on another device, before a
 * deadline. Nothing else on this page is waiting on a person.
 */
internal enum class TrackerTone { Linked, Waiting, Off, Attention }

internal fun trackerTone(state: TrackerState): TrackerTone = when (state) {
    is TrackerState.Linked -> if (state.syncError != null) TrackerTone.Attention else TrackerTone.Linked
    is TrackerState.Pending -> TrackerTone.Waiting
    is TrackerState.Unlinked -> if (state.error != null) TrackerTone.Attention else TrackerTone.Off
    is TrackerState.Unconfigured -> TrackerTone.Off
    TrackerState.Loading -> TrackerTone.Off
}

/**
 * The state, as a word or two for the pill beside the provider's name.
 *
 * The provider's own name is never in here: it stays on the card whatever happens, so that
 * a page of trackers can be scanned by name and state rather than read. Replacing the name
 * with a username the moment an account connects is how the card stops being identifiable
 * at exactly the point it has something to say.
 */
internal fun trackerStatusLabel(state: TrackerState): String = when (state) {
    TrackerState.Loading -> "Checking"
    is TrackerState.Unconfigured -> "Unavailable"
    is TrackerState.Unlinked -> if (state.error != null) "Needs attention" else "Not connected"
    is TrackerState.Pending -> "Waiting for you"
    is TrackerState.Linked -> when {
        state.syncError != null -> "Needs attention"
        state.syncing -> "Syncing"
        else -> "Connected"
    }
}

/**
 * The supporting line.
 *
 * Errors are passed through verbatim — they come from the tracker and say which call
 * failed, and inventing a friendlier sentence would throw away the only detail worth
 * having. The connected line leads with what the switches below actually do, because that
 * is the question someone opening this card is about to ask.
 */
internal fun trackerDetail(
    state: TrackerState,
    provider: TrackerProvider,
    now: Instant,
): String = when (state) {
    TrackerState.Loading -> "Reading this device's ${provider.label} authorization."
    is TrackerState.Unconfigured -> state.reason
    is TrackerState.Unlinked -> state.error
        ?: "Connect to scrobble what you watch and keep your list in step."
    is TrackerState.Pending -> "Approve Cove on ${provider.label} to finish."
    is TrackerState.Linked -> when {
        state.syncError != null -> state.syncError!!
        state.syncing -> "Reconciling your list and watch history."
        state.lastSyncAt == null ->
            "Connected as ${state.username}. Nothing reconciled from this device yet."
        else -> "Connected as ${state.username}. Last synced ${relativeTime(state.lastSyncAt!!, now)}."
    }
}

/**
 * A pending code split for transcription.
 *
 * Codes are read off one screen and typed into another, and an unbroken run of characters
 * is where that goes wrong — the eye loses its place and re-reads the same character. Both
 * trackers issue short codes, so pairs are enough; a code that already carries its own
 * separator is left exactly as the tracker sent it.
 */
internal fun groupUserCode(code: String): List<String> {
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.any { !it.isLetterOrDigit() }) return listOf(trimmed)
    return trimmed.chunked(2)
}

/**
 * How long the code has left, or null once there is nothing useful to say.
 *
 * Rounds *up* to the next whole unit, which is the opposite of [relativeTime] and
 * deliberate: an elapsed time reads better rounded down ("3 minutes ago"), but a deadline
 * rounded down shows 0 while there is still time on it.
 */
internal fun codeCountdown(expiresAt: Instant?, now: Instant): String? {
    val left: Duration = (expiresAt ?: return null) - now
    if (left.isNegative() || left == Duration.ZERO) return "Expired"
    val seconds = left.inWholeSeconds
    if (seconds < 60) return "${seconds}s left"
    val minutes = (seconds + 59) / 60
    return "${minutes}m left"
}

/** Under a minute the countdown stops being context and starts being a warning. */
internal fun codeExpiringSoon(expiresAt: Instant?, now: Instant): Boolean {
    val left = (expiresAt ?: return false) - now
    return left.inWholeSeconds < 60
}

/**
 * What Disconnect actually does, where the two trackers differ.
 *
 * Trakt revokes the token at its end. Simkl publishes no revoke endpoint at all, so the most
 * Cove can do is forget the token locally — the authorization stays live on the Simkl account
 * until it is removed there. Saying so is the difference between somebody who knows to finish
 * the job on simkl.com and somebody who believes they have signed out and has not.
 *
 * Null where there is nothing to add, rather than a reassuring sentence: a note under every
 * button is a note nobody reads by the time it matters.
 */
internal fun unlinkNote(provider: TrackerProvider): String? = when (provider) {
    TrackerProvider.Trakt -> null
    TrackerProvider.Simkl -> "Disconnecting forgets Simkl on this device. Simkl offers no way " +
        "to revoke it remotely, so remove Cove in your Simkl account settings to finish."
}

/**
 * Which settings field each tracker's switches read and write.
 *
 * Spelled out per provider rather than derived, because the fields are typed: [AppSettings]
 * is replaced whole on every write, so a scrobble flag has to be a real property that
 * `copy()` can name rather than an entry in a map. The cost is four `when` branches that
 * would go unnoticed if two of them were swapped — one tracker's switch quietly driving the
 * other's — which is what `TrackerModelTest` pins down.
 */
internal fun AppSettings.scrobbleEnabled(provider: TrackerProvider): Boolean = when (provider) {
    TrackerProvider.Trakt -> traktScrobbleEnabled
    TrackerProvider.Simkl -> simklScrobbleEnabled
}

internal fun AppSettings.withScrobbleEnabled(
    provider: TrackerProvider,
    enabled: Boolean,
): AppSettings = when (provider) {
    TrackerProvider.Trakt -> copy(traktScrobbleEnabled = enabled)
    TrackerProvider.Simkl -> copy(simklScrobbleEnabled = enabled)
}

internal fun AppSettings.syncEnabled(provider: TrackerProvider): Boolean = when (provider) {
    TrackerProvider.Trakt -> traktSyncEnabled
    TrackerProvider.Simkl -> simklSyncEnabled
}

internal fun AppSettings.withSyncEnabled(
    provider: TrackerProvider,
    enabled: Boolean,
): AppSettings = when (provider) {
    TrackerProvider.Trakt -> copy(traktSyncEnabled = enabled)
    TrackerProvider.Simkl -> copy(simklSyncEnabled = enabled)
}
