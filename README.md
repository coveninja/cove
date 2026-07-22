<div align="center">
  <img src="web/src/assets/CoveIcon.svg" alt="Cove" width="120" />

# Cove

A media streaming app for Linux, Windows, Android & Android TV. Discover, track, and stream movies and TV shows - powered by TMDB metadata, Stremio & Nuvio compatible addons & plugins, and a built-in mpv player.

[![CI](https://github.com/coveninja/cove/actions/workflows/ci.yml/badge.svg)](https://github.com/coveninja/cove/actions/workflows/ci.yml)
[![CodeQL](https://github.com/coveninja/cove/actions/workflows/codeql.yml/badge.svg)](https://github.com/coveninja/cove/actions/workflows/codeql.yml)
[![Coverage](https://codecov.io/gh/coveninja/cove/branch/master/graph/badge.svg)](https://codecov.io/gh/coveninja/cove)
[![Latest Release](https://img.shields.io/github/v/release/coveninja/cove?label=release)](https://github.com/coveninja/cove/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Go](https://img.shields.io/badge/Go-1.26+-00ADD8?logo=go&logoColor=white)](https://go.dev)
[![Svelte](https://img.shields.io/badge/Svelte-5-FF3E00?logo=svelte&logoColor=white)](https://svelte.dev)
[![Qt](https://img.shields.io/badge/Qt-6-41CD52?logo=qt&logoColor=white)](https://www.qt.io)

[![Platform](https://img.shields.io/badge/platform-linux%20%7C%20windows%20%7C%20android-informational)](#install)
[![Downloads](https://img.shields.io/github/downloads/coveninja/cove/total)](https://github.com/coveninja/cove/releases/latest)
[![Stars](https://img.shields.io/github/stars/coveninja/cove?style=social)](https://github.com/coveninja/cove/stargazers)
[![Issues](https://img.shields.io/github/issues/coveninja/cove)](https://github.com/coveninja/cove/issues)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
</div>

> **Disclaimer:** Cove is a media player and organizer, not a content host. It does not provide, index, or distribute any media itself — a fresh install has zero sources configured. Any streams come from third-party Stremio-compatible addons or community plugins that *you* choose to add. You're responsible for what you connect to and for complying with the laws in your jurisdiction.

## Table of Contents

- [Features](#features)
- [Install](#install)
- [Build from source](#build-from-source)
- [Development](#development)
- [Testing and CI](#testing-and-ci)
- [Configuration](#configuration)
- [Documentation](#documentation)
- [Community & Support](#community--support)
- [Star History](#star-history)
- [Roadmap / Known Limitations](#roadmap--known-limitations)

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
- **Android & Android TV App** — native Kotlin shell hosting the responsive Svelte UI, with the same embedded Go backend and mpv playback; runs standalone, or connects to your desktop Cove over LAN in remote mode

## Install

### Arch / CachyOS (PKGBUILD)

Install via the AUR:

```sh
yay -S cove-bin
```

Or download `PKGBUILD` from the [latest release](https://github.com/coveninja/cove/releases/latest) manually and run `makepkg -si` in the same directory. To update, repeat with the new release's `PKGBUILD`.

### Flatpak — any Linux distro

Download `cove-linux-amd64.flatpak` from the [latest release](https://github.com/coveninja/cove/releases/latest), then:

```sh
flatpak install --user cove-linux-amd64.flatpak
flatpak run io.github.coveninja.Cove
```

### Windows

Download `cove-windows-amd64-setup.exe` from the [latest release](https://github.com/coveninja/cove/releases/latest) and run the installer. Or grab `cove-windows-amd64.zip` for a portable install.

### Android & Android TV

> **⚠️ Experimental:** Mobile & TV support is covered by JVM and emulator launch tests, but remains new. Expect rough edges — please [file an issue](https://github.com/coveninja/cove/issues) if you hit one.

Download `cove-android.apk` from the [latest release](https://github.com/coveninja/cove/releases/latest) and install it (sideloading — your browser or file manager will ask you to allow installs from unknown sources). The app is fully standalone; optionally point it at a desktop Cove on your LAN from Settings → Server.

You only need to sideload once — the app checks GitHub for new releases and updates itself in-app (downloads are SHA-256 verified; the very first self-update asks for confirmation, after that they're silent).

The same APK runs on Android TV — Cove detects the TV environment automatically and loads a D-pad-navigable 10-foot UI.

### macOS

Not currently supported. There's no native build or packaging for macOS yet — see [Roadmap](#roadmap--known-limitations).

## Build from source

**Prerequisites:** Go 1.26+, Node.js 20.19+ or 22.12+, Qt 6 with QtWebEngine and QtWebChannel, libmpv, and CMake

```sh
git clone https://github.com/coveninja/cove
cd cove
echo "TMDB_API_KEY=your_key_here" > .env
make run  # builds everything and launches the app
```

> Need a TMDB key? Create a free account at [themoviedb.org](https://www.themoviedb.org/), then generate one under **Settings → API** in your account.

### Development

```sh
make hot        # hot-reload: Vite HMR in-window, rebuilds Go + Qt on changes
make hot-debug  # same + QtWebEngine remote devtools on :9222
make web-dev    # browser-only Vite dev server (player shows "unavailable")
```

### Individual builds

```sh
make go      # build the Go backend binary
make web     # build the Svelte frontend
make qt      # build the Qt shell
make dev     # full build + regenerate TypeScript types from Go structs
make android # build the Android APK (gomobile AAR + Gradle; see android/README.md)
```

## Testing and CI

The standard target exercises both the public/no-op build and the checked-in
`supabase` auth/sync implementation. Maintainers with both private submodules
can separately run the exact release combination with `supabase,discover`.

```sh
make test      # complete Go + web suite
make test-all  # add workflow/security checks plus Qt and Android builds
```

`make test` includes API/sync unit tests with coverage and browser flows for
startup degradation, login persistence, profile switching, and sync errors.
Install the Playwright browser once before the first run:

```sh
cd web
npx playwright install chromium
```

The checks requiring private sources or an already-running Android target are
kept explicit:

```sh
make test-private            # maintainers, after make inject-private
make test-android-connected  # run instrumentation tests on a device/emulator
```

Pull requests and branch pushes run the full matrix in
[`ci.yml`](.github/workflows/ci.yml): formatting/vet, public and tagged Go
tests, the race detector, Linux/Windows builds, web checks, a blocking Qt
build, Android lint/JVM/emulator tests, dependency review, and vulnerability
scanning. Coverage reports are retained as workflow artifacts and published to
Codecov for the badge above; the current baseline is informational rather than
a merge threshold.

## Configuration

A fresh profile ships with no provider addons and no plugin repos enabled — only two hardcoded "official" integrations (streaming-availability lookup, intro/recap timestamps) work out of the box. To get streams, add one or more Stremio-compatible addon URLs and/or community plugin repos in the app's Settings page.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — how the Go backend, Svelte frontend, and Qt shell fit together, the playback data flow, and the open-source/proprietary build-tag split
- [docs/API.md](docs/API.md) — HTTP endpoint reference
- [docs/TESTING.md](docs/TESTING.md) — local test commands, CI matrix, coverage, and release gates
- [CONTRIBUTING.md](CONTRIBUTING.md) — dev setup and code style for contributors
- [android/README.md](android/README.md) — Android app: toolchain setup, emulator, build/install loops, release signing

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
