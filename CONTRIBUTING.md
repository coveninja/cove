# Contributing to Cove

## Setup

Install JDK 21 and libmpv. A fixture-backed desktop run needs no provider keys:

```sh
make run
```

For live catalog data, copy `.env.example` to `.env`, set `TMDB_API_KEY`, and
select the real backend explicitly:

```sh
cd app
./gradlew :desktop:run --args="--backend-mode kotlin"
```

`make hot` is the preferred fixture-backed Compose UI loop. For a live hot
reload session or focused work, run Gradle from `app/`, for example
`./gradlew :desktop:hotRun --auto --args="--backend-mode kotlin"`,
`./gradlew :backend:desktopTest`, or `./gradlew :desktop:run`.

For Android work, install SDK platform/build-tools 37 and run `make mobile` or
`./gradlew :mobile:installDebug`. One `app/mobile` APK serves phones, tablets,
and televisions: `MainActivity` selects adaptive `CoveApp` or D-pad-oriented
`CoveTvApp` from the device. Use `make run-tv`/`make hot-tv` for the desktop TV
harness and `make tv-avd`/`make tv-install` for an Android TV device. Share the
domain/backend graph, but keep D-pad/ten-foot behavior in the TV presentation
rather than conditional branches throughout the touch UI.

Use the Gradle wrapper under `app/`; no system Gradle installation is required.
Desktop configuration is read from the environment, the nearest `.env`, then
packaged properties. Android deployment values are compiled into `BuildConfig`.
Never put a service-role key, signing key, or production auth token in `.env`.

### Useful development modes

| Mode | Command | External configuration |
|---|---|---|
| Desktop fixtures | `make run` | None |
| Desktop fixtures with hot reload | `make hot` | None |
| Live desktop graph | `cd app && ./gradlew :desktop:run --args="--backend-mode kotlin"` | `TMDB_API_KEY`; optional public integration credentials |
| Desktop TV harness | `make run-tv` or `make hot-tv` | None for fixtures |
| Android touch app | `make mobile` or `cd app && ./gradlew :mobile:installDebug` | Android SDK and a connected device/emulator |
| Android TV | `make tv-avd` then `make tv-install` | SDK emulator tools and hardware acceleration |

## Code organization

- Put portable models, interfaces, repositories, and business logic in
  `commonMain`. Put filesystem, SQLite JDBC, Ktor CIO, GraalJS, jlibtorrent, and
  desktop lifecycle code in `desktopMain`; put Android drivers and lifecycle
  adapters in `androidMain` or `app/mobile`.
- Compose screens depend on `AppGraph` repositories. Do not route ordinary
  in-process UI operations through localhost.
- Keep URL-requiring media operations and external compatibility in
  `CoreRoutes`. New routes belong under `/api/v1`; only add an unversioned alias
  when an existing client contract requires it.
- `AppSettings` is a whole-object persisted contract. Add new settings with
  safe defaults and update the SQL/JSON compatibility tests in the same change.
- Every stored row is either explicitly global or profile-scoped. Include the
  active profile in caches and invalidate caches when provider/settings state
  changes.
- Route all user-supplied URLs through the host's `AddonUrlPolicy`
  (`DesktopAddonUrlPolicy` or `AndroidAddonUrlPolicy`). Do not enable automatic
  redirects on the untrusted HTTP client or forward credentials to a different
  authority.
- Nuvio code must remain in its platform sandbox: a disposable GraalJS child
  JVM on desktop or an isolated QuickJS service on Android. Do not expose host
  classes, filesystem/process APIs, native access, or unbounded execution.
- Desktop plugins stay behind `PluginRepository` and the brokered worker
  protocol. New capabilities require an explicit manifest value, user-facing
  approval text, host enforcement, protocol limits, and tests. Never pass raw
  playback URLs to observation-only plugins.
- The primary-profile addon-sharing switch applies only to provider addons and
  catalog visibility. Do not accidentally share libraries, Nuvio activation,
  plugin grants, progress, or ordinary profile settings with it.
- Comments should explain constraints and decisions; use names and small
  functions to explain mechanics.

The retired Go backend is available through Git history but is no longer kept in
the working tree. All maintained backend work belongs under `app/backend`.

## Testing

```sh
make test                 # all Kotlin module tests
make test-build           # desktop app image plus Android debug APK
make test-workflows       # release-note tests plus actionlint/shellcheck
make test-all             # broad local CI approximation
```

For backend changes, prefer a focused test while iterating and finish with
`./gradlew test --no-daemon`. Add contract tests for HTTP status/body/header
behavior, migration tests for every schema or import change, and concurrency or
cache-invalidation tests where work is asynchronous.

Choose the focused suite that owns the behavior:

| Change | Focused validation |
|---|---|
| Shared models or repositories | Owning `commonTest` class and shared tests |
| Routes, sync, addons, plugins, or persistence | `:backend:desktopTest` with the owning test class |
| Shared Compose UI or state | `:ui:allTests` or the relevant UI test class |
| Desktop player or lifecycle | `:desktop:test` plus a GUI smoke for visual/native behavior |
| Android host or packaging | `:mobile:testDebugUnitTest`, `:mobile:lintDebug`, and `:mobile:assembleDebug` |
| Site documentation | `make test-site-docs` |
| Workflows or release scripts | `make test-workflows` |

Tests must use temporary directories, fixture graphs, mock HTTP engines, and
ephemeral keys. They must not read a developer's real database, auth session,
plugin store, or `.env`.

CI builds and tests the Gradle project on Linux, verifies an Apple-silicon
desktop image on macOS, lints the Android app, launches it on a phone emulator,
reviews pull-request dependencies, and lints workflow files. Release jobs repeat
the Kotlin gate and create Linux/Flatpak, Windows, ad-hoc-signed macOS, and
signed Android phone/tablet/TV artifacts.

## Before opening a PR

- Run the checks proportional to the changed surface and `git diff --check`.
- Describe behavior and migration impact, not just the files changed.
- Preserve desktop input/player behavior, phone/tablet touch behavior, and TV
  focus/D-pad behavior. Keep TV presentation separate and never pretend native
  player, torrent, or sandbox implementations are portable unchanged.
- Never commit `.env`, user databases, auth sessions, provider tokens, or
  generated release credentials.
- Update the versioned site guide and relevant contributor reference in the
  same change when user-visible behavior, an endpoint, a setting, packaging, or
  a trust boundary changes. Keep user instructions task-oriented and verify
  relative links with `make test-site-docs`.
