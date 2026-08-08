<div align="center">
  <img src="packaging/icons/cove.svg" alt="Cove" width="120" />

# Cove

A Kotlin media app for Linux, Windows, and Android phones/tablets. Desktop and
mobile share one Compose UI and Kotlin domain layer; desktop adds the in-process
mpv and extension runtime.

[![CI](https://github.com/coveninja/cove/actions/workflows/ci.yml/badge.svg)](https://github.com/coveninja/cove/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/coveninja/cove?label=release)](https://github.com/coveninja/cove/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose%20Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![Platform](https://img.shields.io/badge/platform-linux%20%7C%20windows%20%7C%20android-informational)](#install)
</div>

> Cove is a media player and organizer, not a content host. A fresh install
> has no third-party stream sources. You are responsible for the addons and
> plugins you configure and for complying with the laws in your jurisdiction.

## Features

- TMDB-powered discovery, localized metadata, search, recommendations, library,
  profiles, progress, ratings, calendar, and insights
- Stremio-compatible stream, subtitle, and catalog addons
- Opt-in Nuvio community scrapers executed in a restricted child JVM
- In-process mpv playback, jlibtorrent streaming, subtitle conversion, image
  caching, source probing, speed testing, and predictive stream prefetch
- Optional Supabase account sync and Trakt device login/scrobbling/two-way sync
- SQLite persistence with an atomic, one-time import from Cove's legacy JSON
  stores and an explicit JSON export for recovery
- A small embedded Ktor boundary for mpv/media URLs, diagnostics, optional LAN
  clients, and compatibility with existing HTTP consumers
- One adaptive Compose UI for desktop and Android touch devices, backed by
  platform SQLDelight drivers and in-process repositories

## Install

### Arch / CachyOS

```sh
yay -S cove-bin
```

### Flatpak

Download `cove-linux-amd64.flatpak` from the
[latest release](https://github.com/coveninja/cove/releases/latest), then:

```sh
flatpak install --user cove-linux-amd64.flatpak
flatpak run io.github.coveninja.Cove
```

### Windows

Use `cove-windows-amd64-setup.exe` from the latest release, or the portable
`cove-windows-amd64.zip`.

### Android phone/tablet

Download `cove-android.apk` from the latest release and install it on Android 9
(API 28) or newer. The package keeps Cove's existing `com.coveninja.cove`
identity so an upgrade can import profiles, settings, library state, and watch
progress written by the former Android app.

Android TV is intentionally not included in this APK: TV keeps its own
ten-foot/D-pad presentation while sharing the Kotlin domain and backend layers.
That dedicated TV host is not packaged yet. macOS is also not currently
packaged.

## Build from source

Desktop prerequisites are JDK 21 and libmpv (`mpv` on Arch/CachyOS,
`libmpv-dev` on Debian/Ubuntu). Building the Android APK additionally requires
the Android SDK with platform/build-tools 36. Cove also needs a TMDB API key.

```sh
git clone https://github.com/coveninja/cove
cd cove
echo "TMDB_API_KEY=your_key_here" > .env
make run
```

Useful targets:

```sh
make app       # build backend, shared code, UI, and desktop launcher
make mobile    # build the Android phone/tablet APK
make hot       # Compose hot-reload loop
make test      # all Kotlin tests
make test-all  # tests, workflow lint, desktop image, and mobile APK
```

Desktop runtime configuration is read from environment variables, the nearest
`.env`, or release build properties. Android reads the same values when the APK
is built. Supported deployment keys are `TMDB_API_KEY`,
`SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `TRAKT_CLIENT_ID`, and
`TRAKT_CLIENT_SECRET`. On desktop, set `COVE_DATA_DIR` to override the data
directory; Android always uses app-private storage.

## Data migration and recovery

On first startup, Cove parses the existing JSON stores, creates a timestamped
backup, and imports them into SQLite in one transaction. Desktop imports the
complete profile/settings/library/session/addon/Nuvio/Trakt/activity snapshot.
Android imports profiles, settings, library state, and progress from its
existing app-private `filesDir`; service state not yet active on mobile is kept
as opaque JSON for later adapters. A parse or write failure leaves the source
files untouched, and the import marker makes later startups idempotent.

To write the current SQLite state back to legacy-compatible sidecars without
starting the UI or network services:

```sh
cd app
./gradlew :desktop:run --args='--export-legacy'
```

The retired backend remains available in Git history, with a local ignored copy
kept under `legacy/go-backend` during cutover. It is not built, tested, packaged,
or run.

## Documentation

- [Architecture](ARCHITECTURE.md)
- [HTTP compatibility API](docs/API.md)
- [Testing and release checks](docs/TESTING.md)
- [Contributing](CONTRIBUTING.md)

Translations salvaged from the former Svelte UI remain in `app/i18n/messages/`.
They are not wired into the current Compose screens yet.
