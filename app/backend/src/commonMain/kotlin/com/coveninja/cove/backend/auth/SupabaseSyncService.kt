package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.LibrarySyncSnapshot
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.backend.store.SyncDismissal
import com.coveninja.cove.backend.store.SyncRemoval
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class SyncResult(
    val libraryGeneration: Long,
    val pushError: String = "",
)

/**
 * Cross-device synchronization using only the publishable key and the user's
 * access token. All writes therefore remain subject to Supabase RLS.
 *
 * Everything beyond the library, settings and profile row travels as an opaque
 * per-profile JSON blob in `profile_<kind>`. A host that owns the subsystem
 * supplies a [SyncPayload] and gets a typed merge; a host that does not — Android
 * runs no addon manager and no Nuvio sandbox — round-trips the blob through
 * `legacy_payloads` untouched. That passthrough is what keeps a phone from
 * pushing an empty addon list over the desktop's configured addons.
 */
class SupabaseSyncService(
    private val client: SupabaseClient,
    private val database: CoveDatabase,
    private val profiles: LocalProfileRepository,
    private val library: LocalLibraryRepository,
    private val settings: LocalSettingsRepository,
    private val now: () -> String,
    private val payloads: List<SyncPayload> = emptyList(),
) {
    private var generation = 0L

    // Two callers can now ask for a sync at once — the UI's account repository on
    // its timer and POST /api/auth/sync from a compatibility client. Overlapping
    // runs would interleave pull-then-push against the same tables.
    private val running = Mutex()

    suspend fun reconcileAndSync(userId: String, accessToken: String): SyncResult = running.withLock {
        val profileId = reconcileProfile(userId, accessToken)
        val pulled = pull(accessToken, profileId)

        library.mergeFromRemote(pulled.library)
        pulled.settings?.let { settings.mergeFromRemote(it) }
        pulled.payloads.forEach { (kind, remote) ->
            val participant = payloads.firstOrNull { it.kind == kind }
            if (participant == null) {
                persistOpaque(profileId, kind, remote)
            } else {
                participant.merge(
                    SyncSnapshot(
                        json = CoveJson.encodeToString(JsonElement.serializer(), remote.data),
                        updatedAt = remote.updatedAt,
                    ),
                )
            }
        }
        profiles.linkSupabase(profileId, userId)

        val pushErrors = mutableListOf<String>()
        suspend fun push(label: String, operation: suspend () -> Unit) {
            runCatching { operation() }.onFailure { pushErrors += "$label: ${it.message}" }
        }
        push("library") { pushLibrary(accessToken, profileId) }
        push("settings") { pushSettings(accessToken, profileId) }
        SYNCED_PAYLOAD_KINDS.forEach { kind ->
            push(kind) { pushPayload(accessToken, profileId, kind) }
        }
        push("profile") { ensureProfile(accessToken, userId) }

        SyncResult(++generation, pushErrors.joinToString("; "))
    }

    suspend fun registerProfile(userId: String, accessToken: String, requestedName: String) {
        val active = profiles.activeSyncRecord()
        if (requestedName.isNotBlank() && requestedName.trim() != active.name) {
            profiles.rename(active.id, requestedName)
        }
        profiles.linkSupabase(active.id, userId)
        ensureProfile(accessToken, userId)
        val result = reconcileAndSync(userId, accessToken)
        if (result.pushError.isNotBlank()) {
            throw IllegalStateException("profile created but initial push was incomplete: ${result.pushError}")
        }
    }

    private suspend fun reconcileProfile(userId: String, token: String): String {
        var active = profiles.activeSyncRecord()
        val remotes = client.select(
            token,
            "profiles",
            "user_id=eq.${userId.encodeURLParameter()}",
        ).map { CoveJson.decodeFromJsonElement<RemoteProfile>(it) }

        remotes.firstOrNull { it.id == active.id }?.let { matching ->
            if (matching.name.isNotBlank() && matching.updatedAt > active.nameUpdatedAt) {
                profiles.renameFromRemote(active.id, matching.name, matching.updatedAt)
            }
            return active.id
        }
        if (remotes.isEmpty()) {
            ensureProfile(token, userId)
            return active.id
        }

        val target = remotes.firstOrNull(RemoteProfile::isPrimary) ?: remotes.first()
        if (profiles.adoptActiveId(target.id)) {
            active = profiles.activeSyncRecord()
            if (target.name.isNotBlank() && target.name != active.name) {
                profiles.renameFromRemote(active.id, target.name, target.updatedAt)
            }
            return target.id
        }

        // Another local profile already owns the remote ID. Preserve both by
        // registering the currently active local profile as a second profile.
        ensureProfile(token, userId)
        return active.id
    }

    private suspend fun ensureProfile(token: String, userId: String) {
        val active = profiles.activeSyncRecord()
        client.upsert(token, "profiles", buildJsonArray {
            add(buildJsonObject {
                put("id", active.id)
                put("user_id", userId)
                put("name", active.name)
                put("is_primary", active.isPrimary)
                put("updated_at", active.nameUpdatedAt.ifBlank(now))
            })
        })
    }

    private suspend fun pull(token: String, profileId: String): PulledData {
        val filter = "profile_id=eq.${profileId.encodeURLParameter()}"
        val entries = client.select(token, "library_entries", filter).map {
            CoveJson.decodeFromJsonElement<LibraryEntry>(it)
        }
        val progress = client.select(token, "watch_progress", filter).map {
            CoveJson.decodeFromJsonElement<WatchProgress>(it)
        }
        val dismissals = client.select(token, "dismissals", filter).map {
            val row = CoveJson.decodeFromJsonElement<RemoteDismissal>(it)
            SyncDismissal(row.tmdbId, row.mediaType, row.dismissedAt)
        }
        val removals = client.select(token, "library_removals", filter).map {
            val row = CoveJson.decodeFromJsonElement<RemoteRemoval>(it)
            SyncRemoval(row.tmdbId, row.mediaType, row.removedAt)
        }
        val settingsRow = client.select(
            token,
            "profile_settings",
            "$filter&order=updated_at.desc&limit=1",
        ).firstOrNull()?.let { CoveJson.decodeFromJsonElement<RemoteSettings>(it).data }
        val pulledPayloads = SYNCED_PAYLOAD_KINDS.mapNotNull { kind ->
            pullOpaque(token, tableFor(kind), filter)?.let { kind to it }
        }
        return PulledData(
            LibrarySyncSnapshot(entries, progress, dismissals, removals),
            settingsRow,
            pulledPayloads,
        )
    }

    private suspend fun pushLibrary(token: String, profileId: String) {
        val snapshot = library.snapshotForSync(profileId)
        if (snapshot.entries.isNotEmpty()) {
            client.upsert(token, "library_entries", JsonArray(snapshot.entries.map {
                CoveJson.encodeToJsonElement(it.copy(profileId = profileId))
            }))
        }
        if (snapshot.progress.isNotEmpty()) {
            client.upsert(token, "watch_progress", JsonArray(snapshot.progress.map {
                CoveJson.encodeToJsonElement(it.copy(profileId = profileId))
            }))
        }
        if (snapshot.dismissals.isNotEmpty()) {
            client.upsert(token, "dismissals", buildJsonArray {
                snapshot.dismissals.forEach { dismissal ->
                    add(buildJsonObject {
                        put("profile_id", profileId)
                        put("tmdb_id", dismissal.tmdbId)
                        put("media_type", dismissal.mediaType)
                        put("dismissed_at", dismissal.dismissedAt)
                    })
                }
            })
        }
        if (snapshot.removals.isNotEmpty()) {
            client.upsert(token, "library_removals", buildJsonArray {
                snapshot.removals.forEach { removal ->
                    add(buildJsonObject {
                        put("profile_id", profileId)
                        put("tmdb_id", removal.tmdbId)
                        put("media_type", removal.mediaType)
                        put("removed_at", removal.removedAt)
                    })
                }
            })
            snapshot.removals.forEach { removal ->
                client.delete(
                    token,
                    "library_entries",
                    "profile_id=eq.${profileId.encodeURLParameter()}" +
                        "&tmdb_id=eq.${removal.tmdbId}" +
                        "&media_type=eq.${removal.mediaType.encodeURLParameter()}",
                )
            }
        }
    }

    private suspend fun pushSettings(token: String, profileId: String) {
        val snapshot = settings.snapshotForSync(profileId)
        client.upsert(token, "profile_settings", buildJsonArray {
            add(buildJsonObject {
                put("profile_id", profileId)
                put("data", CoveJson.encodeToJsonElement(snapshot))
                put("updated_at", snapshot.updatedAt.orEmpty().ifBlank(now))
            })
        })
    }

    /**
     * Pushes one payload kind, from the participant that owns it when there is
     * one and from the stored blob otherwise. A host with neither pushes nothing
     * at all rather than an empty value — an empty push would look like a
     * deliberate "I have no addons" to every other device.
     */
    private suspend fun pushPayload(token: String, profileId: String, kind: String) {
        val snapshot = payloads.firstOrNull { it.kind == kind }?.snapshot()
            ?: database.coveQueries.selectLegacyPayloadRecord(profileId, kind)
                .executeAsOneOrNull()
                ?.let { SyncSnapshot(it.json, it.updated_at) }
            ?: return
        client.upsert(token, tableFor(kind), buildJsonArray {
            add(buildJsonObject {
                put("profile_id", profileId)
                put("data", CoveJson.parseToJsonElement(snapshot.json))
                put("updated_at", snapshot.updatedAt.ifBlank(now))
            })
        })
    }

    private suspend fun pullOpaque(token: String, table: String, filter: String): RemoteOpaque? =
        client.select(token, table, filter).firstOrNull()?.let {
            val obj = it.jsonObject
            RemoteOpaque(
                data = obj["data"] ?: return@let null,
                updatedAt = obj["updated_at"]?.toString()?.trim('"').orEmpty(),
            )
        }

    private fun persistOpaque(profileId: String, kind: String, remote: RemoteOpaque) {
        val local = database.coveQueries.selectLegacyPayloadRecord(profileId, kind).executeAsOneOrNull()
        if (local == null || remote.updatedAt > local.updated_at) {
            database.coveQueries.upsertLegacyPayload(
                profileId,
                kind,
                CoveJson.encodeToString(JsonElement.serializer(), remote.data),
                remote.updatedAt,
            )
        }
    }

    private data class PulledData(
        val library: LibrarySyncSnapshot,
        val settings: AppSettings?,
        val payloads: List<Pair<String, RemoteOpaque>>,
    )

    private data class RemoteOpaque(val data: JsonElement, val updatedAt: String)

    @Serializable
    private data class RemoteProfile(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String = "",
        @SerialName("is_primary") val isPrimary: Boolean = false,
        @SerialName("updated_at") val updatedAt: String = "",
    )

    @Serializable
    private data class RemoteDismissal(
        @SerialName("tmdb_id") val tmdbId: Int,
        @SerialName("media_type") val mediaType: String,
        @SerialName("dismissed_at") val dismissedAt: String,
    )

    @Serializable
    private data class RemoteRemoval(
        @SerialName("tmdb_id") val tmdbId: Int,
        @SerialName("media_type") val mediaType: String,
        @SerialName("removed_at") val removedAt: String,
    )

    @Serializable
    private data class RemoteSettings(val data: AppSettings)

    companion object {
        /**
         * Everything carried as a per-profile JSON blob, in `profile_<kind>`.
         * Listed as a constant rather than derived from [payloads]: a host must
         * pull and re-push the kinds it does not itself own, or the first sync
         * from that host would drop them for every other device.
         */
        val SYNCED_PAYLOAD_KINDS = listOf("addons", "nuvio", "activity")

        private fun tableFor(kind: String) = "profile_$kind"
    }
}

/** One JSON blob a host owns and can merge, keyed by [kind]. */
data class SyncSnapshot(val json: String, val updatedAt: String)

/**
 * A subsystem that participates in sync directly instead of having its blob
 * round-tripped opaquely — implemented where the subsystem actually exists, so
 * that [SupabaseSyncService] can stay in commonMain alongside the hosts that
 * lack it.
 */
interface SyncPayload {
    /** One of [SupabaseSyncService.SYNCED_PAYLOAD_KINDS]. */
    val kind: String

    suspend fun snapshot(): SyncSnapshot

    suspend fun merge(snapshot: SyncSnapshot)
}
