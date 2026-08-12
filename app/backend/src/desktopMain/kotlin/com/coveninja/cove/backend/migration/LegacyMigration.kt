package com.coveninja.cove.backend.migration

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.db.Library_entries
import com.coveninja.cove.backend.db.Watch_progress
import com.coveninja.cove.backend.activity.ActivityDay
import com.coveninja.cove.backend.activity.ActivityDiskStore
import com.coveninja.cove.backend.addons.AddonEntry
import com.coveninja.cove.backend.addons.toModel
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveJson
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.io.path.name
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val IMPORT_KEY = "legacy_json_import_version"
private const val IMPORT_VERSION = "1"
private const val GLOBAL_PAYLOAD_SCOPE = ""
private val validProfileId = Regex("^[A-Za-z0-9_-]{1,64}$")

sealed interface MigrationResult {
    data object AlreadyImported : MigrationResult
    data class Imported(val profileCount: Int, val backupDirectory: Path) : MigrationResult
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
 * Imports the complete legacy JSON store as one database transaction. Files
 * are parsed and backed up before the transaction begins; a malformed sidecar
 * therefore leaves both the database and every source file untouched.
 */
class LegacyMigration(
    private val database: CoveDatabase,
    private val dataDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun importIfNeeded(): MigrationResult {
        val queries = database.coveQueries
        if (queries.selectMigrationMetadata(IMPORT_KEY).executeAsOneOrNull() == IMPORT_VERSION) {
            return MigrationResult.AlreadyImported
        }

        Files.createDirectories(dataDirectory)
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

            for (profile in profiles.profiles) {
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
                if (kind == "session") {
                    queries.upsertClientSession(json, Instant.now(clock).toString())
                }
            }
            queries.upsertMigrationMetadata(IMPORT_KEY, IMPORT_VERSION)
            queries.upsertMigrationMetadata("legacy_json_backup", backup.toString())
            queries.upsertMigrationMetadata("legacy_json_imported_at", Instant.now(clock).toString())
        }
        return MigrationResult.Imported(profiles.profiles.size, backup)
    }

    private fun loadProfiles(): LegacyProfilesDocument {
        val path = dataDirectory.resolve("profiles.json")
        val document = if (Files.exists(path)) {
            CoveJson.decodeFromString<LegacyProfilesDocument>(Files.readString(path))
        } else {
            val primary = LegacyProfile(newId(), "Primary", isPrimary = true)
            LegacyProfilesDocument(listOf(primary), primary.id)
        }
        require(document.profiles.isNotEmpty()) { "profiles.json contains no profiles" }
        require(document.profiles.map { it.id }.toSet().size == document.profiles.size) {
            "profiles.json contains duplicate profile ids"
        }
        document.profiles.forEach {
            require(validProfileId.matches(it.id)) { "invalid profile id ${it.id}" }
            require(it.name.isNotBlank()) { "profile ${it.id} has an empty name" }
        }

        val firstPrimary = document.profiles.indexOfFirst(LegacyProfile::isPrimary).let { if (it < 0) 0 else it }
        val normalized = document.profiles.mapIndexed { index, profile ->
            profile.copy(isPrimary = index == firstPrimary)
        }
        val active = document.activeProfileId.takeIf { id -> normalized.any { it.id == id } }
            ?: normalized[firstPrimary].id
        return LegacyProfilesDocument(normalized, active)
    }

    private fun readSettings(profileId: String, isPrimary: Boolean): AppSettings {
        val path = profilePath("settings", profileId, isPrimary)
        return if (path == null) AppSettings()
        else CoveJson.decodeFromString(Files.readString(path))
    }

    private fun readLibrary(profileId: String, isPrimary: Boolean): LegacyLibraryDocument {
        val path = profilePath("library", profileId, isPrimary) ?: return LegacyLibraryDocument()
        return CoveJson.decodeFromString(Files.readString(path))
    }

    private fun profilePath(kind: String, profileId: String, allowUnscoped: Boolean): Path? {
        val scoped = dataDirectory.resolve("$kind-$profileId.json")
        if (Files.exists(scoped)) return scoped
        if (!allowUnscoped) return null
        val legacy = dataDirectory.resolve("$kind.json")
        return legacy.takeIf(Files::exists)
    }

    private fun readOpaqueProfilePayloads(profileId: String, isPrimary: Boolean): Map<String, String> =
        listOf("activity", "addons", "nuvio", "trakt").mapNotNull { kind ->
            profilePath(kind, profileId, isPrimary)?.let { kind to validatedJson(it) }
        }.toMap()

    private fun readGlobalPayloads(): Map<String, String> =
        listOf("session").mapNotNull { kind ->
            dataDirectory.resolve("$kind.json").takeIf(Files::exists)?.let { kind to validatedJson(it) }
        }.toMap()

