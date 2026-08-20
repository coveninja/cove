package com.coveninja.cove.backend.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class AndroidDatabase private constructor(
    val database: CoveDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    override fun close() = driver.close()

    companion object {
        /**
         * Android reads query results through a CursorWindow, and the platform default is
         * 2 MiB. A row that does not fit does not degrade — the read fails outright with
         * `SQLiteBlobTooBigException: Row too big to fit into CursorWindow`, which reaches
         * the viewer as whatever the caller was doing at the time.
         *
         * Several tables here deliberately hold a whole serialized document in one row:
         * `nuvio_state` carries the source of every scraper the profile has fetched,
         * `addons.manifest_json` a whole addon manifest, `legacy_payloads` whatever a
         * syncing peer stored. None of them is bounded by 2 MiB, so a phone with a couple
         * of scraper repos installed could not read its own database, and the failure
         * landed on the playback path — every attempt to play ended in "Nothing to play"
         * with the CursorWindow message underneath it. The desktop's JDBC driver has no
         * equivalent limit, which is why this only ever appeared on Android.
         *
         * The window is committed as it fills rather than up front, so the unused part of a
         * larger one costs address space rather than memory. SQLDelight applies it on API
         * 28 and above; that is also this app's minSdk, so every supported device gets it.
         */
        private const val CURSOR_WINDOW_BYTES = 16L * 1024 * 1024

        fun open(context: Context, name: String = "cove.db"): AndroidDatabase {
            val driver = AndroidSqliteDriver(
                schema = CoveDatabase.Schema,
                context = context.applicationContext,
                name = name,
                windowSizeBytes = CURSOR_WINDOW_BYTES,
            )
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            return AndroidDatabase(CoveDatabase(driver), driver)
        }
    }
}
