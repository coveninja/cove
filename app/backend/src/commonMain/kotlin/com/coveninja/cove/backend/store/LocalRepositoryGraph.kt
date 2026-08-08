package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import kotlinx.coroutines.CoroutineScope

/**
 * Platform-neutral assembly for the repositories consumed by the shared UI.
 * Desktop and Android only provide a SQLDelight driver plus platform sources
 * for identifiers, timestamps, and secure random tokens.
 */
class LocalRepositoryGraph internal constructor(
    val profileSession: ActiveProfileSession,
    val profiles: LocalProfileRepository,
    val library: LocalLibraryRepository,
    val settings: LocalSettingsRepository,
)

fun createLocalRepositoryGraph(
    database: CoveDatabase,
    scope: CoroutineScope,
    newId: () -> String,
    now: () -> String,
    newRemoteToken: () -> String,
): LocalRepositoryGraph {
    val session = ActiveProfileSession(database)
    return LocalRepositoryGraph(
        profileSession = session,
        profiles = LocalProfileRepository(database, session, newId, now),
        library = LocalLibraryRepository(database, session, scope, newId, now),
        settings = LocalSettingsRepository(database, session, scope, now, newRemoteToken),
    )
}