    private fun validatedJson(path: Path): String = Files.readString(path).also {
        CoveJson.parseToJsonElement(it)
    }

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
        document.dismissed.values.filterNotNull().forEach {
            queries.upsertDismissal(profileId, it.tmdbId.toLong(), it.mediaType, it.dismissedAt)
        }
        document.removed.values.filterNotNull().forEach {
            queries.upsertRemoval(profileId, it.tmdbId.toLong(), it.mediaType, it.removedAt)
        }
    }

    private fun createBackup(): Path {
        val backup = dataDirectory.resolve("migration-backup-v1-${clock.millis()}")
        Files.createDirectories(backup)
        Files.list(dataDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }
                .forEach { Files.copy(it, backup.resolve(it.name), StandardCopyOption.COPY_ATTRIBUTES) }
        }
        return backup
    }

    private data class ParsedProfile(
        val settings: AppSettings,
        val library: LegacyLibraryDocument,
        val opaque: Map<String, String>,
    )
}

internal fun atomicWrite(path: Path, text: String) {
    path.parent?.let(Files::createDirectories)
    val temporary = path.resolveSibling("${path.fileName}.tmp-${UUID.randomUUID()}")
    Files.writeString(temporary, text)
    try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Writes the current database into the legacy profile-scoped files before a
 * user needs to inspect or recover the pre-migration JSON representation. */
class LegacyExporter(
    private val database: CoveDatabase,
    private val dataDirectory: Path,
) {
    fun export() {
        val queries = database.coveQueries
        Files.createDirectories(dataDirectory)
        val profiles = queries.selectProfiles().executeAsList()
        val activeId = requireNotNull(queries.selectActiveProfileId().executeAsOneOrNull())
        atomicWrite(
            dataDirectory.resolve("profiles.json"),
            CoveJson.encodeToString(
                LegacyProfilesDocument(
                    profiles = profiles.map {
                        LegacyProfile(
                            id = it.id,
                            name = it.name,
                            isPrimary = it.is_primary != 0L,
                            supabaseUid = it.supabase_uid,
                            nameUpdatedAt = it.name_updated_at,
                        )
                    },
                    activeProfileId = activeId,
                ),
            ),
        )

        profiles.forEach { profile ->
            val settings = queries.selectSettings(profile.id).executeAsOneOrNull()
                ?: CoveJson.encodeToString(AppSettings())
            atomicWrite(dataDirectory.resolve("settings-${profile.id}.json"), settings)

            val entries = queries.selectLibraryEntries(profile.id).executeAsList()
                .associate { row -> "${row.tmdb_id}:${row.media_type}" to row.toLegacyModel() }
            val progress = queries.selectWatchProgress(profile.id).executeAsList()
                .associate { row -> row.progress_key to row.toLegacyModel() }
            val dismissals = queries.selectDismissals(profile.id).executeAsList()
                .associate { row ->
                    "${row.tmdb_id}:${row.media_type}" to LegacyDismissal(
                        row.tmdb_id.toInt(), row.media_type, row.dismissed_at,
                    )
                }
            val removals = queries.selectRemovals(profile.id).executeAsList()
                .associate { row ->
                    "${row.tmdb_id}:${row.media_type}" to LegacyRemoval(
                        row.tmdb_id.toInt(), row.media_type, row.removed_at,
                    )
                }
            atomicWrite(
                dataDirectory.resolve("library-${profile.id}.json"),
                CoveJson.encodeToString(LegacyLibraryDocument(entries, progress, dismissals, removals)),
            )
            exportActivity(profile.id)
            exportAddons(profile.id)
            exportNuvio(profile.id)
            exportTrakt(profile.id)
        }

        queries.selectLegacyPayloads().executeAsList().forEach { payload ->
            if (payload.profile_id != GLOBAL_PAYLOAD_SCOPE && payload.kind in PORTED_PROFILE_PAYLOADS) {
                return@forEach
            }
            val filename = if (payload.profile_id == GLOBAL_PAYLOAD_SCOPE) {
                "${payload.kind}.json"
            } else {
                "${payload.kind}-${payload.profile_id}.json"
            }
            atomicWrite(dataDirectory.resolve(filename), payload.json)
        }
        queries.selectClientSession().executeAsOneOrNull()?.let { json ->
            atomicWrite(dataDirectory.resolve("session.json"), json)
        }
    }

    private fun exportActivity(profileId: String) {
        val queries = database.coveQueries
        val state = queries.selectActivityState(profileId).executeAsOneOrNull()
        if (state == null) return exportLegacyPayload(profileId, "activity")
        val days = linkedMapOf<String, MutableExportActivityDay>()
        queries.selectActivityHours(profileId).executeAsList().forEach { row ->
            val hour = row.hour.toInt()
            if (hour in 0..23) days.getOrPut(row.date, ::MutableExportActivityDay).byHour[hour] = row.seconds
        }
        queries.selectActivityTitles(profileId).executeAsList().forEach { row ->
            days.getOrPut(row.date, ::MutableExportActivityDay).byTitle[row.title_key] = row.seconds
        }
        atomicWrite(
            dataDirectory.resolve("activity-$profileId.json"),
            CoveJson.encodeToString(ActivityDiskStore(
                days = days.mapValues { (_, day) -> ActivityDay(day.byHour, day.byTitle) },
                lastPos = queries.selectActivityPositions(profileId).executeAsList()
                    .associate { it.progress_key to it.position },
                backfilled = state.backfilled != 0L,
            )),
        )
    }

    private fun exportAddons(profileId: String) {
        val queries = database.coveQueries
        val rows = queries.selectAddons(profileId).executeAsList()
        if (rows.isEmpty()) return exportLegacyPayload(profileId, "addons")
        val entries = rows.map { it.toModel() }
        atomicWrite(
            dataDirectory.resolve("addons-$profileId.json"),
            CoveJson.encodeToString(ExportAddonStore(
                stremioAddons = entries.filter { it.source == "stremio" },
                officialEnabled = entries.filter { it.source == "official" }.associate { it.id to it.enabled },
                updatedAt = queries.selectProfileStoreVersion(profileId, "addons").executeAsOneOrNull().orEmpty(),
            )),
        )
    }

    private fun exportNuvio(profileId: String) {
        val row = database.coveQueries.selectNuvioState(profileId).executeAsOneOrNull()
            ?: return exportLegacyPayload(profileId, "nuvio")
        atomicWrite(dataDirectory.resolve("nuvio-$profileId.json"), row.json)
    }

    private fun exportTrakt(profileId: String) {
        val row = database.coveQueries.selectTraktSession(profileId).executeAsOneOrNull()
            ?: return exportLegacyPayload(profileId, "trakt")
        atomicWrite(
            dataDirectory.resolve("trakt-$profileId.json"),
            CoveJson.encodeToString(ExportTraktSession(
                accessToken = row.access_token,
                refreshToken = row.refresh_token,
                expiresAt = row.expires_at.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
                    ?: "0001-01-01T00:00:00Z",
                username = row.username,
                lastSyncAt = row.last_sync_at.ifBlank { "0001-01-01T00:00:00Z" },
            )),
        )
    }

    private fun exportLegacyPayload(profileId: String, kind: String) {
        database.coveQueries.selectLegacyPayloadRecord(profileId, kind).executeAsOneOrNull()?.let {
            atomicWrite(dataDirectory.resolve("$kind-$profileId.json"), it.json)
        }
    }
}

private class MutableExportActivityDay(
    val byHour: MutableList<Long> = MutableList(24) { 0 },
    val byTitle: MutableMap<String, Long> = linkedMapOf(),
)

@Serializable
private data class ExportAddonStore(
    val stremioAddons: List<AddonEntry> = emptyList(),
    val officialEnabled: Map<String, Boolean> = emptyMap(),
    val updatedAt: String = "",
)

@Serializable
private data class ExportTraktSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: String,
    val username: String,
    @SerialName("last_sync_at") val lastSyncAt: String,
)

