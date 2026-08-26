package com.coveninja.cove.backend.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopDatabaseMigrationTest {
    @Test
    fun versionThreeDatabaseMigratesAuthAndSessionTablesWithoutLosingData() {
        val path = Files.createTempDirectory("cove-schema-migration").resolve("cove.db")
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            CoveDatabase.Schema.create(driver)
            // A version-3 database is faked by creating the current schema and dropping
            // everything the later migrations add — so every new migration has to drop
            // its table here too, or that migration re-creates a table that exists.
            driver.execute(null, "DROP TABLE insights_cache", 0)
            driver.execute(null, "DROP TABLE title_plays", 0)
            driver.execute(null, "DROP TABLE calendar_cache", 0)
            driver.execute(null, "DROP TABLE auth_session", 0)
            driver.execute(null, "DROP TABLE client_session", 0)
            driver.execute(null, "DROP TABLE profile_store_versions", 0)
            driver.execute(null, "DROP TABLE nuvio_state", 0)
            driver.execute(null, "DROP TABLE activity_hours", 0)
            driver.execute(null, "DROP TABLE activity_titles", 0)
            driver.execute(null, "DROP TABLE activity_positions", 0)
            driver.execute(null, "DROP TABLE activity_state", 0)
            driver.execute(null, "DROP TABLE tracker_sessions", 0)
            driver.execute(null, "DROP TABLE external_id_map", 0)
            driver.execute(null, "DROP TABLE profile_removals", 0)
            driver.execute(null, "DROP TABLE track_memory", 0)
            driver.execute(null, "PRAGMA user_version = 3", 0)
            driver.execute(
                null,
                "INSERT INTO migration_metadata(key, value) VALUES ('sentinel', 'preserved')",
                0,
            )
        }

        DesktopDatabase.open(path).use { migrated ->
            assertEquals("preserved", migrated.database.coveQueries
                .selectMigrationMetadata("sentinel").executeAsOne())
            migrated.database.coveQueries.upsertAuthSession(
                "user", "", "access", "refresh", null, "now",
            )
            assertNotNull(migrated.database.coveQueries.selectAuthSession().executeAsOneOrNull())
        }
    }

    /**
     * Migration 11 folds `trakt_sessions` into the per-provider `tracker_sessions`.
     *
     * Faked at the version right before it rather than at 3, because the row has to exist
     * *before* the copy runs and a version-3 database reaches this migration with a
     * `trakt_sessions` that migration 7 has only just created empty. The rows are live
     * OAuth tokens: losing one to a schema change signs the viewer out of an account they
     * never asked to leave, and says nothing while doing it.
     */
    @Test
    fun previousVersionCarriesTraktSessionsIntoTheTrackerTable() {
        val path = Files.createTempDirectory("cove-tracker-migration").resolve("cove.db")
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            CoveDatabase.Schema.create(driver)
            driver.execute(null, "DROP TABLE tracker_sessions", 0)
            driver.execute(null, "DROP TABLE external_id_map", 0)
            // Migrating from 11 also runs everything after it, so every table a later
            // migration creates has to go here too — 12's, and each one added after it.
            driver.execute(null, "DROP TABLE track_memory", 0)
            driver.execute(
                null,
                "CREATE TABLE trakt_sessions (" +
                    "profile_id TEXT NOT NULL PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE," +
                    "access_token TEXT NOT NULL," +
                    "refresh_token TEXT NOT NULL DEFAULT ''," +
                    "expires_at INTEGER NOT NULL DEFAULT 0," +
                    "username TEXT NOT NULL DEFAULT ''," +
                    "last_sync_at TEXT NOT NULL DEFAULT '')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO profiles(id, name, is_primary) VALUES ('p1', 'Primary', 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO trakt_sessions(profile_id, access_token, refresh_token, " +
                    "expires_at, username, last_sync_at) VALUES " +
                    "('p1', 'legacy-access', 'legacy-refresh', 99, 'legacy-user', '2026-01-01T00:00:00Z')",
                0,
            )
            // 11.sqm is the migration under test; SQLDelight runs it for oldVersion <= 11.
            driver.execute(null, "PRAGMA user_version = 11", 0)
        }

        DesktopDatabase.open(path).use { migrated ->
            // Mutation check: drop the INSERT..SELECT from 11.sqm and every assertion
            // below fails on a null row.
            val adopted = migrated.database.coveQueries
                .selectTrackerSession("p1", "trakt").executeAsOneOrNull()
            assertNotNull(adopted)
            assertEquals("legacy-access", adopted.access_token)
            assertEquals("legacy-refresh", adopted.refresh_token)
            assertEquals(99L, adopted.expires_at)
            assertEquals("legacy-user", adopted.username)
            assertEquals("2026-01-01T00:00:00Z", adopted.last_sync_at)
            // The same profile can now hold a second provider, which is the whole point.
            migrated.database.coveQueries.upsertTrackerSession(
                "p1", "simkl", "simkl-access", "4242", 0, "simkl-user", "",
            )
            assertEquals(
                "simkl-access",
                migrated.database.coveQueries.selectTrackerSession("p1", "simkl")
                    .executeAsOne().access_token,
            )
        }
    }
}
