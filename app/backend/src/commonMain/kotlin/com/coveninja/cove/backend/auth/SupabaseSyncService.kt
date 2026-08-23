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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Rows in a bulk upsert must all carry the same keys — PostgREST refuses the
 * whole batch with "All object keys must match" otherwise. [CoveJson] omits null
 * fields, so a library where one title has a rating and another does not produces
 * two different shapes and the entire library push fails. Here nulls are spelled
 * out instead, which is also the truthful value for a column that has one.
 */
private val SyncRowJson = Json(CoveJson) { explicitNulls = true }

data class SyncResult(
    val libraryGeneration: Long,
    val pushError: String = "",
    /** Remote rows this build could not read, and therefore did not merge. */
    val pullWarning: String = "",
)

/**
 * Cross-device synchronization using only the publishable key and the user's
 * access token. All writes therefore remain subject to Supabase RLS.
 *
 * Everything beyond the library, settings and profile row travels as an opaque
 * per-profile JSON blob in `profile_<kind>`. A host that owns the subsystem
 * supplies a [SyncPayload] and gets a typed merge; a host that does not own a
 * subsystem round-trips its blob through `legacy_payloads` untouched. Android,
 * for example, owns addons but still passes Nuvio and activity through. That
 * passthrough keeps one host from overwriting another host's configured data.
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
        // Before anything reads the roster. A profile deleted here is already gone
        // locally, so a roster read that still saw it alive upstream would insert it
        // straight back and the deletion would have to fight its way out again.
        val removalError = runCatching { pushProfileRemovals(accessToken) }
            .exceptionOrNull()
            ?.also { if (it is SupabaseException && it.statusCode == UNAUTHORIZED) throw it }
        val profileId = reconcileRoster(userId, accessToken)
        val pulled = pull(accessToken, profileId)

        library.mergeFromRemote(pulled.library)
        pulled.settings?.let { settings.mergeFromRemote(it) }

        val pullWarnings = mutableListOf<String>()
        if (pulled.warning.isNotBlank()) pullWarnings += pulled.warning
        pulled.payloads.forEach { (kind, remote) ->
            val participant = payloads.firstOrNull { it.kind == kind }
            if (participant == null) {
                persistOpaque(profileId, kind, remote)
            } else {
                // A blob this build cannot read costs that blob, not the sync.
                // These carry shapes written by several generations of the app,
                // and one unreadable field in the addon list used to mean nothing
                // synced at all — library, settings and progress included. The
                // local copy stands, and the push below then heals the remote.
                runCatching {
                    participant.merge(
                        SyncSnapshot(
                            json = CoveJson.encodeToString(JsonElement.serializer(), remote.data),
                            updatedAt = remote.updatedAt,
                        ),
                    )
                }.onFailure { pullWarnings += "$kind not merged: ${it.message}" }
            }
        }
        profiles.linkSupabase(profileId, userId)

        val pushErrors = mutableListOf<String>()
        suspend fun push(label: String, operation: suspend () -> Unit) {
            runCatching { operation() }.onFailure { failure ->
                // A refused token is not a partial push. Every remaining push would
                // fail the same way, and collecting them into a status line strands
                // the caller with a report instead of the one exception that lets it
                // refresh the session and run the whole sync again.
                if (failure is SupabaseException && failure.statusCode == UNAUTHORIZED) {
                    throw failure
                }
                pushErrors += "$label: ${failure.message}"
            }
        }
        push("library") { pushLibrary(accessToken, profileId) }
        push("settings") { pushSettings(accessToken, profileId) }
        SYNCED_PAYLOAD_KINDS.forEach { kind ->
            push(kind) { pushPayload(accessToken, profileId, kind) }
        }
        push("profile") { pushRoster(accessToken, userId) }
        removalError?.let { pushErrors += "profile removals: ${it.message}" }

        SyncResult(++generation, pushErrors.joinToString("; "), pullWarnings.joinToString("; "))
    }

    suspend fun registerProfile(userId: String, accessToken: String, requestedName: String) {
        val active = profiles.activeSyncRecord()
        if (requestedName.isNotBlank() && requestedName.trim() != active.name) {
            profiles.rename(active.id, requestedName)
        }
        profiles.linkSupabase(active.id, userId)
        pushRoster(accessToken, userId)
        val result = reconcileAndSync(userId, accessToken)
        if (result.pushError.isNotBlank()) {
            throw IllegalStateException("profile created but initial push was incomplete: ${result.pushError}")
        }
    }

    /**
     * Brings this device's profile list into line with the account's, and returns
     * the profile whose data the rest of the sync should carry.
     *
     * Two steps that must stay in this order. [reconcileIdentity] settles which
     * *one* profile this device already is — a fresh install re-keys its default
     * profile onto the account's primary rather than becoming a duplicate of it —
     * and only then does [mergeRoster] add everything else the account owns. Merging
     * first would insert the primary as a second local profile, and the adoption
     * would then find the id taken and give up.
     */
    private suspend fun reconcileRoster(userId: String, token: String): String {
        val remotes = client.select(
            token,
            "profiles",
            "user_id=eq.${userId.encodeURLParameter()}",
        ).map { CoveJson.decodeFromJsonElement<RemoteProfile>(it) }
        reconcileIdentity(userId, token, remotes)
        mergeRoster(userId, remotes)
        // Read *after* the merge rather than taking what reconcileIdentity settled
        // on. A tombstone for the profile in use is applied in there, and the rest
        // of the sync would otherwise pull, link and push against a profile this
        // device has just deleted.
        return profiles.activeSyncRecord().id
    }

    private suspend fun reconcileIdentity(
        userId: String,
        token: String,
        remotes: List<RemoteProfile>,
    ) {
        var active = profiles.activeSyncRecord()
        val live = remotes.filter { it.deletedAt.isBlank() }

        live.firstOrNull { it.id == active.id }?.let { matching ->
            if (matching.name.isNotBlank() && matching.updatedAt > active.nameUpdatedAt) {
                profiles.renameFromRemote(active.id, matching.name, matching.updatedAt)
            }
            return
        }
        if (live.isEmpty()) {
            pushRoster(token, userId)
            return
        }

        val target = choosePrimary(live) ?: live.first()
        if (profiles.adoptActiveId(target.id)) {
            active = profiles.activeSyncRecord()
            if (target.name.isNotBlank() && target.name != active.name) {
                profiles.renameFromRemote(active.id, target.name, target.updatedAt)
            }
            return
        }

        // Another local profile already owns the remote ID. Preserve both by
        // registering the currently active local profile as a second profile.
        pushRoster(token, userId)
    }

    /**
     * Adds the account's other profiles to this device, and drops the ones it says
     * are gone. This is what makes a profile created on one device appear on the
     * rest; without it a second device only ever adopts the primary.
     */
    private suspend fun mergeRoster(userId: String, remotes: List<RemoteProfile>) {
        val known = profiles.syncRecords().associateBy { it.id }
        val live = remotes.filter { it.deletedAt.isBlank() }

        // Tombstones first, so a deleted profile cannot be the one [choosePrimary]
        // settles on and cannot be renamed on its way out.
        remotes.filter { it.deletedAt.isNotBlank() }.forEach { gone ->
            if (known.containsKey(gone.id)) profiles.deleteFromRemote(gone.id)
        }

        // A removal this device has not managed to push yet still counts as deleted.
        // Re-adding it here would undo the user's action every sync until the push
        // finally lands.
        val unpushed = profiles.pendingRemovals().mapTo(mutableSetOf()) { it.profileId }
        live.forEach { remote ->
            if (remote.id in unpushed) return@forEach
            val existing = known[remote.id]
            when {
                existing == null -> profiles.insertFromRemote(
                    remote.id,
                    remote.name,
                    remote.isPrimary,
                    userId,
                    remote.updatedAt,
                )
                remote.name.isNotBlank() && remote.updatedAt > existing.nameUpdatedAt ->
                    profiles.renameFromRemote(remote.id, remote.name, remote.updatedAt)
            }
        }

        // Exactly one primary per account. Nothing upstream enforces it, and two
        // devices that each believed they were primary would take turns demoting
        // each other — which the addon-sharing policy reads, so it is not cosmetic.
        val primary = choosePrimary(live) ?: return
        profiles.syncRecords().forEach { profiles.applyRemoteFlags(it.id, it.id == primary.id) }
    }

    /** Deterministic across devices: id order breaks a tie the same way everywhere. */
    private fun choosePrimary(live: List<RemoteProfile>): RemoteProfile? =
        live.sortedBy { it.id }.firstOrNull { it.isPrimary }

    /**
     * Pushes every local profile, not just the active one — a profile created here
     * and never switched to still belongs to the account.
     *
     * The rows are built uniformly because PostgREST refuses a bulk upsert whose
     * objects do not all carry identical keys ("All object keys must match"), which
     * would fail the whole roster rather than one row of it.
     */
    private suspend fun pushRoster(token: String, userId: String) {
        // Profiles left behind by a previous account are not this account's to
        // claim. A null uid is a profile made on this device and not yet linked,
        // which is precisely the one that should join.
        val records = profiles.syncRecords()
            .filter { it.supabaseUid.isNullOrBlank() || it.supabaseUid == userId }
        if (records.isEmpty()) return
        client.upsert(token, "profiles", buildJsonArray {
            records.forEach { record ->
                // No deleted_at key: PostgREST's merge-duplicates updates only the
                // columns present, so a device that still has a profile another one
                // deleted refreshes its name without clearing the tombstone.
                add(buildJsonObject {
                    put("id", record.id)
                    put("user_id", userId)
                    put("name", record.name)
                    put("is_primary", record.isPrimary)
                    put("updated_at", record.nameUpdatedAt.ifBlank(now))
                })
            }
        })
    }

    /**
     * Tombstones profiles deleted on this device and clears out their data.
     *
     * The profiles row is kept and marked rather than deleted: a device that was
     * offline when the deletion happened still has the profile locally, and with no
     * tombstone to read it would push it back and resurrect it on every device.
     */
    private suspend fun pushProfileRemovals(token: String) {
        profiles.pendingRemovals().forEach { removal ->
            val id = removal.profileId.encodeURLParameter()
            // Children before the parent, always. Child RLS proves ownership by
            // looking the parent row up, so a tombstone written first would put
            // every one of that profile's rows beyond this account's reach.
            PROFILE_CHILD_TABLES.forEach { table ->
                client.delete(token, table, "profile_id=eq.$id")
            }
            client.patch(token, "profiles", "id=eq.$id", buildJsonObject {
                put("deleted_at", removal.removedAt.ifBlank(now))
            })
            // Only now. An interrupted removal has to run again next time rather
            // than leaving the account with a profile no device will ever tidy up.
            profiles.clearRemoval(removal.profileId)
        }
    }

    private suspend fun pull(token: String, profileId: String): PulledData {
        val filter = "profile_id=eq.${profileId.encodeURLParameter()}"
        val skipped = mutableMapOf<String, Int>()

        /**
         * Decodes row by row, skipping the ones this build cannot read.
         *
         * These tables have been written by several generations of this app, and
         * one row from an older one used to abort the entire sync — library,
         * settings, addons and all — over a single unreadable field. Skipping is
         * not silent: the count comes back as [SyncResult.pullWarning].
         */
        suspend fun <T> rows(table: String, query: String = filter, decode: (JsonElement) -> T): List<T> =
            client.select(token, table, query).mapNotNull { row ->
                runCatching { decode(row) }
                    .onFailure { skipped[table] = (skipped[table] ?: 0) + 1 }
                    .getOrNull()
            }

        val entries = rows("library_entries") { CoveJson.decodeFromJsonElement<LibraryEntry>(it) }
        val progress = rows("watch_progress") { CoveJson.decodeFromJsonElement<WatchProgress>(it) }
        val dismissals = rows("dismissals") {
            val row = CoveJson.decodeFromJsonElement<RemoteDismissal>(it)
            SyncDismissal(row.tmdbId, row.mediaType, row.dismissedAt)
        }
        val removals = rows("library_removals") {
            val row = CoveJson.decodeFromJsonElement<RemoteRemoval>(it)
            SyncRemoval(row.tmdbId, row.mediaType, row.removedAt)
        }
        val settingsRow = rows(
            "profile_settings",
            "$filter&order=updated_at.desc&limit=1",
        ) { CoveJson.decodeFromJsonElement<RemoteSettings>(it).data }.firstOrNull()
        val pulledPayloads = SYNCED_PAYLOAD_KINDS.mapNotNull { kind ->
            pullOpaque(token, tableFor(kind), filter)?.let { kind to it }
        }
        return PulledData(
            LibrarySyncSnapshot(entries, progress, dismissals, removals),
            settingsRow,
            pulledPayloads,
            skipped.entries.joinToString("; ") { (table, count) ->
                "$count unreadable row${if (count == 1) "" else "s"} in $table"
            },
        )
    }

    private suspend fun pushLibrary(token: String, profileId: String) {
        val snapshot = library.snapshotForSync(profileId)
        if (snapshot.entries.isNotEmpty()) {
            client.upsert(token, "library_entries", JsonArray(snapshot.entries.map {
                SyncRowJson.encodeToJsonElement(it.copy(profileId = profileId))
            }))
        }
        if (snapshot.progress.isNotEmpty()) {
            // Fill in the entry id where this profile knows it. Rows migrated from
            // the pre-SQLite files have none, and sending what we do know repairs
            // the link for every client rather than writing the gap back out.
            //
            // A stored id is only sent if it points at an entry going up in the
            // same push: upstream the column is a foreign key, and progress left
            // over from a title that has since been removed from the library still
            // names the entry it used to belong to. One such row fails the whole
            // batch, so an id that cannot be vouched for becomes null — which is
            // what "watched, but not in the library" means anyway.
            val entryIds = snapshot.entries.associate {
                "${it.tmdbId}:${it.mediaType.wireName}" to it.id
            }
            val pushedEntryIds = snapshot.entries.mapTo(mutableSetOf()) { it.id }
            client.upsert(token, "watch_progress", JsonArray(snapshot.progress.map { progress ->
                val linked = progress.copy(
                    profileId = profileId,
                    libraryEntryId = progress.libraryEntryId?.takeIf { it in pushedEntryIds }
                        ?: entryIds["${progress.tmdbId}:${progress.mediaType.wireName}"],
                )
                SyncRowJson.encodeToJsonElement(linked)
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
        val warning: String = "",
    )

    private data class RemoteOpaque(val data: JsonElement, val updatedAt: String)

    @Serializable
    private data class RemoteProfile(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String = "",
        @SerialName("is_primary") val isPrimary: Boolean = false,
        @SerialName("updated_at") val updatedAt: String = "",
        // Defaulted so a client still works against a project without the column.
        @SerialName("deleted_at") val deletedAt: String = "",
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

        /**
         * Every table keyed by `profile_id`, cleared when a profile is deleted.
         * Kept beside [SYNCED_PAYLOAD_KINDS] rather than derived from it: three of
         * these are not payload blobs, and a table missing from this list leaves
         * rows upstream that nothing can reach once the profile is tombstoned.
         */
        private val PROFILE_CHILD_TABLES = listOf(
            "library_entries",
            "watch_progress",
            "dismissals",
            "library_removals",
            "profile_settings",
        ) + SYNCED_PAYLOAD_KINDS.map(::tableFor)

        private const val UNAUTHORIZED = 401

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
