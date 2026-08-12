package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.auth.SyncPayload
import com.coveninja.cove.backend.auth.SyncSnapshot
import com.coveninja.cove.shared.network.CoveJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Lets the addon store take part in Supabase sync without [AddonManager] being
 * visible to the sync service. Hosts that run no addon manager simply do not
 * supply this, and their addon blob is round-tripped untouched.
 *
 * The wire shape is a bare JSON array of entries, which is what the
 * `profile_addons.data` column has always held.
 */
class AddonSyncPayload(
    private val addons: AddonManager,
    private val onMerged: suspend () -> Unit = {},
) : SyncPayload {
    override val kind: String = "addons"

    override suspend fun snapshot(): SyncSnapshot {
        val snapshot = addons.snapshotForSync()
        return SyncSnapshot(
            json = CoveJson.encodeToString(ListSerializer(AddonEntry.serializer()), snapshot.entries),
            updatedAt = snapshot.updatedAt,
        )
    }

    override suspend fun merge(snapshot: SyncSnapshot) {
        val entries = CoveJson.decodeFromString(
            ListSerializer(AddonEntry.serializer()),
            snapshot.json,
        )
        addons.mergeFromRemote(entries, snapshot.updatedAt)
        onMerged()
    }
}
