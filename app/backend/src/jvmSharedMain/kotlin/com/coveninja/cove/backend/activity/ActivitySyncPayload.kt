package com.coveninja.cove.backend.activity

import com.coveninja.cove.backend.auth.SyncPayload
import com.coveninja.cove.backend.auth.SyncSnapshot

/**
 * Desktop-only: [ActivityService] buckets watch time with java.time, so it does
 * not exist on Android and that host passes the blob through untouched.
 *
 * The snapshot carries no timestamp of its own — the activity store is rewritten
 * as a whole on every merge — so the push stamps it with the current time.
 */
internal class ActivitySyncPayload(private val activity: ActivityService) : SyncPayload {
    override val kind: String = "activity"

    override suspend fun snapshot(): SyncSnapshot = SyncSnapshot(activity.snapshotJson(), "")

    override suspend fun merge(snapshot: SyncSnapshot) = activity.mergeFromJson(snapshot.json)
}
