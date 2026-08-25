# Cove architecture

## Runtime topology

Cove has two platform hosts over the same Kotlin Multiplatform graph.
`app/desktop` owns the desktop window, mpv player, single-instance lock, and
`LocalBackendRuntime`; `app/mobile` owns the Android activity/lifecycle and
`AndroidBackendRuntime`. Both construct repositories in-process and pass the
same `AppGraph` into Compose. Desktop and Android phone/tablet use the adaptive
`CoveApp` root; Android TV uses the separate D-pad-oriented `CoveTvApp` root,
which is also available on desktop through `--tv` as a development harness.
Ordinary UI operations never need to be serialized through localhost HTTP.

One Android APK serves phones, tablets, and televisions. Its touchscreen and
Leanback features are both optional, and `MainActivity` selects the touch or TV
root from `FEATURE_LEANBACK` and the current UI mode. The manifest includes both
the ordinary launcher and `LEANBACK_LAUNCHER`, with TV banner artwork.

On desktop, an embedded Ktor server listens on loopback for boundaries that
require a URL: mpv stream/torrent access, subtitle and image proxies, download
progress, speed tests, diagnostics, and compatibility clients. Android lazily
starts a narrow, ephemeral loopback media host only when playback needs a URL.
When Android remote access is enabled, its connected-device foreground service
also starts the full compatibility route graph on loopback port 6969 and LAN
port 6970. The full API exposes the stable `/api/v1` namespace; the former
`/api` namespace delegates to the same handlers and returns `Deprecation`,
`Sunset`, and successor-version headers.

Disk retention is deliberately not one of those boundaries. Cache measurement,
manual clearing, and the retention sweep all run in-process through
`StorageRepository`, the same way `DeviceRepository` serves the mpv config: the
caches sit on whichever machine is running the backend, so a compatibility
client pointed at it over `--api-base` gets `UnavailableStorageRepository` and no
storage screen rather than the ability to delete somebody else's files. No route
was added to `/api/v1` for any of it.

Optional LAN access is a separate listener. It starts only when the persisted
setting enables it and a non-empty token exists. Remote requests use a
constant-time token check; loopback CORS is allow-listed. User-supplied addon,
plugin, proxy, and custom-ranking URLs pass through a policy that rejects local,
private, metadata, userinfo, and unsafe redirect targets unless the explicit LAN
stream-source preference permits them.

## Gradle modules

| Module | Responsibility |
|---|---|
| `app/shared` | Domain models, repository interfaces, app graph, HTTP compatibility client |
| `app/backend` common/JVM-shared | TMDB, addons, auth/sync, calendar, discovery, activity, Trakt, storage, Ktor routes, and shared service logic |
| `app/backend` desktop | SQLite JDBC, CIO clients/servers, desktop jlibtorrent, GraalJS sandbox, updater, and runtime composition |
| `app/backend` Android | Android SQLite, OkHttp, Android jlibtorrent, QuickJS sandbox, updater, playback media host, and runtime composition |
| `app/ui` | Shared adaptive Compose presentation, separate TV root, and platform interaction seams |
| `app/desktop` | Desktop composition root, lifecycle, packaging, and mpv surfaces |
| `app/mobile` | Android phone/tablet/TV composition roots, services, manifest, lifecycle, native mpv, and APK packaging |
| `app/benchmark` | Android Macrobenchmark tests and generated baseline/startup profiles |

Portable code stays in `commonMain`. Desktop implementations live in
`desktopMain`; shared JVM services used by both targets live in `jvmSharedMain`;
Android drivers and lifecycle adapters live in `androidMain` or `app/mobile`.
Pointer-only secondary-click behavior is an expect/actual seam; touch keeps the
shared long-press and drag interactions. TV-specific focus, navigation, density,
and screen composition belong in `CoveTvApp`, not as mode switches throughout
the shared touch UI.

## Persistence

Each host opens one SQLDelight SQLite database through its platform driver:
SQLite JDBC on desktop and `AndroidSqliteDriver` in Android app storage. Schema
migrations are append-only under `app/backend/src/commonMain/sqldelight`; they
cover profiles, settings, library/progress/dismissals, sessions, addons, Nuvio
state, activity, and Trakt state. Repositories publish `StateFlow` snapshots and keep
profile scoping explicit.

Migration runs before repositories become visible. Desktop `LegacyMigration`
parses and backs up all known JSON inputs first, then replaces/imports their
state in one database transaction. Android's migration uses the same package ID
and app-private `filesDir` as the former app; it imports profiles, settings,
library/progress/dismissal state and initially preserves session/addon/Nuvio/
Trakt/activity JSON as opaque payloads. The current services import or merge
those payloads when they initialize. Both migrations record versioned markers.
Structured legacy export is currently a desktop-only `--export-legacy`
recovery path.

## Integrations and discovery

Desktop and Android expose the same application-level services: localized TMDB
content, profile-scoped persistence, addons, Nuvio, discovery, calendar,
insights, account sync, Trakt, prefetch, playback, updates, and cache policy.
Their repository contracts and most service logic are shared; native and
security-sensitive implementations remain platform-owned.

