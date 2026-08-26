package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.data.TrackMemory
import com.coveninja.cove.shared.data.TrackMemoryRepository

/**
 * Per-title track choices, in the database beside everything else the profile owns.
 *
 * Scoped to the active profile at read and write rather than cached against one, because the
 * profile can change under a long-lived repository and a household's two viewers want
 * different answers for the same show.
 */
class LocalTrackMemoryRepository(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val now: () -> String,
) : TrackMemoryRepository {

    override suspend fun read(tmdbId: Int): TrackMemory {
        val row = database.coveQueries
            .selectTrackMemory(session.profileId.value, tmdbId.toLong())
            .executeAsOneOrNull()
            ?: return TrackMemory.None
        return TrackMemory(
            audioLanguage = row.audio_language,
            subtitleLanguage = row.subtitle_language,
            subtitlesOff = row.subtitles_off != 0L,
            speed = row.speed,
        )
    }

    override suspend fun write(tmdbId: Int, memory: TrackMemory) {
        // An empty memory is the absence of one, so it is stored as a missing row rather than
        // a row full of defaults — otherwise clearing a choice would leave behind something
        // that reads back as "chose the defaults" and overrides the settings forever.
        if (memory.isEmpty) {
            database.coveQueries.deleteTrackMemory(session.profileId.value, tmdbId.toLong())
            return
        }
        database.coveQueries.upsertTrackMemory(
            session.profileId.value,
            tmdbId.toLong(),
            memory.audioLanguage,
            memory.subtitleLanguage,
            if (memory.subtitlesOff) 1L else 0L,
            memory.speed,
            now(),
        )
    }
}
