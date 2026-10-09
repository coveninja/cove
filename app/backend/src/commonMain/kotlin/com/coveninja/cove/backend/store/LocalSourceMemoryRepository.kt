package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.data.SourceMemory
import com.coveninja.cove.shared.data.SourceMemoryRepository

/**
 * The last-played source per title and per episode, in the database beside everything else the
 * profile owns.
 *
 * Scoped to the active profile at read and write rather than cached against one, because the
 * profile can change under a long-lived repository and a household's two viewers want different
 * answers for the same show.
 */
class LocalSourceMemoryRepository(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val now: () -> String,
) : SourceMemoryRepository {

    override suspend fun read(tmdbId: Int, season: Int?, episode: Int?): SourceMemory {
        val row = database.coveQueries
            .selectSourceMemory(session.profileId.value, tmdbId.toLong(), key(season), key(episode))
            .executeAsOneOrNull()
            ?: return SourceMemory.None
        return SourceMemory(
            releaseKey = row.release_key,
            addonName = row.addon_name,
            quality = row.quality,
            displayName = row.display_name,
        )
    }

    override suspend fun write(tmdbId: Int, season: Int?, episode: Int?, memory: SourceMemory) {
        // An empty memory is the absence of one, so it is stored as a missing row rather than a
        // row that reads back as "the release with no name" and matches nothing for ever.
        if (memory.isEmpty) return forget(tmdbId, season, episode)
        database.coveQueries.upsertSourceMemory(
            session.profileId.value,
            tmdbId.toLong(),
            key(season),
            key(episode),
            memory.releaseKey,
            memory.addonName,
            memory.quality,
            memory.displayName,
            now(),
        )
    }

    override suspend fun forget(tmdbId: Int, season: Int?, episode: Int?) {
        database.coveQueries
            .deleteSourceMemory(session.profileId.value, tmdbId.toLong(), key(season), key(episode))
    }

    /**
     * A film, and the per-title row of a series, are both "no episode in particular".
     *
     * Stored as -1 rather than as NULL because this is half of the primary key, and SQLite
     * treats two NULLs as distinct there — every write would insert another row and every read
     * would find none of them.
     */
    private fun key(value: Int?): Long = value?.toLong() ?: TITLE_WIDE

    private companion object {
        const val TITLE_WIDE = -1L
    }
}
