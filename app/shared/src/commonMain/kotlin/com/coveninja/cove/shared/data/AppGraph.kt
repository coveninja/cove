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
    // Defaulted to the Unavailable objects so a host that cannot offer one — for
    // example a remote backend over --api-base — is a rendering decision in the
    // settings page rather than a compile error here.
    val account: AccountRepository = UnavailableAccountRepository,
    val profiles: ProfileRepository = UnavailableProfileRepository,
    val trakt: TraktRepository = UnavailableTraktRepository,
    val device: DeviceRepository = UnavailableDeviceRepository,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}
