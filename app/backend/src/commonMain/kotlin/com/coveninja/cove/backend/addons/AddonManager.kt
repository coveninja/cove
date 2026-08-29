package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.db.Addons as AddonRow
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.db.Profiles as ProfileRow
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.data.AddonSharing
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import kotlin.time.Clock

private const val MAX_ADDON_RESPONSE_CHARS = 20 * 1024 * 1024
private const val ADDON_REQUEST_TIMEOUT_MS = 15_000L

class AddonManager(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val httpClient: HttpClient,
    private val now: () -> String,
    private val tmdbApiKey: String = "",
    private val imdbLookup: suspend (Int) -> String? = { null },
    private val introDbBaseUrl: String = "https://api.theintrodb.org/v3",
    private val introDbAppBaseUrl: String = "https://api.introdb.app",
    private val tmdbBaseUrl: String = "https://api.themoviedb.org/3",
    private val urlPolicy: AddonUrlPolicy = BasicAddonUrlPolicy,
    private val onProviderError: (AddonEntry, Throwable) -> Unit = { _, _ -> },
) {
    private val streamCacheMutex = Mutex()
    private val streamCache = mutableMapOf<String, CachedAddonStreams>()

    /**
     * Every addon this profile resolves streams and catalogs through: the ones it
     * owns, plus the primary profile's when that profile shares them.
     *
     * This is the single funnel — the settings screen, [streams], [subtitles] and
     * [enabledCatalogs] all read it — which is the whole reason inheritance is
     * resolved here and nowhere else. Note that [snapshotForSync] deliberately
     * does *not* come through here: a secondary that pushed the primary's addons
     * under its own profile would carry a newer timestamp than every other device
     * and duplicate them across the account.
     */
    suspend fun entries(): List<AddonEntry> {
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        ensureOfficialEntries(profileId)
        val own = database.coveQueries.selectAddons(profileId).executeAsList().map(AddonRow::toModel)
        // sortedBy is stable, so within each group this keeps selectAddons' rowid
        // order — registration order for installed addons, seed order for the
        // built-in ones. That only holds because persist() updates rows in place;
        // see the note on insertAddonIfMissing in Cove.sq. Inherited addons sort
        // ahead of this profile's own, which is also the order providers are
        // asked in: the household's shared sources answer first.
        return (own + inheritedEntries(profileId, own)).sortedBy { entry ->
            when {
                entry.source == "official" -> 0
                entry.managed -> 1
                else -> 2
            }
        }
    }

    /** How the active profile relates to the primary's shared addon list. */
    fun sharing(): AddonSharing {
        val primary = primaryProfile() ?: return AddonSharing()
        return AddonSharing(
            enabled = policySetOn(primary.id),
            editable = primary.id == session.profileId.value,
            primaryName = primary.name,
        )
    }

    /**
     * The primary's shareable addons, minus any URL [own] already holds — the
     * profile's own row wins, so switching the policy off later leaves an addon
     * it installed for itself still working and still editable.
     */
    private fun inheritedEntries(profileId: String, own: List<AddonEntry>): List<AddonEntry> {
        val primary = inheritedFrom(profileId) ?: return emptyList()
        ensureLegacyImported(primary.id)
        val ownUrls = own.mapTo(mutableSetOf(), AddonEntry::url)
        return database.coveQueries.selectAddons(primary.id).executeAsList().map(AddonRow::toModel)
            // Only pasted-in addons are shared. JustWatch and IntroDB are seeded
            // into every profile already, and each keeps its own on/off switch.
            .filter { it.source == "stremio" && it.url !in ownUrls }
            .map { it.copy(managed = true) }
    }

    /**
     * Everything that decides whether a cached addon answer is still good, and
     * nothing that says what was asked. Callers append their own question to it.
     *
     * An addon the primary adds or disables never touches this profile's own store
     * version, so without the primary's in the key an inheriting profile would keep
     * serving a stale answer for the whole cache window. It also covers the policy
     * itself being switched: this collapses to "" the moment inheritance stops,
     * which is a different key either way.
     *
     * Shared by [streams] and by the catalog cache in [AddonCatalogService], which
     * is why it is visible beyond this class — the two must agree on what staleness
     * means, and a second copy of this reasoning would be the thing that drifts.
     */
    fun cacheToken(): String {
        val profileId = session.profileId.value
        val storeVersion = database.coveQueries.selectProfileStoreVersion(profileId, "addons")
            .executeAsOneOrNull().orEmpty()
        val inheritedVersion = inheritedFrom(profileId)?.let { primary ->
            database.coveQueries.selectProfileStoreVersion(primary.id, "addons").executeAsOneOrNull()
        }.orEmpty()
        return "$profileId|$storeVersion|$inheritedVersion"
    }

    /**
     * The profile [profileId] inherits addons from, or null when it inherits
     * none — because it *is* the primary, because there is no primary, or
     * because the primary has not switched sharing on.
     */
    private fun inheritedFrom(profileId: String): ProfileRow? {
        val primary = primaryProfile() ?: return null
        if (primary.id == profileId) return null
        return primary.takeIf { policySetOn(it.id) }
    }

    private fun primaryProfile(): ProfileRow? =
        // selectProfiles already orders is_primary DESC, but filtering says what
        // is meant rather than leaning on the ordering of a shared query.
        database.coveQueries.selectProfiles().executeAsList().firstOrNull { it.is_primary != 0L }

    /**
     * Reads the policy off the primary's own settings row. A settings row that
     * fails to decode means no sharing rather than no addons at all — this sits
     * underneath every stream resolution, so it must not be able to throw.
     */
    private fun policySetOn(primaryId: String): Boolean =
        database.coveQueries.selectSettings(primaryId).executeAsOneOrNull()
            ?.let { runCatching { CoveJson.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?.addonsFollowPrimary == true

    suspend fun add(rawUrl: String): AddonEntry {
        val url = normalizeAddonUrl(rawUrl)
        urlPolicy.validate(url)
        val manifest = fetchManifest(url)
        val entry = AddonEntry(
            id = manifest.id,
            url = url,
            manifest = manifest,
            kind = manifest.detectKind(),
        )
        persist(session.profileId.value, entry)
        invalidateStreamCache()
        return entry
    }

    suspend fun refresh(id: String?, rawUrl: String?): AddonEntry {
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        val normalizedUrl = rawUrl?.takeIf(String::isNotBlank)?.let(::normalizeAddonUrl)
        val existing = find(profileId, id, normalizedUrl)
            ?: throw ownershipFailure(profileId, id, normalizedUrl)
        require(existing.source == "stremio") { "official addons cannot be refreshed" }
        urlPolicy.validate(existing.url)
        val manifest = fetchManifest(existing.url)
        val validCatalogs = manifest.catalogs.map(AddonCatalog::key).toSet()
        val refreshed = existing.copy(
            id = manifest.id,
            manifest = manifest,
            kind = manifest.detectKind(),
            disabledCatalogs = existing.disabledCatalogs.filterKeys(validCatalogs::contains),
        )
        persist(profileId, refreshed)
        invalidateStreamCache()
        return refreshed
    }

    suspend fun setEnabled(id: String?, rawUrl: String?, enabled: Boolean) {
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        val normalizedUrl = rawUrl?.takeIf(String::isNotBlank)?.let(::normalizeAddonUrl)
        val existing = find(profileId, id, normalizedUrl)
            ?: throw ownershipFailure(profileId, id, normalizedUrl)
        // No source check here, unlike remove and refresh. Official integrations
        // are meant to be switched off — officialEnabled() gates JustWatch and
        // IntroDB on exactly this flag — and the guard that used to sit here was a
        // copy of remove()'s, right down to its "cannot be removed" message. It
        // made every toggle of a built-in addon fail with a 400.
        persist(profileId, existing.copy(enabled = enabled))
        invalidateStreamCache()
    }

    suspend fun remove(id: String?, rawUrl: String?) {
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        val normalizedUrl = rawUrl?.takeIf(String::isNotBlank)?.let(::normalizeAddonUrl)
        val existing = find(profileId, id, normalizedUrl)
            ?: throw ownershipFailure(profileId, id, normalizedUrl)
        database.coveQueries.deleteAddonByUrl(profileId, existing.url)
        database.coveQueries.upsertProfileStoreVersion(profileId, "addons", now())
        invalidateStreamCache()
    }

    /**
     * @param refresh ask the providers again instead of answering from the cache; the
     *   fresh result is still cached. This is for a viewer retrying a stream that died:
     *   several providers mint a playback link per request, so the cached listing holds
     *   the very link that just stopped working and a retry served from it cannot help.
     */
    suspend fun streams(
        type: MediaType,
        stremioId: String,
        refresh: Boolean = false,
    ): List<AddonStream> {
        val key = "${cacheToken()}|${type.wireName}|$stremioId"
        val now = Clock.System.now().toEpochMilliseconds()
        streamCacheMutex.withLock {
            streamCache.entries.removeAll { it.value.expiresAt <= now }
            if (!refresh) streamCache[key]?.let { return it.streams }
        }
        val result = coroutineScope {
        val providers = entries().filter { entry ->
            entry.enabled && entry.source == "stremio" &&
                entry.kind == AddonKind.Provider && entry.manifest.hasResource("stream")
        }
        providers.map { addon ->
            async {
                withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) {
                    runCatching { fetchStreams(addon, type, stremioId) }.getOrElse { error ->
                        onProviderError(addon, error)
                        emptyList()
                    }
                }.orEmpty()
            }
        }.map { it.await() }.flatten()
        }
        streamCacheMutex.withLock {
            streamCache[key] = CachedAddonStreams(
                result,
                now + if (result.isEmpty()) EMPTY_STREAM_CACHE_MILLIS else STREAM_CACHE_MILLIS,
            )
        }
        return result
    }

    suspend fun subtitles(type: MediaType, stremioId: String): List<AddonSubtitle> = coroutineScope {
        entries().filter { entry ->
            entry.enabled && entry.source == "stremio" &&
                entry.kind == AddonKind.Subtitle && entry.manifest.hasResource("subtitles")
        }.map { addon ->
            async {
                withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) {
                    runCatching { fetchSubtitles(addon, type, stremioId) }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }.map { it.await() }.flatten()
    }

    suspend fun enabledCatalogs(): List<AddonCatalogRef> = entries().flatMap { addon ->
        if (!addon.enabled || addon.source != "stremio") return@flatMap emptyList()
        addon.manifest.catalogs.filter { catalog ->
            catalog.isHomeEligible() && addon.disabledCatalogs[catalog.key()] != true
        }.map { catalog ->
            AddonCatalogRef(
                addon.id,
                addon.manifest.name,
                addon.url,
                catalog.type,
                catalog.id,
                catalog.name,
            )
        }
    }

    suspend fun setCatalogEnabled(
        id: String?,
        rawUrl: String?,
        catalogKey: String,
        enabled: Boolean,
    ) {
        require(catalogKey.matches(Regex("[^/]+/[^/]+"))) { "invalid catalog key" }
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        val normalizedUrl = rawUrl?.takeIf(String::isNotBlank)?.let(::normalizeAddonUrl)
        val existing = find(profileId, id, normalizedUrl)
            ?: throw ownershipFailure(profileId, id, normalizedUrl)
        require(existing.manifest.catalogs.any { it.key() == catalogKey }) {
            "unknown catalog $catalogKey"
        }
        val disabled = existing.disabledCatalogs.toMutableMap()
        if (enabled) disabled.remove(catalogKey) else disabled[catalogKey] = true
        persist(profileId, existing.copy(disabledCatalogs = disabled))
    }

    suspend fun catalog(
        addonId: String?,
        rawAddonUrl: String?,
        type: String,
        catalogId: String,
        skip: Int,
    ): List<AddonCatalogItem> {
        require(skip >= 0) { "skip must not be negative" }
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        val normalizedUrl = rawAddonUrl?.takeIf(String::isNotBlank)?.let(::normalizeAddonUrl)
        // Read path, so inherited addons resolve too: a home-screen row from
        // one carries its id, and the owned-only find() would 404 on it.
        val addon = findIncludingInherited(profileId, addonId, normalizedUrl)
            ?: error("addon not found")
        require(addon.source == "stremio" && addon.enabled) { "addon is disabled" }
        urlPolicy.validate(addon.url)
        val suffix = if (skip == 0) ".json" else "/skip=$skip.json"
        return getJson<AddonCatalogResponse>(
            "${addon.url}/catalog/${type.encodeURLPathPart()}/${catalogId.encodeURLPathPart()}$suffix",
        ).metas
    }

    suspend fun timestamps(tmdbId: Int, season: Int?, episode: Int?): TimestampData {
        require(tmdbId > 0) { "tmdb id must be positive" }
        if (!officialEnabled("cove.introdb")) return TimestampData()
        val primary = runCatching {
            getJson<TimestampData>("$introDbBaseUrl/media") {
                parameter("tmdb_id", tmdbId)
                season?.let { parameter("season", it) }
                episode?.let { parameter("episode", it) }
            }
        }.getOrDefault(TimestampData())
        if (season == null || episode == null) return primary
        val imdbId = runCatching { imdbLookup(tmdbId) }.getOrNull().orEmpty()
        if (imdbId.isBlank()) return primary
        val secondary = runCatching {
            getJson<IntroDbAppResponse>("$introDbAppBaseUrl/segments") {
                parameter("imdb_id", imdbId)
                parameter("season", season)
                parameter("episode", episode)
            }.toTimestampData()
        }.getOrDefault(TimestampData())
        return primary.fillMissingFrom(secondary)
    }

    suspend fun watchOptions(type: MediaType, tmdbId: Int): List<WatchOption> {
        require(tmdbId > 0) { "tmdb id must be positive" }
        if (!officialEnabled("cove.justwatch") || tmdbApiKey.isBlank()) return emptyList()
        val response = getJson<WatchProvidersResponse>(
            "$tmdbBaseUrl/${type.wireName}/$tmdbId/watch/providers",
        ) { parameter("api_key", tmdbApiKey) }
        val region = response.results["US"] ?: return emptyList()
        return region.flatrate.map { it.toOption("flatrate", region.link) } +
            region.rent.map { it.toOption("rent", region.link) } +
            region.buy.map { it.toOption("buy", region.link) }
    }

    internal suspend fun snapshotForSync(): AddonSyncSnapshot {
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        ensureOfficialEntries(profileId)
        val rows = database.coveQueries.selectAddons(profileId).executeAsList()
        return AddonSyncSnapshot(
            entries = rows.map(AddonRow::toModel),
            updatedAt = database.coveQueries.selectProfileStoreVersion(profileId, "addons")
                .executeAsOneOrNull() ?: rows.maxOfOrNull { it.updated_at }.orEmpty(),
        )
    }

    internal suspend fun mergeFromRemote(entries: List<AddonEntry>, updatedAt: String) {
        val profileId = session.profileId.value
        ensureLegacyImported(profileId)
        val localUpdatedAt = database.coveQueries.selectProfileStoreVersion(profileId, "addons")
            .executeAsOneOrNull().orEmpty()
        if (updatedAt <= localUpdatedAt) return
        database.transaction {
            database.coveQueries.deleteAddonsForProfile(profileId)
            // Official rows from an older client arrive with no url; give them the
            // synthetic one this build uses so every local row looks the same
            // regardless of which client wrote it.
            entries.forEach { entry ->
                val normalized = if (entry.source == "official" && entry.url.isBlank()) {
                    entry.copy(url = "official:${entry.id}")
                } else {
                    entry
                }
                persist(profileId, normalized, updatedAt)
            }
            database.coveQueries.upsertProfileStoreVersion(profileId, "addons", updatedAt)
        }
        ensureOfficialEntries(profileId)
        invalidateStreamCache()
    }

    private suspend fun fetchManifest(baseUrl: String): AddonManifest {
        val manifest = getJson<AddonManifest>("$baseUrl/manifest.json")
        require(manifest.id.isNotBlank()) { "addon manifest has no id" }
        return manifest
    }

    private suspend fun fetchStreams(
        addon: AddonEntry,
        type: MediaType,
        stremioId: String,
    ): List<AddonStream> {
        urlPolicy.validate(addon.url)
        val stremioType = if (type == MediaType.Tv) "series" else "movie"
        val response = getJson<AddonStreamsResponse>(
            "${addon.url}/stream/$stremioType/${stremioId.encodeURLPathPart()}.json",
        )
        return response.streams.map { stream ->
            stream.copy(
                addonName = addon.manifest.name,
                sizeBytes = stream.sizeBytes.takeIf { it > 0 }
                    ?: stream.behaviorHints?.videoSize?.takeIf { it > 0 }
                    // Torrentio, the busiest provider there is, sets neither of
                    // the above and writes the size into the title instead.
                    // Without this the field is zero for most real answers.
                    ?: humanSizeToBytes(stream.title).takeIf { it > 0 }
                    ?: 0,
            )
        }
    }

    private suspend fun fetchSubtitles(
        addon: AddonEntry,
        type: MediaType,
        stremioId: String,
    ): List<AddonSubtitle> {
        urlPolicy.validate(addon.url)
        val stremioType = if (type == MediaType.Tv) "series" else "movie"
        return getJson<AddonSubtitlesResponse>(
            "${addon.url}/subtitles/$stremioType/${stremioId.encodeURLPathPart()}.json",
        ).subtitles
    }

    private suspend inline fun <reified T> getJson(
        url: String,
        noinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "Cove Kotlin Backend")
            accept(ContentType.Application.Json)
            configure()
        }
        if (!response.status.isSuccess()) {
            throw IllegalArgumentException("addon returned HTTP ${response.status.value}")
        }
        val text = response.bodyAsText()
        require(text.length <= MAX_ADDON_RESPONSE_CHARS) { "addon response exceeds 20 MiB" }
        return CoveJson.decodeFromString(text)
    }

    private fun find(profileId: String, id: String?, url: String?): AddonEntry? = when {
        !url.isNullOrBlank() -> database.coveQueries.selectAddonByUrl(profileId, url)
            .executeAsOneOrNull()?.toModel()
        !id.isNullOrBlank() -> database.coveQueries.selectAddonsById(profileId, id)
            .executeAsList().firstOrNull()?.toModel()
        else -> throw IllegalArgumentException("addon id or URL is required")
    }

    /**
     * [find], widened to the addons this profile inherits from the primary.
     *
     * Read paths use this; mutations must not. A mutation persists against the
     * active profile, so a managed row resolved here is one this profile does
     * not own — [ownershipFailure] is what a mutation gets instead.
     */
    private fun findIncludingInherited(profileId: String, id: String?, url: String?): AddonEntry? =
        find(profileId, id, url) ?: inheritedEntries(profileId, emptyList()).firstOrNull { entry ->
            if (!url.isNullOrBlank()) entry.url == url else entry.id == id
        }

    /**
     * Why a mutation could not resolve its target. An inherited addon is listed
     * like any other and is not missing at all, so the generic "addon not found"
     * would read as a bug rather than as the sharing policy holding.
     */
    private fun ownershipFailure(profileId: String, id: String?, url: String?) =
        if (findIncludingInherited(profileId, id, url) != null) {
            IllegalStateException("this addon is managed by the primary profile")
        } else {
            IllegalStateException("addon not found")
        }

    private fun persist(
        profileId: String,
        entry: AddonEntry,
        updatedAt: String = now(),
        updateVersion: Boolean = true,
    ) {
        val manifestJson = CoveJson.encodeToString(entry.manifest)
        val catalogsJson = CoveJson.encodeToString(entry.disabledCatalogs)
        val enabled = if (entry.enabled) 1L else 0L
        // Insert-then-update in one transaction: the insert creates the row only
        // when it is missing, and the update edits it in place, so an existing
        // addon keeps its rowid and therefore its position in the list.
        database.coveQueries.transaction {
            database.coveQueries.insertAddonIfMissing(
                profile_id = profileId,
                url = entry.url,
                addon_id = entry.id,
                manifest_json = manifestJson,
                kind = entry.kind.wireName,
                source = entry.source,
                enabled = enabled,
                disabled_catalogs_json = catalogsJson,
                updated_at = updatedAt,
            )
            database.coveQueries.updateAddon(
                addon_id = entry.id,
                manifest_json = manifestJson,
                kind = entry.kind.wireName,
                source = entry.source,
                enabled = enabled,
                disabled_catalogs_json = catalogsJson,
                updated_at = updatedAt,
                profile_id = profileId,
                url = entry.url,
            )
        }
        if (updateVersion) {
            database.coveQueries.upsertProfileStoreVersion(profileId, "addons", updatedAt)
        }
    }

    private fun ensureLegacyImported(profileId: String) {
        val marker = "addons_structured_import:$profileId"
        if (database.coveQueries.selectMigrationMetadata(marker).executeAsOneOrNull() != null) return
        database.transaction {
            val payload = database.coveQueries
                .selectLegacyPayloadRecord(profileId, "addons")
                .executeAsOneOrNull()
            if (payload != null) {
                when (CoveJson.parseToJsonElement(payload.json)) {
                    is JsonArray -> {
                        // A host that did not own addons stored Supabase's wire payload
                        // untouched. That shape is a bare array rather than the legacy
                        // on-disk object's { stremioAddons, officialEnabled } wrapper.
                        val importedAt = payload.updated_at.ifBlank(now)
                        CoveJson.decodeFromString(
                            ListSerializer(AddonEntry.serializer()),
                            payload.json,
                        ).forEach { entry ->
                            val normalized = if (entry.source == "official" && entry.url.isBlank()) {
                                entry.copy(url = "official:${entry.id}")
                            } else {
                                entry
                            }
                            persist(profileId, normalized, importedAt)
                        }
                    }
                    else -> {
                        val legacy = CoveJson.decodeFromString<LegacyAddonStore>(payload.json)
                        val importedAt = legacy.updatedAt
                            .ifBlank { payload.updated_at }
                            .ifBlank(now)
                        legacy.stremioAddons.forEach { persist(profileId, it, importedAt) }
                        officialAddons.forEach { official ->
                            persist(
                                profileId,
                                official.copy(enabled = legacy.officialEnabled[official.id] ?: true),
                                importedAt,
                                updateVersion = false,
                            )
                        }
                    }
                }
            }
            database.coveQueries.upsertMigrationMetadata(marker, "1")
        }
    }

    private fun ensureOfficialEntries(profileId: String) {
        val existing = database.coveQueries.selectAddons(profileId).executeAsList()
            .mapTo(mutableSetOf()) { it.addon_id }
        val legacyFlags = database.coveQueries.selectLegacyPayload(profileId, "addons")
            .executeAsOneOrNull()
            ?.let { runCatching { CoveJson.decodeFromString<LegacyAddonStore>(it).officialEnabled }.getOrNull() }
            .orEmpty()
        officialAddons.filterNot { it.id in existing }.forEach { official ->
            persist(
                profileId,
                official.copy(enabled = legacyFlags[official.id] ?: true),
                updatedAt = "",
                updateVersion = false,
            )
        }
    }

    // Safe to read through entries(): inheritance covers stremio addons only, so
    // no primary row can ever shadow this profile's own JustWatch/IntroDB switch.
    private suspend fun officialEnabled(id: String): Boolean =
        entries().firstOrNull { it.id == id && it.source == "official" }?.enabled == true

    private suspend fun invalidateStreamCache() = streamCacheMutex.withLock {
        streamCache.clear()
    }
}

