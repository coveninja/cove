package com.coveninja.cove.backend.migration

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveJson
import java.io.File
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val IMPORT_KEY = "legacy_json_import_version"
private const val IMPORT_VERSION = "1"
private const val GLOBAL_PAYLOAD_SCOPE = ""
private val validProfileId = Regex("^[A-Za-z0-9_-]{1,64}$")

sealed interface AndroidMigrationResult {
    data object AlreadyImported : AndroidMigrationResult
    data class Imported(val profileCount: Int, val backupDirectory: File) : AndroidMigrationResult
}

@Serializable
private data class LegacyProfilesDocument(
    val profiles: List<LegacyProfile> = emptyList(),
    @SerialName("active_profile_id") val activeProfileId: String = "",
)

@Serializable
private data class LegacyProfile(
    val id: String,
    val name: String,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("supabase_uid") val supabaseUid: String? = null,
    @SerialName("name_updated_at") val nameUpdatedAt: String = "",
)

@Serializable
private data class LegacyLibraryDocument(
    val entries: Map<String, LibraryEntry?> = emptyMap(),
    val progress: Map<String, WatchProgress?> = emptyMap(),
    val dismissed: Map<String, LegacyDismissal?> = emptyMap(),
    val removed: Map<String, LegacyRemoval?> = emptyMap(),
)

@Serializable
private data class LegacyDismissal(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    @SerialName("dismissed_at") val dismissedAt: String,
)

@Serializable
private data class LegacyRemoval(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    @SerialName("removed_at") val removedAt: String,
)

/**
 * Imports data from Cove's former Android Go/WebView build. That build used
 * the same package id and wrote its JSON sidecars directly to filesDir, so an
 * APK upgrade can migrate profiles, settings, library state, and progress in
 * place. Other stores are retained as opaque payloads until their mobile
 * service adapters consume them.
 */
