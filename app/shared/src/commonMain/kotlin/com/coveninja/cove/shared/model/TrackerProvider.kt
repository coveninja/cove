package com.coveninja.cove.shared.model

/**
 * The third-party watch trackers Cove can link to.
 *
 * [key] is load-bearing in three places at once: it is the `provider` column in
 * `tracker_sessions`, the path segment under `/api/v1` and the discriminator on
 * [TrackerStats]. Spelling it once here is what keeps a stored session, a route and a
 * stats section from drifting apart — renaming it silently unlinks every account.
 */
enum class TrackerProvider(val key: String, val label: String) {
    Trakt("trakt", "Trakt"),
    Simkl("simkl", "Simkl"),
    ;

    companion object {
        fun fromKey(key: String): TrackerProvider? = entries.firstOrNull { it.key == key }
    }
}