private data class CachedAddonStreams(val streams: List<AddonStream>, val expiresAt: Long)

private const val STREAM_CACHE_MILLIS = 15 * 60 * 1_000L
private const val EMPTY_STREAM_CACHE_MILLIS = 2 * 60 * 1_000L

internal data class AddonSyncSnapshot(
    val entries: List<AddonEntry>,
    val updatedAt: String,
)

internal fun normalizeAddonUrl(raw: String): String = raw.trim().trimEnd('/')
    .removeSuffix("/manifest.json")
    .trimEnd('/')

private val AddonKind.wireName: String
    get() = when (this) {
        AddonKind.Provider -> "provider"
        AddonKind.Subtitle -> "subtitle"
        AddonKind.Timestamps -> "timestamps"
    }

private fun AddonManifest.detectKind(): AddonKind =
    if (hasResource("subtitles") && !hasResource("stream")) AddonKind.Subtitle else AddonKind.Provider

private fun AddonManifest.hasResource(name: String): Boolean = resources.any { resource ->
    when (resource) {
        is JsonPrimitive -> resource.contentOrNull == name
        is JsonObject -> resource["name"]?.jsonPrimitive?.contentOrNull == name
        else -> false
    }
}

/**
 * Whether a catalog can be drawn as a row without the viewer supplying something first.
 *
 * A catalog whose `extra` marks `search` or `genre` required has no meaningful "just show
 * me it" response, so it is not a row — only `skip` is, since paging supplies that itself.
 * Internal rather than private: [LocalAddonRepository] applies the same rule when it
 * reports a manifest's catalogs to the settings screen, and two copies would drift.
 */
