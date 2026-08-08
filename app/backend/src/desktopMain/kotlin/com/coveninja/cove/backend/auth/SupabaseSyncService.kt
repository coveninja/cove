package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.addons.AddonEntry
import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.LibrarySyncSnapshot
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.backend.store.SyncDismissal
import com.coveninja.cove.backend.store.SyncRemoval
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.http.encodeURLParameter
import java.util.concurrent.atomic.AtomicLong
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
 */
class SupabaseSyncService(
    private val client: SupabaseClient,
    private val database: CoveDatabase,
    private val profiles: LocalProfileRepository,
    private val library: LocalLibraryRepository,
    private val settings: LocalSettingsRepository,
    private val addons: AddonManager,
    private val now: () -> String,
    private val nuvio: NuvioManager? = null,
    private val activity: ActivityService? = null,
) {
    private val generation = AtomicLong()

    suspend fun reconcileAndSync(userId: String, accessToken: String): SyncResult {
        val profileId = reconcileProfile(userId, accessToken)
        val pulled = pull(accessToken, profileId)

        library.mergeFromRemote(pulled.library)
        pulled.settings?.let { settings.mergeFromRemote(it) }
        pulled.addons?.let { addons.mergeFromRemote(it.entries, it.updatedAt) }
        pulled.nuvio?.let { remote ->
            if (nuvio != null) {
                nuvio.mergeFromRemote(
                    CoveJson.encodeToString(JsonElement.serializer(), remote.data),
                    remote.updatedAt,
                )
            } else {
                persistOpaque(profileId, "nuvio", remote)
            }
        }
        pulled.activity?.let { remote ->
            if (activity != null) {
                activity.mergeFromJson(
                    CoveJson.encodeToString(JsonElement.serializer(), remote.data),
                    profileId,
                )
            } else {
                persistOpaque(profileId, "activity", remote)
            }
        }
        profiles.linkSupabase(profileId, userId)

        val pushErrors = mutableListOf<String>()
        suspend fun push(label: String, operation: suspend () -> Unit) {
            runCatching { operation() }.onFailure { pushErrors += "$label: ${it.message}" }
        }
        push("library") { pushLibrary(accessToken, profileId) }
        push("settings") { pushSettings(accessToken, profileId) }
        push("addons") { pushAddons(accessToken, profileId) }
        push("nuvio") {
            if (nuvio == null) pushOpaque(accessToken, profileId, "nuvio", "profile_nuvio")
            else {
                val snapshot = nuvio.snapshotForSync()
                client.upsert(accessToken, "profile_nuvio", buildJsonArray {
                    add(buildJsonObject {
                        put("profile_id", profileId)
                        put("data", CoveJson.encodeToJsonElement(snapshot))
                        put("updated_at", snapshot.updatedAt.ifBlank(now))
                    })
                })
            }
        }
        push("activity") {
            if (activity == null) {
                pushOpaque(accessToken, profileId, "activity", "profile_activity")
            } else {
                client.upsert(accessToken, "profile_activity", buildJsonArray {
                    add(buildJsonObject {
                        put("profile_id", profileId)
                        put("data", CoveJson.parseToJsonElement(activity.snapshotJson(profileId)))
                        put("updated_at", now())
                    })
                })
            }
        }
        push("profile") { ensureProfile(accessToken, userId) }

        return SyncResult(generation.incrementAndGet(), pushErrors.joinToString("; "))
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
        val addonRow = client.select(token, "profile_addons", filter).firstOrNull()?.let {
            CoveJson.decodeFromJsonElement<RemoteAddons>(it)
        }
        val nuvio = pullOpaque(token, "profile_nuvio", filter)
        val activity = pullOpaque(token, "profile_activity", filter)
        return PulledData(
            LibrarySyncSnapshot(entries, progress, dismissals, removals),
            settingsRow,
            addonRow,
            nuvio,
            activity,
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

    private suspend fun pushAddons(token: String, profileId: String) {
        val snapshot = addons.snapshotForSync()
        client.upsert(token, "profile_addons", buildJsonArray {
            add(buildJsonObject {
                put("profile_id", profileId)
                put("data", JsonArray(snapshot.entries.map(CoveJson::encodeToJsonElement)))
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

    private suspend fun pushOpaque(token: String, profileId: String, kind: String, table: String) {
        val local = database.coveQueries.selectLegacyPayloadRecord(profileId, kind).executeAsOneOrNull()
            ?: return
        client.upsert(token, table, buildJsonArray {
            add(buildJsonObject {
                put("profile_id", profileId)
                put("data", CoveJson.parseToJsonElement(local.json))
                put("updated_at", local.updated_at.ifBlank(now))
            })
        })
    }

    private data class PulledData(
        val library: LibrarySyncSnapshot,
        val settings: AppSettings?,
        val addons: RemoteAddons?,
        val nuvio: RemoteOpaque?,
        val activity: RemoteOpaque?,
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

    @Serializable
    private data class RemoteAddons(
        val data: List<AddonEntry> = emptyList(),
        @SerialName("updated_at") val updatedAt: String = "",
    ) {
        val entries: List<AddonEntry> get() = data
    }
}