- `TmdbClient` owns localized metadata. It uses the selected UI language,
  performs English fallback for missing presentation fields, and resolves TMDB
  and IMDb identifiers used by addons.
- `AddonManager` handles Stremio manifests, streams, subtitles, catalogs, the
  official watch-option/timestamp integrations, bounded caching, and explicit
  invalidation when provider state changes.
- Enabled addon catalogs become attributed Home and Explore rows. The primary
  profile can provide addon and catalog configuration to secondary profiles,
  but that read-only sharing does not cross library, progress, Nuvio, plugin,
  or ordinary settings boundaries.
- `DiscoveryService` builds profile-scoped taste signals from watch recency,
  genres, keywords, people, studios, ratings, and status. Watched/dismissed/
  removed items and age-inappropriate results are excluded before ranking. A
  custom HTTPS ranker is optional and uses the untrusted-network policy.
- `AuthService` and `SupabaseSyncService` implement public account auth and
  reconciliation. `TraktService` implements device OAuth, scrobbling, and
  two-way synchronization.
- `PrefetchService` observes progress changes and periodically warms bounded
  stream caches for current and next likely titles without starting torrents.

## Nuvio isolation

Community scraper code never executes with application-process authority. On
desktop, each invocation starts a disposable child JVM with a 128 MiB heap and
a parent-enforced timeout; GraalJS receives no host-class lookup, host IO,
process/native access, or thread creation. Android runs QuickJS in an
`isolatedProcess` service and brokers bounded, public-address-only fetches
through the main process. Both hosts load the vendored CommonJS compatibility
modules from `app/backend/src/desktopMain/resources`; the Android build packages
that directory as assets. Repository and scraper activation remain
profile-scoped and opt-in.

## Desktop plugin isolation

Desktop plugins use a signed package and permission model separate from addons
and Nuvio. `DesktopPluginManager` verifies the signed catalog, package/manifest
equality, digest, declared size, API compatibility, archive paths, file count,
and extraction size before installation. Updates are staged and activate only
after the current worker stops. A package requesting a new capability cannot
start until that capability is approved for the active profile.

Every enabled plugin runs in a dedicated 128 MiB child JVM. GraalJS receives no
host-class lookup, host IO, native access, process creation, or thread creation.
The line-framed protocol caps messages and enforces timeouts. Network, profile
storage, playback transport, and Discord IPC are host-brokered and checked
against the approved capability. Observation receives a URL-free playback
snapshot; media results are sanitized before joining addon and scraper results.

Plugins are currently desktop-only. Android and TV receive an unavailable
repository, so shared UI and content logic must tolerate the plugin surface
being absent without presenting dead settings.

## Playback and media boundary

Direct HTTP sources are probed and proxied only when headers or compatibility
handling require it. Redirects are validated one hop at a time, and credentials
are stripped when authority changes. Subtitle responses can be converted from
SRT to WebVTT. Desktop playback can also load a user-selected subtitle file or
accept one through the window drop target; the shared player exposes the action
only when the host implements it. Torrent requests are registered with the
platform media host and served from jlibtorrent while progress remains
observable. Each platform engine chooses the requested or largest playable
video file and owns cleanup on runtime close.

Playback segments use four semantic kinds: intro, recap, credits, and preview.
Recognized embedded media chapters win for their matching kind; compatible
external timestamps fill only the kinds the media did not supply. The shared
session uses the same resolved segments for seek-bar marks, manual actions, and
automatic skipping, preventing UI and playback policy from disagreeing.

Desktop mpv stays in-process through JNA. The OpenGL path retains GPU rendering
and hardware decoding; software rendering remains the controlled fallback.
Android uses its native libmpv surface host, a media-playback foreground service,
picture-in-picture on touch devices, decoder capability probing, and its own
jlibtorrent/media-boundary implementations. The shared UI sees both through the
same `VideoPlayerHost` and `PlaybackRepository` contracts without pretending the
native binaries or surface lifecycles are interchangeable.

## Configuration and release

Desktop configuration precedence is environment, nearest `.env`, then bundled
release properties. Android reads the same deployment values into `BuildConfig`
at APK build time. Release jobs inject deployment keys into desktop resources,
build platform Compose distributables and native dependencies, and publish a
separately signed `cove-android.apk`. CI compiles, lints, and tests the shared,
desktop, and Android modules on Linux; creates and verifies an Apple-silicon app
on macOS; and launches the Android artifact on a phone emulator. There is no
backend sidecar, private-source injection, or Go toolchain in active CI/release
paths.

The HTTP compatibility API intentionally cannot drive application updates:
`/update/check` reports only the current version and `/update/apply` declines the
request. Signed update checking and staging are device-local repositories.
Windows installer/portable builds and Android APK builds can update in-app;
AUR, Flatpak, Linux tarball, and macOS installations remain package-manager or
manual replacements.

The retired Go/Qt/WebView implementation remains available in Git history, but
its source is no longer kept in the working tree or included in any build or
package.
