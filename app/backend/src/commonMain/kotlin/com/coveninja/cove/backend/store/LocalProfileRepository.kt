package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.data.ProfileRepository
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalProfileRepository(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val newId: () -> String,
    private val now: () -> String,
) : ProfileRepository {
    private val mutation = Mutex()
    private val _profiles = MutableStateFlow<ProfilesState>(ProfilesState.Loading)
    override val profiles: StateFlow<ProfilesState> = _profiles.asStateFlow()

    init {
        refresh()
    }

    override suspend fun create(name: String): Profile = mutation.withLock {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "profile name must not be empty" }
        require(cleanName.length <= 64) { "profile name must be at most 64 characters" }
        val profile = Profile(newId(), cleanName)
        database.coveQueries.insertProfile(profile.id, profile.name, 0L, null, now())
        refresh()
        profile
    }

    override suspend fun rename(id: String, name: String) = mutation.withLock {
        val existing = database.coveQueries.selectProfileById(id).executeAsOneOrNull()
            ?: error("unknown profile $id")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "profile name must not be empty" }
        require(cleanName.length <= 64) { "profile name must be at most 64 characters" }
        database.coveQueries.updateProfile(
            cleanName,
            existing.is_primary,
            existing.supabase_uid,
            now(),
            existing.id,
        )
        refresh()
    }

    override suspend fun activate(id: String) = mutation.withLock {
        session.activate(id)
        refresh()
    }

    override suspend fun delete(id: String) = mutation.withLock {
        val existing = database.coveQueries.selectProfileById(id).executeAsOneOrNull()
            ?: error("unknown profile $id")
        require(existing.is_primary == 0L) { "the primary profile cannot be deleted" }
        require(session.profileId.value != id) { "activate another profile before deleting this one" }
        database.coveQueries.deleteProfileById(id)
        refresh()
    }

    internal suspend fun linkSupabase(id: String, userId: String) = mutation.withLock {
        val existing = database.coveQueries.selectProfileById(id).executeAsOneOrNull()
            ?: error("unknown profile $id")
        database.coveQueries.updateProfile(
            existing.name,
            existing.is_primary,
            userId,
            existing.name_updated_at,
            id,
        )
        refresh()
    }

    internal suspend fun unlinkSupabase(id: String) = mutation.withLock {
        val existing = database.coveQueries.selectProfileById(id).executeAsOneOrNull()
            ?: error("unknown profile $id")
        database.coveQueries.updateProfile(
            existing.name,
            existing.is_primary,
            null,
            existing.name_updated_at,
            id,
        )
        refresh()
    }

    internal suspend fun renameFromRemote(id: String, name: String, updatedAt: String) = mutation.withLock {
        val existing = database.coveQueries.selectProfileById(id).executeAsOneOrNull()
            ?: error("unknown profile $id")
        database.coveQueries.updateProfile(
            name.trim().takeIf(String::isNotEmpty) ?: existing.name,
            existing.is_primary,
            existing.supabase_uid,
            updatedAt,
            id,
        )
        refresh()
    }

    /**
     * Re-keys a fresh local profile to the account's canonical remote profile.
     * Every profile-scoped table moves in the same transaction so observers
     * never see a half-adopted identity.
     */
    internal suspend fun adoptActiveId(targetId: String): Boolean = mutation.withLock {
        val sourceId = session.profileId.value
        if (sourceId == targetId) return@withLock true
        if (database.coveQueries.selectProfileById(targetId).executeAsOneOrNull() != null) {
            return@withLock false
        }
        val source = database.coveQueries.selectProfileById(sourceId).executeAsOne()
        database.transaction {
            database.coveQueries.insertProfile(
                targetId,
                source.name,
                source.is_primary,
                source.supabase_uid,
                source.name_updated_at,
            )
            database.coveQueries.moveSettingsToProfile(targetId, sourceId)
            database.coveQueries.moveLibraryEntriesToProfile(targetId, sourceId)
            database.coveQueries.moveWatchProgressToProfile(targetId, sourceId)
            database.coveQueries.moveDismissalsToProfile(targetId, sourceId)
            database.coveQueries.moveRemovalsToProfile(targetId, sourceId)
            database.coveQueries.moveLegacyPayloadsToProfile(targetId, sourceId)
            database.coveQueries.moveAddonsToProfile(targetId, sourceId)
            database.coveQueries.moveProfileStoreVersionsToProfile(targetId, sourceId)
            database.coveQueries.moveNuvioStateToProfile(targetId, sourceId)
            database.coveQueries.moveActivityHoursToProfile(targetId, sourceId)
            database.coveQueries.moveActivityTitlesToProfile(targetId, sourceId)
            database.coveQueries.moveActivityPositionsToProfile(targetId, sourceId)
            database.coveQueries.moveActivityStateToProfile(targetId, sourceId)
            database.coveQueries.moveTraktSessionToProfile(targetId, sourceId)
            database.coveQueries.setActiveProfile(targetId)
            database.coveQueries.deleteProfileById(sourceId)
        }
        session.refresh()
        refresh()
        true
    }

    internal fun activeSyncRecord(): ProfileSyncRecord {
        val row = database.coveQueries.selectProfileById(session.profileId.value).executeAsOne()
        return ProfileSyncRecord(
            id = row.id,
            name = row.name,
            isPrimary = row.is_primary != 0L,
            supabaseUid = row.supabase_uid,
            nameUpdatedAt = row.name_updated_at,
        )
    }

    internal fun refresh() {
        _profiles.value = try {
            ProfilesState.Ready(
                profiles = database.coveQueries.selectProfiles().executeAsList().map {
                    Profile(
                        id = it.id,
                        name = it.name,
                        isPrimary = it.is_primary != 0L,
                        supabaseUid = it.supabase_uid,
                    )
                },
                activeProfileId = session.profileId.value,
            )
        } catch (error: Exception) {
            ProfilesState.Failed(error.message ?: "Unknown error loading profiles")
        }
    }
}

internal data class ProfileSyncRecord(
    val id: String,
    val name: String,
    val isPrimary: Boolean,
    val supabaseUid: String?,
    val nameUpdatedAt: String,
)
