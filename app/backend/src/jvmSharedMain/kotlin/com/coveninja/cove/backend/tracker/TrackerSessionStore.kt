package com.coveninja.cove.backend.tracker

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.model.TrackerProvider

/**
 * One linked account, per profile and per tracker.
 *
 * [refreshToken] is Trakt's OAuth refresh token. Simkl issues none — its tokens do not
 * expire — so it stores its numeric account id there instead, which the stats path needs
 * and would otherwise have to re-fetch on every call. The column is documented as
 * provider-defined in `Cove.sq` for that reason.
 */
data class TrackerSession(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val username: String = "",
    val lastSyncAt: String = "",
)

/** `tracker_sessions` reads and writes, keyed by profile and provider. */
class TrackerSessionStore(
    private val database: CoveDatabase,
    private val provider: TrackerProvider,
) {
    fun read(profileId: String): TrackerSession? =
        database.coveQueries.selectTrackerSession(profileId, provider.key)
            .executeAsOneOrNull()
            ?.let {
                TrackerSession(
                    it.access_token,
                    it.refresh_token,
                    it.expires_at,
                    it.username,
                    it.last_sync_at,
                )
            }

    fun write(profileId: String, session: TrackerSession) {
        database.coveQueries.upsertTrackerSession(
            profileId,
            provider.key,
            session.accessToken,
            session.refreshToken,
            session.expiresAt,
            session.username,
            session.lastSyncAt,
        )
    }

    fun delete(profileId: String) {
        database.coveQueries.deleteTrackerSession(profileId, provider.key)
    }
}
