package com.coveninja.cove.backend

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.migration.LegacyExporter
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.migration.MigrationResult
import com.coveninja.cove.backend.http.CoreRouteServices
import com.coveninja.cove.backend.http.LocalBackendHost
import com.coveninja.cove.backend.http.MediaBoundary
import com.coveninja.cove.backend.auth.AuthService
import com.coveninja.cove.backend.auth.ClientSessionStore
import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.calendar.CalendarService
import com.coveninja.cove.backend.trakt.TraktService
import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.quality.QualityLookup
import com.coveninja.cove.backend.updater.UpdateService
import com.coveninja.cove.backend.prefetch.PrefetchService
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.platform.DesktopConfigPaths
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalRepositoryGraph
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.backend.store.ProgressEventBus
import com.coveninja.cove.backend.store.createLocalRepositoryGraph
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class LocalStoreGraph private constructor(
    private val database: DesktopDatabase,
    private val scope: CoroutineScope,
    private val repositories: LocalRepositoryGraph,
    internal val now: () -> String,
    val migrationResult: MigrationResult,
    private val exporter: LegacyExporter,
) : AutoCloseable {
    internal val profileSession: ActiveProfileSession get() = repositories.profileSession
    val profiles: LocalProfileRepository get() = repositories.profiles
    val library: LocalLibraryRepository get() = repositories.library
    val settings: LocalSettingsRepository get() = repositories.settings
    val progressEvents: ProgressEventBus get() = repositories.progressEvents
    internal val databaseHandle get() = database.database
    fun exportLegacyFallback() = exporter.export()

    fun createHttpHost(
        catalog: MediaCatalog? = null,
        addons: AddonManager? = null,
        nuvio: NuvioManager? = null,
        media: MediaBoundary? = null,
        auth: AuthService? = null,
        clientSessions: ClientSessionStore? = null,
        activity: ActivityService? = null,
        calendar: CalendarService? = null,
        trakt: TraktService? = null,
        deviceSettings: DeviceSettingsService? = null,
        discovery: DiscoveryService? = null,
        quality: QualityLookup? = null,
        updater: UpdateService? = null,
        prefetch: PrefetchService? = null,
        host: String = "127.0.0.1",
        port: Int = 6969,
        remoteHost: String = "0.0.0.0",
        remotePort: Int = port + 1,
    ): LocalBackendHost = LocalBackendHost(
        CoreRouteServices(
            profiles,
            settings,
            library,
            catalog,
            addons,
            nuvio,
            media,
            auth,
            clientSessions,
            activity,
            calendar,
            trakt,
            deviceSettings,
            discovery,
            quality,
            updater,
            prefetch,
        ),
        host,
        port,
        remoteHost,
        remotePort,
    )

    override fun close() {
        scope.cancel()
        database.close()
    }

    companion object {
        fun open(
            dataDirectory: Path = DesktopConfigPaths.dataDirectory(),
            clock: Clock = Clock.systemUTC(),
        ): LocalStoreGraph {
            val database = DesktopDatabase.open(dataDirectory.resolve("cove.db"))
            try {
                val migration = LegacyMigration(database.database, dataDirectory, clock)
                    .importIfNeeded()
                val scope = backendScope("stores")
                val now = { Instant.now(clock).toString() }
                val random = SecureRandom()
                val token = {
                    ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                }
                val repositories = createLocalRepositoryGraph(
                    database = database.database,
                    scope = scope,
                    newId = { UUID.randomUUID().toString() },
                    now = now,
                    newRemoteToken = token,
                )
                return LocalStoreGraph(
                    database = database,
                    scope = scope,
                    repositories = repositories,
                    now = now,
                    migrationResult = migration,
                    exporter = LegacyExporter(database.database, dataDirectory),
                )
            } catch (error: Throwable) {
                database.close()
                throw error
            }
        }
    }
}
