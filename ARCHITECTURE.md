# Cove architecture

## Runtime topology

Cove has two application hosts over the same Kotlin Multiplatform graph.
`app/desktop` owns the desktop window, mpv player, single-instance lock, and
`LocalBackendRuntime`; `app/mobile` owns the Android activity/lifecycle and
`AndroidBackendRuntime`. Both construct repositories in-process, pass the same
`AppGraph` into the same `CoveApp` Compose root, and avoid serializing ordinary
UI operations through localhost HTTP.

The mobile artifact requires a touchscreen and has no Leanback launcher. This
is a deliberate product boundary: phone/tablet and desktop share the adaptive
Compose presentation, while a future Android TV host will provide a separate
ten-foot/D-pad UI and reuse only shared domain/backend contracts.

On desktop, an embedded Ktor server still listens on loopback for boundaries
that require a URL: mpv stream/torrent access, subtitle and image proxies,
download progress, speed tests, diagnostics, and compatibility clients. It
exposes the stable `/api/v1` namespace. The former `/api` namespace delegates to
the same handlers and returns `Deprecation`, `Sunset`, and successor-version
headers.

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
| `app/backend` common | TMDB client, addon manager, profile/settings/library repositories, Supabase auth and cross-device sync |
| `app/backend` desktop | SQLite, migration, Ktor, media/torrent, Nuvio sandbox, Trakt, discovery, prefetch |
| `app/backend` Android | Android SQLite driver, upgrade migration, OkHttp, and mobile runtime composition |
| `app/ui` | Shared desktop/mobile Compose presentation and platform interaction seams |
| `app/desktop` | Desktop composition root, lifecycle, packaging, and mpv surfaces |
| `app/mobile` | Android phone/tablet composition root, manifest, lifecycle, and APK packaging |

Portable code stays in `commonMain`. Desktop implementations live in
`desktopMain`; Android drivers and lifecycle adapters live in `androidMain` or
`app/mobile`. Pointer-only secondary-click behavior is an expect/actual seam;
touch keeps the shared long-press and drag interactions. TV-specific focus,
navigation, density, and screen composition must not be added as mode switches
inside the shared touch UI.

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
library/progress/dismissal state and preserves session/addon/Nuvio/Trakt/
activity JSON as opaque payloads until mobile adapters consume it. Both record
versioned markers. Structured legacy export is currently a desktop-only
`--export-legacy` recovery path.

## Integrations and discovery

The Android runtime currently exposes the services used by the shared UI:
localized TMDB content plus profile-scoped settings and library persistence.
The richer desktop service graph remains desktop-only until each service gets a
mobile-safe adapter; preserved legacy payloads prevent those future adapters
from losing upgrade data.

- `TmdbClient` owns localized metadata. It uses the selected UI language,
  performs English fallback for missing presentation fields, and resolves TMDB
  and IMDb identifiers used by addons.
- `AddonManager` handles Stremio manifests, streams, subtitles, catalogs, the
  official watch-option/timestamp integrations, bounded caching, and explicit
  invalidation when provider state changes.
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

Community scraper code never executes in the application JVM. Each invocation
starts a disposable child JVM with a 128 MiB heap and a parent-enforced timeout.
GraalJS receives no host-class lookup, host IO, process/native access, or thread
creation. The bundled compatibility resources are stored beside the sandbox in
`app/backend/src/desktopMain/resources`. Repository and scraper activation remain
profile-scoped and opt-in.

## Playback and media boundary

Direct HTTP sources are probed and proxied only when headers or compatibility
handling require it. Redirects are validated one hop at a time, and credentials
are stripped when authority changes. Subtitle responses can be converted from
SRT to WebVTT. Torrent requests are registered with the embedded server and
served from jlibtorrent while SSE exposes progress. The torrent engine chooses
the requested or largest playable video file and owns cleanup on runtime close.

Desktop mpv stays in-process through JNA. The OpenGL path retains GPU rendering
and hardware decoding; software rendering remains the controlled fallback.
Native Android playback and torrent/media-boundary adapters are intentionally
separate future platform work; the shared UI does not pretend desktop JNA or
jlibtorrent binaries are Android-compatible.

## Configuration and release

Desktop configuration precedence is environment, nearest `.env`, then bundled
release properties. Android reads the same deployment values into `BuildConfig`
at APK build time. Release jobs inject deployment keys into desktop resources,
build the Compose distributables plus libmpv, and publish a separately signed
`cove-android.apk`. CI compiles/lints/tests both hosts and launches the mobile
artifact on a phone emulator. There is no backend sidecar, private-source
injection, or Go toolchain in active CI/release paths.

Self-replacement is intentionally disabled. `/update/check` reports the current
version and `/update/apply` directs users to Flatpak, AUR, or the Windows
installer, keeping updates atomic under the platform package manager.

The retired implementation remains in Git history; a local ignored cutover copy
may exist under `legacy/go-backend`. It is excluded from builds and packages and
must not receive new behavior.
