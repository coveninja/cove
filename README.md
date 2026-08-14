<div align="center">
  <img src="packaging/icons/cove.svg" alt="Cove logo" width="128" />

  <h1>Cove</h1>

  <p><strong>Discover, organize, and play your media in one native application.</strong></p>

  <p>
    Cove brings a personal library, rich discovery, extensible sources, and
    hardware-accelerated playback to Linux, Windows, and Android phones and tablets.
  </p>

  <p>
    <a href="#installation">Installation</a> ·
    <a href="#building-from-source">Build from source</a> ·
    <a href="#architecture">Architecture</a> ·
    <a href="#documentation">Documentation</a> ·
    <a href="CONTRIBUTING.md">Contributing</a>
  </p>

  <p>
    <a href="https://github.com/coveninja/cove/actions/workflows/ci.yml"><img src="https://github.com/coveninja/cove/actions/workflows/ci.yml/badge.svg" alt="CI status" /></a>
    <a href="https://github.com/coveninja/cove/releases/latest"><img src="https://img.shields.io/github/v/release/coveninja/cove?label=latest" alt="Latest release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-2563eb" alt="AGPL-3.0 license" /></a>
    <a href="https://www.jetbrains.com/compose-multiplatform/"><img src="https://img.shields.io/badge/Kotlin-Compose%20Multiplatform-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Compose Multiplatform" /></a>
  </p>
</div>

> [!IMPORTANT]
> Cove is a media player and organizer, not a content host or provider. A fresh
> installation contains no third-party stream sources. You are responsible for
> the addons and plugins you configure and for following the laws that apply in
> your jurisdiction.

## Features

### Discovery and organization

- Personalized recommendations informed by watch history, ratings, and taste.
- Search, trending and upcoming titles, genres, curated collections, and people.
- A profile-scoped library with favorites, progress, continue watching, and
  spoiler protection for unwatched episodes.
- Multiple local profiles, with optional account sync and Trakt integration.

### Playback

- Native, hardware-accelerated mpv playback on desktop and Android.
- Direct HTTP and torrent playback with subtitles and live buffering progress.
- Automatic stream selection by quality, size, reliability, or measured
  connection speed, plus full manual sorting and filtering.
- Configurable intro, recap, and credits skipping, with progress saved as you watch.

### Sources and extensions

- Stremio-compatible addons for catalogs, streams, subtitles, and metadata.
- Opt-in community scraper plugins executed behind platform-specific isolation.
- Legal watch-option discovery alongside configured playback sources.
- Local-first profiles and settings: signing in is optional.

## Platform support

| Platform | Status | Distribution | Update path |
|---|---|---|---|
| Linux | Available | AUR, Flatpak | `cove-bin` through `pacman`; standalone Flatpak bundles are manual |
| Windows | Available | Installer, portable ZIP | Verified in-app updates for both forms beginning with `1.0.0` |
| Android phone/tablet | Preview | APK, Android 9+ | Verified APK through Android's package installer |
| Android TV | Planned | — | Dedicated ten-foot/D-pad host is not packaged yet |
| macOS | Not available | — | No native build or packaging yet |

## Installation