internal fun AddonCatalog.isHomeEligible(): Boolean {
    val required = extra.filter(AddonCatalogExtra::isRequired).map(AddonCatalogExtra::name) + extraRequired
    return required.all { it == "skip" }
}

internal fun AddonRow.toModel() = AddonEntry(
    id = addon_id,
    url = url,
    manifest = CoveJson.decodeFromString(manifest_json),
    kind = when (kind) {
        "provider" -> AddonKind.Provider
        "subtitle" -> AddonKind.Subtitle
        "timestamps" -> AddonKind.Timestamps
        else -> error("unknown addon kind $kind")
    },
    enabled = enabled != 0L,
    source = source,
    disabledCatalogs = CoveJson.decodeFromString(disabled_catalogs_json),
)

private val officialAddons = listOf(
    AddonEntry(
        id = "cove.justwatch",
        url = "official:cove.justwatch",
        manifest = AddonManifest(
            id = "cove.justwatch",
            name = "JustWatch",
            description = "Streaming availability via TMDB/JustWatch",
        ),
        kind = AddonKind.Provider,
        source = "official",
    ),
    AddonEntry(
        id = "cove.introdb",
        url = "official:cove.introdb",
        manifest = AddonManifest(
            id = "cove.introdb",
            name = "IntroSkip",
            description = "Intro, recap, credits, and preview timestamps",
        ),
        kind = AddonKind.Timestamps,
        source = "official",
    ),
)

