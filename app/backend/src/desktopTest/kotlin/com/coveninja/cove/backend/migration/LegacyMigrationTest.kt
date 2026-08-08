package com.coveninja.cove.backend.migration

import com.coveninja.cove.backend.db.DesktopDatabase
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegacyMigrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun firstRunCreatesPrimaryProfileAndIsIdempotent() {
        val dir = Files.createTempDirectory("cove-migration-first-run")
        DesktopDatabase.inMemory().use { store ->
            val migration = LegacyMigration(store.database, dir, clock) { "primary-id" }
            val imported = assertIs<MigrationResult.Imported>(migration.importIfNeeded())
            assertEquals(1, imported.profileCount)
            assertTrue(imported.backupDirectory.exists())
            assertEquals("primary-id", store.database.coveQueries.selectActiveProfileId().executeAsOne())
            assertEquals("Primary", store.database.coveQueries.selectProfiles().executeAsOne().name)
            assertIs<MigrationResult.AlreadyImported>(migration.importIfNeeded())
        }
    }

    @Test
    fun importsEveryProfileScopedStoreAndExportsCurrentDatabase() {
        val dir = Files.createTempDirectory("cove-migration-roundtrip")
        Files.writeString(
            dir.resolve("profiles.json"),
            """{
              "profiles":[
                {"id":"one","name":"One","is_primary":true,"name_updated_at":"2026-01-01T00:00:00Z"},
                {"id":"two","name":"Two","is_primary":false}
              ],
              "active_profile_id":"two"
            }""".trimIndent(),
        )
        Files.writeString(
            dir.resolve("settings-one.json"),
            """{"defaultVolume":0.4,"rememberPosition":false,"remoteAccessToken":"paired"}""",
        )
        Files.writeString(
            dir.resolve("library-one.json"),
            """{
              "entries":{"42:movie":{
                "id":"entry-1","profile_id":"one","tmdb_id":42,"media_type":"movie",
                "title":"Movie","poster_path":"/poster.jpg","status":"finished","rating":4.5,
                "vote_average":8.2,"added_at":"2026-01-01T00:00:00Z","updated_at":"2026-02-01T00:00:00Z"
              }},
              "progress":{"42:movie":{
                "id":"progress-1","profile_id":"one","library_entry_id":"entry-1","tmdb_id":42,
                "media_type":"movie","position_seconds":100.0,"duration_seconds":100.0,
                "completed":true,"watched_at":"2026-02-01T00:00:00Z"
              }},
              "dismissed":{"7:tv":{"tmdb_id":7,"media_type":"tv","dismissed_at":"2026-02-02T00:00:00Z"}},
              "removed":{"8:movie":{"tmdb_id":8,"media_type":"movie","removed_at":"2026-02-03T00:00:00Z"}}
            }""".trimIndent(),
        )
        Files.writeString(dir.resolve("addons-one.json"), """{"stremioAddons":[]}""")
        Files.writeString(dir.resolve("session.json"), """{"access_token":"secret"}""")

        DesktopDatabase.inMemory().use { store ->
            assertIs<MigrationResult.Imported>(LegacyMigration(store.database, dir, clock).importIfNeeded())
            val queries = store.database.coveQueries
            assertEquals("two", queries.selectActiveProfileId().executeAsOne())
            assertEquals(2, queries.selectProfiles().executeAsList().size)
            assertEquals(0.4, queries.selectSettings("one").executeAsOne().let {
                com.coveninja.cove.shared.network.CoveJson
                    .decodeFromString<com.coveninja.cove.shared.model.AppSettings>(it).defaultVolume
            })
            assertEquals("Movie", queries.selectLibraryEntries("one").executeAsOne().title)
            assertEquals(1L, queries.selectWatchProgress("one").executeAsOne().completed)
            assertEquals(1, queries.selectDismissals("one").executeAsList().size)
            assertEquals(1, queries.selectRemovals("one").executeAsList().size)
            assertEquals("""{"stremioAddons":[]}""", queries.selectLegacyPayload("one", "addons").executeAsOne())
            assertEquals("""{"access_token":"secret"}""", queries.selectLegacyPayload("", "session").executeAsOne())
            assertEquals("""{"access_token":"secret"}""", queries.selectClientSession().executeAsOne())

            LegacyExporter(store.database, dir).export()
            assertTrue(Files.readString(dir.resolve("profiles.json")).contains("\"active_profile_id\":\"two\""))
            assertTrue(Files.readString(dir.resolve("library-one.json")).contains("\"entry-1\""))
            assertTrue(Files.readString(dir.resolve("settings-one.json")).contains("\"defaultVolume\":0.4"))
        }
    }

    @Test
    fun malformedSidecarRollsBackBeforeWritingDatabase() {
        val dir = Files.createTempDirectory("cove-migration-corrupt")
        Files.writeString(
            dir.resolve("profiles.json"),
            """{"profiles":[{"id":"one","name":"One","is_primary":true}],"active_profile_id":"one"}""",
        )
        Files.writeString(dir.resolve("activity-one.json"), "{")

        DesktopDatabase.inMemory().use { store ->
            assertFailsWith<Exception> { LegacyMigration(store.database, dir, clock).importIfNeeded() }
            assertEquals(0, store.database.coveQueries.selectProfiles().executeAsList().size)
            assertEquals(null, store.database.coveQueries.selectMigrationMetadata("legacy_json_import_version").executeAsOneOrNull())
        }
    }

    @Test
    fun unscopedLegacyFilesBelongOnlyToPrimaryProfile() {
        val dir = Files.createTempDirectory("cove-migration-unscoped")
        Files.writeString(
            dir.resolve("profiles.json"),
            """{"profiles":[{"id":"one","name":"One","is_primary":true},{"id":"two","name":"Two"}],"active_profile_id":"two"}""",
        )
        Files.writeString(dir.resolve("settings.json"), """{"defaultVolume":0.4}""")
        Files.writeString(dir.resolve("addons.json"), """{"stremioAddons":["primary-only"]}""")

        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir, clock).importIfNeeded()
            val queries = store.database.coveQueries
            val primary = com.coveninja.cove.shared.network.CoveJson.decodeFromString<com.coveninja.cove.shared.model.AppSettings>(
                queries.selectSettings("one").executeAsOne(),
            )
            val child = com.coveninja.cove.shared.network.CoveJson.decodeFromString<com.coveninja.cove.shared.model.AppSettings>(
                queries.selectSettings("two").executeAsOne(),
            )
            assertEquals(0.4, primary.defaultVolume)
            assertEquals(1.0, child.defaultVolume)
            assertTrue(queries.selectLegacyPayload("one", "addons").executeAsOne().contains("primary-only"))
            assertEquals(null, queries.selectLegacyPayload("two", "addons").executeAsOneOrNull())
        }
    }

    @Test
    fun exporterUsesCurrentStructuredStoresInsteadOfStaleImportedPayloads() {
        val dir = Files.createTempDirectory("cove-migration-structured-export")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir, clock) { "primary" }.importIfNeeded()
            val queries = store.database.coveQueries
            queries.upsertActivityHour("primary", "2026-08-08", 12, 45)
            queries.upsertActivityTitle("primary", "2026-08-08", "42:movie", 45)
            queries.upsertActivityPosition("primary", "42:movie", 45.0)
            queries.upsertActivityState("primary", 1, 1)
            queries.upsertAddon(
                profile_id = "primary",
                url = "https://addon.test",
                addon_id = "provider",
                manifest_json = """{"id":"provider","name":"Provider"}""",
                kind = "provider",
                source = "stremio",
                enabled = 1,
                disabled_catalogs_json = "{}",
                updated_at = "2026-08-08T12:00:00Z",
            )
            queries.upsertProfileStoreVersion("primary", "addons", "2026-08-08T12:00:00Z")
            queries.upsertNuvioState(
                "primary",
                """{"repos":[],"updatedAt":"2026-08-08T12:00:00Z"}""",
                "2026-08-08T12:00:00Z",
            )
            queries.upsertTraktSession(
                "primary",
                "access",
                "refresh",
                Instant.parse("2026-08-09T12:00:00Z").epochSecond,
                "viewer",
                "2026-08-08T11:00:00Z",
            )

            LegacyExporter(store.database, dir).export()

            assertTrue(Files.readString(dir.resolve("activity-primary.json")).contains("\"42:movie\":45"))
            assertTrue(Files.readString(dir.resolve("addons-primary.json")).contains("https://addon.test"))
            assertTrue(Files.readString(dir.resolve("nuvio-primary.json")).contains("2026-08-08"))
            assertTrue(Files.readString(dir.resolve("trakt-primary.json")).contains("\"access_token\":\"access\""))
        }
    }
}
