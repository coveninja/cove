# Getting started

Cove is a local-first media library and native player for desktop, Android, and Android TV. It works without an account, and a new installation contains no third-party stream sources.

## 1. Install the right package

Use the [download center](https://cove.ninja/download) or the official GitHub release:

- [Linux](install/linux.md): AUR, standalone Flatpak, or amd64 tarball
- [Windows](install/windows.md): installed or portable amd64 package
- [macOS](install/macos.md): Apple-silicon DMG
- [Android phones, tablets, and televisions](install/android-tv.md): one Android 9+ APK

Only assets attached to an official `coveninja/cove` release are Cove releases. Verify manual downloads before installing them.

## 2. Complete onboarding

The first-run flow takes about a minute and can be skipped. It offers:

1. **Profile** — choose the local viewer name.
2. **Taste** — select titles that help seed recommendations.
3. **Sources** — optionally add provider addons or scraper repositories.
4. **Preferences** — choose subtitle, next-episode, and intro behavior.
5. **Sync** — optionally register or sign in to a Cove account.
6. **Finish** — review what was configured and enter the application.

Skipping a step leaves its normal default in place. Everything can be changed later from **Profile**. Local profiles work offline; sign in only when you want [cross-device sync](accounts-and-sync.md).

## 3. Learn the main destinations

| Destination | Purpose |
|---|---|
| Home | Recommendations, trending media, continue watching, and enabled catalog rows |
| Explore | Browse genres, collections, and addon catalogs |
| Search | Find films, series, and people |
| My List | Manage library states, ratings, progress, and the episode calendar |
| Profile | Switch profiles, view insights, and configure settings and integrations |

Android TV presents the same product areas through a D-pad interface. See [TV and remote controls](tv-and-remote-controls.md).

## 4. Build your library

Open a title and add it to **My List**. You can set its state, rate it, choose episodes, and resume saved progress. Cove keeps this data separate for each profile.

The Watch action adapts to current progress. It starts fresh when nothing is resumable and shows a Continue target when Cove knows the episode and position. Read [Library, discovery, and watch progress](library-discovery-and-progress.md) for the complete behavior.

## 5. Configure playback sources

Cove is a player and organizer, not a content host or subscription service. Legal watch options appear where available. Community addons, scrapers, and plugins are optional; review them and use them lawfully.

- [Addons and sources](addons-and-sources.md) covers Stremio-compatible addons and Nuvio scrapers.
- [Desktop plugins](desktop-plugins.md) covers signed desktop integrations.
- [Playback and subtitles](playback-and-subtitles.md) explains source selection and player controls.

## 6. Get help

Start with [Troubleshooting](troubleshooting.md). Record the Cove version under **Profile → Advanced → About**, the platform and installation method, the exact failing action, and relevant logs. Never publish passwords, access tokens, pairing tokens, or private provider URLs.
