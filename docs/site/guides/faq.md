# Frequently asked questions

## Does Cove provide movies or television streams?

No. Cove is a player and organizer. A new installation contains no third-party stream sources. You choose any community addons yourself and remain responsible for using them lawfully.

## Do I need an account?

No. Profiles, libraries, playback, and settings work locally. An account is only needed for optional Cove cloud sync and website account management.

## Which platforms are supported?

Cove publishes packages for amd64 Linux, amd64 Windows, Apple-silicon macOS, and Android 9+ phones, tablets, and televisions. Intel macOS is not supported. Android uses one APK for touch devices and TV.

## Why is macOS warning about the app?

The DMG is ad-hoc signed but not notarized with an Apple Developer ID. Follow the [macOS installation guide](install/macos.md) and only approve a DMG from the official Cove release.

## How do updates work?

Windows and Android support verified in-app updates. AUR packages update through the package manager. Flatpak bundles, Linux tarballs, and macOS DMGs are replaced manually. See [Update Cove](updates.md).

## Can the website tell whether my device just synced?

No. It shows a read-only snapshot of cloud rows and their newest stored timestamp. Cove does not upload device heartbeat or last-error telemetry.

## Can I watch in a browser?

No. Playback remains in the native Cove applications, where mpv, native decoders, torrent handling, and isolated scraper runtimes are available.

## Where should I report a problem?

Use the [GitHub bug report form](https://github.com/coveninja/cove/issues/new?template=bug_report.yml) after checking [Troubleshooting](troubleshooting.md).

