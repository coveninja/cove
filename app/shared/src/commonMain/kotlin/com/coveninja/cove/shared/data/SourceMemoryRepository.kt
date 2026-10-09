package com.coveninja.cove.shared.data

/**
 * The source a title was last played from.
 *
 * Deliberately not an address. Several providers mint a playback link per request, so the URL
 * is the one field certain to be stale by the time this is read; what survives a fresh listing
 * is the release — [releaseKey], the same identity a failover and a manual retry already match
 * on. The rest is what the picker needs to say which row this was, and what a *later* episode
 * can prefer when the exact release does not exist for it.
 */
data class SourceMemory(
    val releaseKey: String = "",
    val addonName: String = "",
    /** "1080p", "4K" — as the picker's badge spells it, so the two can be compared. */
    val quality: String = "",
    val displayName: String = "",
) {
    val isEmpty: Boolean get() = releaseKey.isBlank()

    companion object {
        val None = SourceMemory()
    }
}

/**
 * Remembers the chosen source per title, and per episode within a title.
 *
 * `season` and `episode` of null address the title as a whole: for a film that is the only row
 * there is, and for a series it is what the *next* episode reads — the exact release is gone by
 * then, but the provider and the quality that worked are still worth preferring.
 *
 * Device-local and outside profile sync, for the same reason [TrackMemoryRepository] is: a 4K
 * remux that plays beautifully on a desktop is not advice for a phone on a train.
 */
interface SourceMemoryRepository {
    /** [SourceMemory.None] where nothing has been played, never null — absence is not an error. */
    suspend fun read(tmdbId: Int, season: Int?, episode: Int?): SourceMemory

    suspend fun write(tmdbId: Int, season: Int?, episode: Int?, memory: SourceMemory)

    /** Forgets one entry, for a remembered source that turned out not to play. */
    suspend fun forget(tmdbId: Int, season: Int?, episode: Int?)
}

/** Stands in where nothing can be stored — see [UnavailableTrackMemoryRepository]. */
object UnavailableSourceMemoryRepository : SourceMemoryRepository {
    override suspend fun read(tmdbId: Int, season: Int?, episode: Int?): SourceMemory =
        SourceMemory.None

    override suspend fun write(tmdbId: Int, season: Int?, episode: Int?, memory: SourceMemory) = Unit

    override suspend fun forget(tmdbId: Int, season: Int?, episode: Int?) = Unit
}
