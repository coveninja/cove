package com.coveninja.cove.shared.data

// Wires the repository interfaces together. onClose is invoked by close()
// so the Ktor client and coroutine scope can be torn down without leaking;
// defaults to a no-op so FixtureAppGraph() works without change.
class AppGraph(
    val content: ContentRepository,
    val library: LibraryRepository,
    val settings: SettingsRepository,
    val playback: PlaybackRepository,
    val addons: AddonRepository,
    val calendar: CalendarRepository = UnavailableCalendarRepository,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}