class AndroidLegacyMigration(
    private val database: CoveDatabase,
    private val dataDirectory: File,
    private val now: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun importIfNeeded(): AndroidMigrationResult {
        val queries = database.coveQueries
        if (queries.selectMigrationMetadata(IMPORT_KEY).executeAsOneOrNull() == IMPORT_VERSION) {
            return AndroidMigrationResult.AlreadyImported
        }

        check(dataDirectory.exists() || dataDirectory.mkdirs()) {
            "Unable to create ${dataDirectory.absolutePath}"
        }
        val profiles = loadProfiles()
        val parsed = profiles.profiles.associateWith { profile ->
            ParsedProfile(
                settings = readSettings(profile.id, profile.isPrimary),
                library = readLibrary(profile.id, profile.isPrimary),
                opaque = readOpaqueProfilePayloads(profile.id, profile.isPrimary),
            )
        }
        val globalPayloads = readGlobalPayloads()
        val backup = createBackup()

        database.transaction {
            queries.deleteActiveProfile()
            queries.deleteWatchProgress()
            queries.deleteDismissals()
            queries.deleteRemovals()
            queries.deleteLibraryEntries()
            queries.deleteSettings()
            queries.deleteAddons()
            queries.deleteProfileStoreVersions()
            queries.deleteNuvioState()
            queries.deleteActivityHours()
            queries.deleteActivityTitles()
            queries.deleteActivityPositions()
            queries.deleteActivityStates()
            queries.deleteTraktSessions()
            queries.deleteLegacyPayloads()
            queries.deleteProfiles()
            queries.deleteClientSession()

            profiles.profiles.forEach { profile ->
                queries.insertProfile(
                    id = profile.id,
                    name = profile.name,
                    is_primary = if (profile.isPrimary) 1L else 0L,
                    supabase_uid = profile.supabaseUid,
                    name_updated_at = profile.nameUpdatedAt,
                )
            }
            queries.setActiveProfile(profiles.activeProfileId)

            parsed.forEach { (profile, value) ->
                val settingsJson = CoveJson.encodeToString(value.settings)
                queries.upsertSettings(profile.id, settingsJson, value.settings.updatedAt.orEmpty())
                importLibrary(profile.id, value.library)
                value.opaque.forEach { (kind, json) ->
                    queries.upsertLegacyPayload(profile.id, kind, json, "")
                }
            }
            globalPayloads.forEach { (kind, json) ->
                queries.upsertLegacyPayload(GLOBAL_PAYLOAD_SCOPE, kind, json, "")
                if (kind == "session") queries.upsertClientSession(json, now())
            }
            queries.upsertMigrationMetadata(IMPORT_KEY, IMPORT_VERSION)
            queries.upsertMigrationMetadata("legacy_json_backup", backup.absolutePath)
            queries.upsertMigrationMetadata("legacy_json_imported_at", now())
        }
        return AndroidMigrationResult.Imported(profiles.profiles.size, backup)
    }

    private fun loadProfiles(): LegacyProfilesDocument {
        val source = dataDirectory.resolve("profiles.json")
        val document = if (source.isFile) {
            CoveJson.decodeFromString<LegacyProfilesDocument>(source.readText())
        } else {
            val primary = LegacyProfile(newId(), "Primary", isPrimary = true)
            LegacyProfilesDocument(listOf(primary), primary.id)
        }
        require(document.profiles.isNotEmpty()) { "profiles.json contains no profiles" }
        require(document.profiles.map(LegacyProfile::id).toSet().size == document.profiles.size) {
            "profiles.json contains duplicate profile ids"
        }
        document.profiles.forEach { profile ->
            require(validProfileId.matches(profile.id)) { "invalid profile id ${profile.id}" }
            require(profile.name.isNotBlank()) { "profile ${profile.id} has an empty name" }
        }

        val primaryIndex = document.profiles.indexOfFirst(LegacyProfile::isPrimary)
            .let { if (it < 0) 0 else it }
        val normalized = document.profiles.mapIndexed { index, profile ->
            profile.copy(isPrimary = index == primaryIndex)
        }
        val active = document.activeProfileId.takeIf { id -> normalized.any { it.id == id } }
            ?: normalized[primaryIndex].id
        return LegacyProfilesDocument(normalized, active)
    }

    private fun readSettings(profileId: String, primary: Boolean): AppSettings =
        profileFile("settings", profileId, primary)?.let { file ->
            CoveJson.decodeFromString<AppSettings>(file.readText())
        } ?: AppSettings()

    private fun readLibrary(profileId: String, primary: Boolean): LegacyLibraryDocument =
        profileFile("library", profileId, primary)?.let { file ->
            CoveJson.decodeFromString<LegacyLibraryDocument>(file.readText())
        } ?: LegacyLibraryDocument()

    private fun profileFile(kind: String, profileId: String, allowUnscoped: Boolean): File? {
        val scoped = dataDirectory.resolve("$kind-$profileId.json")
        if (scoped.isFile) return scoped
        if (!allowUnscoped) return null
        return dataDirectory.resolve("$kind.json").takeIf(File::isFile)
    }

    private fun readOpaqueProfilePayloads(profileId: String, primary: Boolean): Map<String, String> =
        listOf("activity", "addons", "nuvio", "trakt").mapNotNull { kind ->
            profileFile(kind, profileId, primary)?.let { kind to validatedJson(it) }
        }.toMap()

    private fun readGlobalPayloads(): Map<String, String> = listOf("session").mapNotNull { kind ->
        dataDirectory.resolve("$kind.json").takeIf(File::isFile)?.let { kind to validatedJson(it) }
    }.toMap()

    private fun validatedJson(file: File): String = file.readText().also(CoveJson::parseToJsonElement)

    private fun importLibrary(profileId: String, document: LegacyLibraryDocument) {
        val queries = database.coveQueries
        document.entries.values.filterNotNull().forEach { entry ->
            queries.upsertLibraryEntry(
                id = entry.id,
                profile_id = profileId,
                tmdb_id = entry.tmdbId.toLong(),
                media_type = entry.mediaType.wireName,
                title = entry.title,
                poster_path = entry.posterPath,
                status = entry.status.wireName,
                rating = entry.rating,
                vote_average = entry.voteAverage,
                last_air_date = entry.lastAirDate,
                last_watched_at = entry.lastWatchedAt,
                last_watched_season = entry.lastWatchedSeason?.toLong(),
                last_watched_episode = entry.lastWatchedEpisode?.toLong(),
                last_aired_season = entry.lastAiredSeason?.toLong(),
                last_aired_episode = entry.lastAiredEpisode?.toLong(),
                added_at = entry.addedAt,
                updated_at = entry.updatedAt,
            )
        }
        document.progress.forEach { (key, progress) ->
            progress ?: return@forEach
            queries.upsertWatchProgress(
                progress_key = key,
                id = progress.id,
                profile_id = profileId,
                // NOT NULL locally; a legacy file may carry no entry id at all.
                library_entry_id = progress.libraryEntryId.orEmpty(),
                tmdb_id = progress.tmdbId.toLong(),
                media_type = progress.mediaType.wireName,
                season = progress.season?.toLong(),
                episode = progress.episode?.toLong(),
                position_seconds = progress.positionSeconds,
                duration_seconds = progress.durationSeconds,
                completed = if (progress.completed) 1L else 0L,
                watched_at = progress.watchedAt,
            )
        }
        document.dismissed.values.filterNotNull().forEach { item ->
            queries.upsertDismissal(profileId, item.tmdbId.toLong(), item.mediaType, item.dismissedAt)
        }
        document.removed.values.filterNotNull().forEach { item ->
            queries.upsertRemoval(profileId, item.tmdbId.toLong(), item.mediaType, item.removedAt)
        }
    }

    private fun createBackup(): File {
        val backup = dataDirectory.resolve("migration-backup-v1-${nowMillis()}")
        check(backup.mkdirs() || backup.isDirectory) { "Unable to create ${backup.absolutePath}" }
        dataDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .forEach { source -> source.copyTo(backup.resolve(source.name), overwrite = false) }
        return backup
    }

    private data class ParsedProfile(
        val settings: AppSettings,
        val library: LegacyLibraryDocument,
        val opaque: Map<String, String>,
    )
}
