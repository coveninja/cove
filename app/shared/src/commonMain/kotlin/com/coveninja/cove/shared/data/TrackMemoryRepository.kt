package com.coveninja.cove.shared.data

/**
 * What the viewer last chose for one title, in the terms the next episode can act on.
 *
 * Languages rather than track ids throughout: an id is a position in one file's track list,
 * and the next episode is a different file — very possibly from a different release group with
 * its tracks in a different order. Remembering "3" would reliably select the wrong thing.
 */
data class TrackMemory(
    /** Empty means the viewer never chose one, which is not the same as choosing the first. */
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    /** Chosen "off", as distinct from never having chosen at all. */
    val subtitlesOff: Boolean = false,
    val speed: Double = 1.0,
) {
    val isEmpty: Boolean
        get() = audioLanguage.isBlank() && subtitleLanguage.isBlank() && !subtitlesOff &&
            speed == 1.0

    companion object {
        val None = TrackMemory()
    }
}

/**
 * Remembers per-title track choices for the active profile.
 *
 * Deliberately device-local and outside profile sync: it records what was picked on this
 * screen, with these speakers, and a choice made on a television's surround setup is not
 * advice for a phone on a train. That is the same reasoning that keeps the cache policy out
 * of AppSettings.
 */
interface TrackMemoryRepository {
    /** [TrackMemory.None] where nothing has been chosen, never null — absence is not an error. */
    suspend fun read(tmdbId: Int): TrackMemory

    suspend fun write(tmdbId: Int, memory: TrackMemory)
}

/** Stands in where nothing can be stored — see [UnavailableDeviceRepository]. */
object UnavailableTrackMemoryRepository : TrackMemoryRepository {
    override suspend fun read(tmdbId: Int): TrackMemory = TrackMemory.None
    override suspend fun write(tmdbId: Int, memory: TrackMemory) = Unit
}
