# Contributing to Cove

## Setup

Install JDK 21 and libmpv, put `TMDB_API_KEY` in the repository `.env`, then:

```sh
make run
```

`make hot` is the preferred Compose UI loop. For focused work, run Gradle from
`app/`, for example `./gradlew :backend:desktopTest` or
`./gradlew :desktop:run`.

For phone/tablet work, install Android SDK platform/build-tools 36 and run
`make mobile` or `./gradlew :mobile:installDebug`. The `app/mobile` APK uses the
same `CoveApp` Compose root as desktop. Android TV is a separate presentation
host: share its domain/backend logic, but do not add D-pad/ten-foot behavior as
conditional branches throughout the touch UI.

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
- Route all user-supplied URLs through `DesktopAddonUrlPolicy`. Do not enable
  automatic redirects on the untrusted HTTP client or forward credentials to a
  different authority.
- Nuvio code must remain in the child-process sandbox. Do not expose host
  classes, filesystem/process APIs, native access, or unbounded execution.
- Comments should explain constraints and decisions; use names and small
  functions to explain mechanics.

The retired backend is available through Git history; a local ignored cutover
copy may exist under `legacy/go-backend`. Do not implement fixes or features
there.

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

CI builds and tests the complete Gradle project on Linux, lints the Android app,
launches it on a phone emulator, reviews pull-request dependencies, and lints
workflow files. Release jobs repeat the Kotlin gate and create Linux/Flatpak,
Windows, and signed Android phone/tablet artifacts.

## Before opening a PR

- Run the checks proportional to the changed surface and `git diff --check`.
- Describe behavior and migration impact, not just the files changed.
- Preserve desktop input/player behavior and phone/tablet touch behavior. Keep
  TV presentation separate and never pretend desktop-native libraries are
  portable to Android.
- Never commit `.env`, user databases, auth sessions, provider tokens, or
  generated release credentials.
