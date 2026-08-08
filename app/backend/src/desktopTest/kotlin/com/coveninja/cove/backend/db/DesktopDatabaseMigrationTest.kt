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
            driver.execute(null, "DROP TABLE auth_session", 0)
            driver.execute(null, "DROP TABLE client_session", 0)
            driver.execute(null, "DROP TABLE profile_store_versions", 0)
            driver.execute(null, "DROP TABLE nuvio_state", 0)
            driver.execute(null, "DROP TABLE activity_hours", 0)
            driver.execute(null, "DROP TABLE activity_titles", 0)
            driver.execute(null, "DROP TABLE activity_positions", 0)
            driver.execute(null, "DROP TABLE activity_state", 0)
            driver.execute(null, "DROP TABLE trakt_sessions", 0)
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
}
