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
    val discovery: DiscoveryRepository = UnavailableDiscoveryRepository,
    val insights: InsightsRepository = UnavailableInsightsRepository,
    // Defaulted to the Unavailable objects so a host that cannot offer one — for
    // example a remote backend over --api-base — is a rendering decision in the
    // settings page rather than a compile error here.
    val account: AccountRepository = UnavailableAccountRepository,
    val profiles: ProfileRepository = UnavailableProfileRepository,
    val trakt: TraktRepository = UnavailableTraktRepository,
    val device: DeviceRepository = UnavailableDeviceRepository,
    val updates: UpdateRepository = UnavailableUpdateRepository,
    val storage: StorageRepository = UnavailableStorageRepository,
    val plugins: PluginRepository = UnavailablePluginRepository,
    /**
     * True when every repository above is a canned fixture rather than a live backend.
     *
     * The shells draw a marker from it, and that marker is not decoration: the fixtures
     * carry real TMDB artwork and a real-looking library, so a fixtures run is
     * indistinguishable from a real one right up until something asks the catalog a
     * question it cannot answer — a search for a title outside the canned dozen comes
     * back empty and reads as a broken search rather than as a harness.
     *
     * Defaulted, so only [com.coveninja.cove.shared.fixture.FixtureAppGraph] sets it and
     * the live hosts need no change.
     */
    val fixtures: Boolean = false,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}