private fun TimestampData.fillMissingFrom(other: TimestampData) = TimestampData(
    intro = intro.ifEmpty { other.intro },
    recap = recap.ifEmpty { other.recap },
    credits = credits.ifEmpty { other.credits },
    preview = preview.ifEmpty { other.preview },
)

@Serializable
private data class IntroDbAppResponse(
    val intro: IntroDbAppSegment? = null,
    val recap: IntroDbAppSegment? = null,
    val outro: IntroDbAppSegment? = null,
) {
    fun toTimestampData() = TimestampData(
        intro = intro?.let { listOf(it.toSegment()) }.orEmpty(),
        recap = recap?.let { listOf(it.toSegment()) }.orEmpty(),
        credits = outro?.let { listOf(it.toSegment()) }.orEmpty(),
    )
}

@Serializable
private data class IntroDbAppSegment(
    @kotlinx.serialization.SerialName("start_ms") val startMs: Long,
    @kotlinx.serialization.SerialName("end_ms") val endMs: Long,
) {
    fun toSegment() = TimestampSegment(startMs, endMs)
}

@Serializable
private data class WatchProvidersResponse(
    val results: Map<String, WatchProviderRegion> = emptyMap(),
)

@Serializable
private data class WatchProviderRegion(
    val link: String = "",
    val flatrate: List<WatchProvider> = emptyList(),
    val rent: List<WatchProvider> = emptyList(),
    val buy: List<WatchProvider> = emptyList(),
)

@Serializable
private data class WatchProvider(
    @kotlinx.serialization.SerialName("provider_id") val id: Int,
    @kotlinx.serialization.SerialName("provider_name") val name: String,
    @kotlinx.serialization.SerialName("logo_path") val logoPath: String = "",
) {
    fun toOption(type: String, link: String) = WatchOption(id, name, logoPath, type, link)
}
