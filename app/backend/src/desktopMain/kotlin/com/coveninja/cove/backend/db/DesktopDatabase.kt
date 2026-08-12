package com.coveninja.cove.backend.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path

class DesktopDatabase private constructor(
    val database: CoveDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    override fun close() = driver.close()

    companion object {
        fun open(path: Path): DesktopDatabase {
            path.parent?.let(Files::createDirectories)
            val existed = Files.exists(path)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            driver.execute(null, "PRAGMA busy_timeout = 5000", 0)
            if (!existed || !tableExists(driver, "migration_metadata")) {
                CoveDatabase.Schema.create(driver)
                setSchemaVersion(driver, CoveDatabase.Schema.version)
            } else {
                val recorded = schemaVersion(driver)
                // Early Kotlin preview databases predated explicit user_version
                // management and are the v1 schema when the value is zero.
                val current = if (recorded == 0L) 1L else recorded
                if (current < CoveDatabase.Schema.version) {
                    CoveDatabase.Schema.migrate(driver, current, CoveDatabase.Schema.version)
                    setSchemaVersion(driver, CoveDatabase.Schema.version)
                }
            }
            return DesktopDatabase(CoveDatabase(driver), driver)
        }

        fun inMemory(): DesktopDatabase {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            CoveDatabase.Schema.create(driver)
            return DesktopDatabase(CoveDatabase(driver), driver)
        }

        private fun schemaVersion(driver: SqlDriver): Long = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            parameters = 0,
        ).value

        private fun setSchemaVersion(driver: SqlDriver, version: Long) {
            driver.execute(null, "PRAGMA user_version = $version", 0)
        }

        private fun tableExists(driver: SqlDriver, name: String): Boolean = driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor -> QueryResult.Value(cursor.next().value) },
            parameters = 1,
            binders = { bindString(0, name) },
        ).value
    }
}
