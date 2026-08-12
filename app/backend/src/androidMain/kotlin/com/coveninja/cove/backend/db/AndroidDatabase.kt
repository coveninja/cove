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
        fun open(context: Context, name: String = "cove.db"): AndroidDatabase {
            val driver = AndroidSqliteDriver(
                schema = CoveDatabase.Schema,
                context = context.applicationContext,
                name = name,
            )
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            return AndroidDatabase(CoveDatabase(driver), driver)
        }
    }
}