private val PORTED_PROFILE_PAYLOADS = setOf("activity", "addons", "nuvio", "trakt")

private fun Library_entries.toLegacyModel(): LibraryEntry = LibraryEntry(
    id = id,
    profileId = profile_id,
    tmdbId = tmdb_id.toInt(),
    mediaType = com.coveninja.cove.shared.model.MediaType.entries.first { it.wireName == media_type },
    title = title,
    posterPath = poster_path,
    status = com.coveninja.cove.shared.model.LibraryStatus.entries.first { it.wireName == status },
    rating = rating,
    voteAverage = vote_average,
    lastAirDate = last_air_date,
    lastWatchedAt = last_watched_at,
    lastWatchedSeason = last_watched_season?.toInt(),
    lastWatchedEpisode = last_watched_episode?.toInt(),
    lastAiredSeason = last_aired_season?.toInt(),
    lastAiredEpisode = last_aired_episode?.toInt(),
    addedAt = added_at,
    updatedAt = updated_at,
)

private fun Watch_progress.toLegacyModel(): WatchProgress = WatchProgress(
    id = id,
    profileId = profile_id,
    libraryEntryId = library_entry_id,
    tmdbId = tmdb_id.toInt(),
    mediaType = com.coveninja.cove.shared.model.MediaType.entries.first { it.wireName == media_type },
    season = season?.toInt(),
    episode = episode?.toInt(),
    positionSeconds = position_seconds,
    durationSeconds = duration_seconds,
    completed = completed != 0L,
    watchedAt = watched_at,
)
