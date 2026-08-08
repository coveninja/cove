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

- **Stream anything** — connects to Stremio-compatible addon sources and streams directly in the app
- **Extend with plugins** — add community JS scraper plugins for additional stream sources, sandboxed and opt-in per scraper
- **Built-in player** — hardware-accelerated mpv playback with subtitle support, live buffering/download progress, and progress saving
- **Smart stream picker** — auto-selects the best available stream using a configurable strategy (quality, size, reliability, or a connection-speed match via a built-in speed test), or sort/filter candidates yourself
- **Skip intro & recap** — auto-skip buttons for intro, recap, and credits segments during playback, independently toggleable
- **Where to watch** — see which legal streaming/rental services carry a title alongside the stream picker
- **Discover** — personalized recommendations based on your watch history, ratings, and taste profile
- **Library** — track what you've watched, mark favorites, and pick up where you left off with continue watching
- **Explore** — browse trending, upcoming releases, genres, and curated categories
- **Insights** — view your watch stats and genre/actor taste breakdown
- **Search** — find any movie or TV show by title
- **Spoiler-free browsing** — optionally blurs thumbnails and titles for unwatched episodes
- **Multiple profiles** — profile switching, works fully offline with no sign-in required
- **Accounts & sync** — optional sign-in syncs your library and preferences across devices
- **Trakt.tv integration** — optional Trakt sign-in scrobbles what you watch in real time and two-way syncs your watch history and watchlist with Trakt automatically

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
(API 28) or newer.

Android TV is intentionally not included in this APK: TV keeps its own
ten-foot/D-pad presentation while sharing the Kotlin domain and backend layers.
That dedicated TV host is not packaged yet.

### macOS
Not currently supported. There's no native build or packaging for macOS yet — see [Roadmap](#roadmap--known-limitations).

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

## Documentation

- [Architecture](ARCHITECTURE.md)
- [HTTP compatibility API](docs/API.md)
- [Testing and release checks](docs/TESTING.md)
- [Contributing](CONTRIBUTING.md)

Translations salvaged from the former Svelte UI remain in `app/i18n/messages/`.
They are not wired into the current Compose screens yet.

## Community & Support

- **Bugs & feature requests:** [GitHub Issues](https://github.com/coveninja/cove/issues)
- **Questions & discussion:** [GitHub Discussions](https://github.com/coveninja/cove/discussions)

New contributors are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for dev setup and code style before opening a PR.

## Star History

<a href="https://www.star-history.com/?repos=coveninja%2Fcove&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=coveninja/cove&type=date&theme=dark&legend=top-left&sealed_token=4GbbMkGKGiaXN1d60vfao3mhJl3TIFYWtueyZuta_mcDXrGkF63OQqyv4VgSp4DbPhGKYc8UNJubllPuYou3pnWPc9IAmJuem_27FHCoO_20WiA7vsx4LQ" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=coveninja/cove&type=date&legend=top-left&sealed_token=4GbbMkGKGiaXN1d60vfao3mhJl3TIFYWtueyZuta_mcDXrGkF63OQqyv4VgSp4DbPhGKYc8UNJubllPuYou3pnWPc9IAmJuem_27FHCoO_20WiA7vsx4LQ" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=coveninja/cove&type=date&legend=top-left&sealed_token=4GbbMkGKGiaXN1d60vfao3mhJl3TIFYWtueyZuta_mcDXrGkF63OQqyv4VgSp4DbPhGKYc8UNJubllPuYou3pnWPc9IAmJuem_27FHCoO_20WiA7vsx4LQ" />
 </picture>
</a>

## Roadmap / Known Limitations

- **Android & Android TV** are experimental — expect rough edges (see [Install](#install))
- **macOS** is not yet supported — no native build exists
- Have a request? Open an [issue](https://github.com/coveninja/cove/issues) to discuss it
