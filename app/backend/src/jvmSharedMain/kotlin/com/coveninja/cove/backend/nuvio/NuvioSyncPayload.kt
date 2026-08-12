package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.auth.SyncPayload
import com.coveninja.cove.backend.auth.SyncSnapshot
import com.coveninja.cove.shared.network.CoveJson

/** Syncs the Nuvio repository and enabled-scraper state across supported hosts. */
internal class NuvioSyncPayload(private val nuvio: NuvioManager) : SyncPayload {
    override val kind: String = "nuvio"

    override suspend fun snapshot(): SyncSnapshot {
        val store = nuvio.snapshotForSync()
        return SyncSnapshot(
            json = CoveJson.encodeToString(NuvioStore.serializer(), store),
            updatedAt = store.updatedAt,
        )
    }

    override suspend fun merge(snapshot: SyncSnapshot) {
        nuvio.mergeFromRemote(snapshot.json, snapshot.updatedAt)
    }
}
