# Frequently asked questions

## Does Cove provide movies or television streams?

No. Cove is a player and organizer. A new installation contains no third-party stream sources. You choose any community addons yourself and remain responsible for using them lawfully.

## Do I need an account?

No. Profiles, libraries, playback, and settings work locally. An account is only needed for optional Cove cloud sync and website account management.

## What is the difference between a Cove account and Trakt?

A Cove account synchronizes compatible profile data between Cove devices. [Trakt](trakt.md) is a separate external service for scrobbling and optional library/history reconciliation. You can use either, both, or neither.

## Which platforms are supported?

Cove publishes packages for amd64 Linux, amd64 Windows, Apple-silicon macOS, and Android 9+ phones, tablets, and televisions. Intel macOS is not supported. Android uses one APK for touch devices and TV.

## Is Android TV a separate download?

No. The same APK declares phone/tablet and Leanback launchers, then selects the touch or D-pad interface at runtime. See [TV and remote controls](tv-and-remote-controls.md).

## Why is macOS warning about the app?

The DMG is ad-hoc signed but not notarized with an Apple Developer ID. Follow the [macOS installation guide](install/macos.md) and only approve a DMG from the official Cove release.

## How do updates work?

Windows and Android support verified in-app updates. AUR packages update through the package manager. Flatpak bundles, Linux tarballs, and macOS DMGs are replaced manually. See [Update Cove](updates.md).

## Can the website tell whether my device just synced?

No. It shows a read-only snapshot of cloud rows and their newest stored timestamp. Cove does not upload device heartbeat or last-error telemetry.

## Can I watch in a browser?

No. Playback remains in the native Cove applications, where mpv, native decoders, torrent handling, and isolated scraper runtimes are available.

## Why do I see no playback sources after installing?

Cove ships without third-party providers. Add only compatible services you understand and may lawfully use. Check the provider, catalog, and active-profile settings in [Addons and sources](addons-and-sources.md).

## What is the difference between addons, scrapers, and plugins?

Stremio-compatible addons are remote services with declared capabilities. Nuvio scrapers run community JavaScript in a platform-specific isolated process. Signed [desktop plugins](desktop-plugins.md) are desktop-only integration packages with individually approved permissions.

## Can profiles use different addons?

Yes. Provider configuration is profile-scoped unless the primary profile enables addon sharing. In that mode secondary profiles inherit the primary provider addons and catalog switches as read-only settings.

## How does Cove choose a source?

You can choose manually or enable automatic selection using Balanced, Quality first, or Most seeded ordering. Cove does not silently switch to another source after playback starts. See [Playback and subtitles](playback-and-subtitles.md).

## Can I load my own subtitle file?

Desktop playback can load a local subtitle through the track menu or accept a subtitle file dropped over the video. Availability on Android and TV follows their platform file and input boundaries.

## Why was an intro or credit not skipped?

Skipping requires a recognized embedded chapter or compatible timestamp for that segment type, plus the corresponding automatic setting. When automatic skipping is off, Cove can show a manual action while inside a known segment.

## Are downloads synchronized?

No. Torrent pieces, caches, update staging, and other stored media stay on the device. Library state and watch progress can sync without copying the underlying media.

## Can another device control Cove over the network?

Cove has an optional token-authenticated compatibility listener for trusted local networks. It is not an internet-facing service and does not provide TLS. Keep it disabled unless you use a compatible local client and protect the pairing token.

## Where are keyboard shortcuts listed?

Press `?` during desktop playback. The [playback guide](playback-and-subtitles.md) also lists the common controls.

## Where should I report a problem?

Use the [GitHub bug report form](https://github.com/coveninja/cove/issues/new?template=bug_report.yml) after checking [Troubleshooting](troubleshooting.md).
