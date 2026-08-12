package com.coveninja.cove.backend

import android.content.Context
import com.coveninja.cove.backend.db.AndroidDatabase
import com.coveninja.cove.backend.migration.AndroidLegacyMigration
import com.coveninja.cove.backend.migration.AndroidMigrationResult
import com.coveninja.cove.backend.store.LocalRepositoryGraph
import com.coveninja.cove.backend.store.ProgressEventBus
import com.coveninja.cove.backend.store.createLocalRepositoryGraph
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AndroidStoreGraph private constructor(
    private val database: AndroidDatabase,
    private val scope: CoroutineScope,
    val repositories: LocalRepositoryGraph,
    val migrationResult: AndroidMigrationResult,
    internal val now: () -> String,
) : AutoCloseable {
    internal val databaseHandle get() = database.database
    internal val progressEvents: ProgressEventBus get() = repositories.progressEvents

    override fun close() {
        scope.cancel()
        database.close()
    }

    companion object {
        fun open(context: Context): AndroidStoreGraph {
            val appContext = context.applicationContext
            val database = AndroidDatabase.open(appContext)
            try {
                val now = { Instant.now().toString() }
                val migration = AndroidLegacyMigration(
                    database = database.database,
                    dataDirectory = appContext.filesDir,
                    now = now,
                ).importIfNeeded()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val random = SecureRandom()
                val repositories = createLocalRepositoryGraph(
                    database = database.database,
                    scope = scope,
                    newId = { UUID.randomUUID().toString() },
                    now = now,
                    newRemoteToken = {
                        ByteArray(32).also(random::nextBytes)
                            .joinToString("") { byte -> "%02x".format(byte) }
                    },
                )
                return AndroidStoreGraph(database, scope, repositories, migration, now)
            } catch (error: Throwable) {
                database.close()
                throw error
            }
        }
    }
}