All downloadable packages are published on the
[GitHub Releases](https://github.com/coveninja/cove/releases/latest) page.

### Arch Linux and CachyOS

Install the AUR package with your preferred helper:

```sh
yay -S cove-bin
```

Updates remain managed by `pacman` and your AUR workflow.

### Flatpak

Download `cove-linux-amd64.flatpak` from the latest release, then run:

```sh
flatpak install --user cove-linux-amd64.flatpak
flatpak run io.github.coveninja.Cove
```

Standalone Flatpak bundles are replaced manually when a new version is released.

### Windows

Download one of the following from the latest release:

- `cove-windows-amd64-setup.exe` for a conventional installation.
- `cove-windows-amd64.zip` for a self-contained portable copy.

Both distributions support signed, verified in-app updates beginning with
`1.0.0`. Older Windows builds need one manual installer or ZIP update to enter
the new update path.

### Android phone/tablet

Download and install `cove-android.apk` on Android 9 (API 28) or newer. Android
may ask you to allow installs from Cove when applying the first in-app update.
Cove verifies the package name, version, release manifest, payload checksum, and
installed signing certificate before handing the APK to the system installer.

## Building from source

### Prerequisites

- JDK 21
- libmpv (`mpv` on Arch/CachyOS or `libmpv-dev` on Debian/Ubuntu)
- Android SDK platform and build-tools 36 for Android builds
- A TMDB API key for live catalog data

Clone the repository, create your local configuration, and launch the desktop app:

```sh
git clone https://github.com/coveninja/cove.git
cd cove
cp .env.example .env
# Set TMDB_API_KEY in .env
make run
```

Common development commands:

| Command | Purpose |
|---|---|
| `make run` | Build and launch the desktop application |
| `make mobile` | Build the Android phone/tablet debug APK |
| `make hot` | Start the Compose hot-reload development loop |
| `make test` | Run the Kotlin test suites |
| `make test-build` | Build the desktop image and Android debug APK |
| `make test-all` | Run the broadest local approximation of CI |

Desktop configuration is loaded from environment variables, the nearest `.env`,
and then bundled release properties. Android receives the same deployment values
through `BuildConfig` at build time. See [.env.example](.env.example) for the
supported keys. `COVE_DATA_DIR` overrides the desktop data directory; Android
always uses app-private storage.

## Architecture

Cove is a Kotlin Multiplatform application with two native hosts over one shared
application graph:

- `app/shared` defines domain models, repository contracts, and the app graph.
- `app/backend` owns local persistence, integrations, addons, discovery, sync,
  media boundaries, and host-specific runtime composition.
- `app/ui` contains the shared adaptive Compose presentation.
- `app/desktop` owns the desktop window, native mpv surfaces, lifecycle, and packaging.
- `app/mobile` owns Android lifecycle, system integration, native playback, and APK packaging.

Ordinary UI operations stay in-process. An embedded, authenticated Ktor boundary
is retained only where URL semantics are required—for example media proxying,
torrent delivery, diagnostics, and optional LAN clients. SQLDelight provides the
shared database contract with platform-native drivers, while libmpv provides the
playback boundary on both shipping hosts.

For the detailed module and security model, read [ARCHITECTURE.md](ARCHITECTURE.md).

## Documentation

- [Application and presentation guide](docs/APP.md)
- [Architecture](ARCHITECTURE.md)
- [HTTP compatibility API](docs/API.md)
- [Application updates and signing](docs/UPDATES.md)
- [Testing and release checks](docs/TESTING.md)
- [Contributing guide](CONTRIBUTING.md)

Translations recovered from Cove's former frontend remain under
`app/i18n/messages/`; they are not yet connected to the current Compose UI.

## Contributing and support

Contributions are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md) for the
development workflow, architecture boundaries, and validation expectations.

- [Report a bug or request a feature](https://github.com/coveninja/cove/issues)
- [Ask a question or start a discussion](https://github.com/coveninja/cove/discussions)

## Star History

<a href="https://www.star-history.com/?repos=coveninja%2Fcove&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=coveninja/cove&type=date&theme=dark&legend=top-left&sealed_token=4GbbMkGKGiaXN1d60vfao3mhJl3TIFYWtueyZuta_mcDXrGkF63OQqyv4VgSp4DbPhGKYc8UNJubllPuYou3pnWPc9IAmJuem_27FHCoO_20WiA7vsx4LQ" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=coveninja/cove&type=date&legend=top-left&sealed_token=4GbbMkGKGiaXN1d60vfao3mhJl3TIFYWtueyZuta_mcDXrGkF63OQqyv4VgSp4DbPhGKYc8UNJubllPuYou3pnWPc9IAmJuem_27FHCoO_20WiA7vsx4LQ" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=coveninja/cove&type=date&legend=top-left&sealed_token=4GbbMkGKGiaXN1d60vfao3mhJl3TIFYWtueyZuta_mcDXrGkF63OQqyv4VgSp4DbPhGKYc8UNJubllPuYou3pnWPc9IAmJuem_27FHCoO_20WiA7vsx4LQ" />
 </picture>
</a>

## License

Cove is free software licensed under the
[GNU Affero General Public License v3.0](LICENSE).
