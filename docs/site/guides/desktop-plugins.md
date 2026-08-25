# Desktop plugins

Desktop plugins are optional signed JavaScript packages for integrations and additional media capabilities. They are distinct from Stremio-compatible addons and Nuvio scraper repositories, and they are not available in the Android or TV settings surface.

## Install from the catalog

Open **Profile → Plugins**. Cove downloads a signed catalog and lists compatible packages that are not installed. Choosing a package installs it only after Cove verifies its signature, manifest, archive digest, size, compatibility, paths, and extracted limits.

An installed plugin remains off until every capability it requests has been reviewed and approved. Capabilities can include playback observation or transport, streams, subtitles, timestamps, metadata augmentation, bounded network access, profile storage, settings controls, or Discord Presence.

## Review permissions

Permissions are approved per profile. Read each description and enable only capabilities required for the behavior you want. Removing an approval stops the plugin from starting if that capability is required.

Plugins cannot directly use Cove's filesystem, native libraries, processes, threads, or host classes. Network and profile-storage operations go through bounded brokers and are checked against the approved capability and declared hosts. Isolation limits risk; it does not make every package appropriate for every profile.

## Manage a plugin

Each installed package shows its version, runtime status, requested permissions, enabled state, and plugin-defined settings. Available actions include:

- Enable or disable it for the active profile.
- Save a revised permission set.
- Change plugin-defined settings or invoke declared actions.
- Retry a failed or waiting worker.
- Uninstall the package.
- Refresh the signed catalog and stage compatible updates.

Updates activate after the existing worker stops. If a new version requests another capability, Cove requires approval before enabling it.

## Discord Presence

The official Discord Presence plugin publishes activity through Discord's local IPC protocol. Per-profile settings can control title, episode detail, playback state, remaining time, and TMDB artwork.

Playback URLs, provider credentials, and arbitrary file paths are not included in the activity snapshot. Activity is cleared when playback ends, the profile changes, the plugin is disabled, or Cove shuts down. Discord must be running locally under a compatible desktop session.

## Developer mode

Developer mode allows a local plugin ZIP to be installed by path. Local packages are unsigned and bypass the official catalog trust chain. Use this only while developing or auditing code you control, and turn it off for ordinary use.

## Troubleshoot a plugin

1. Refresh the catalog and confirm the package is compatible with the current Cove version.
2. Check that the plugin is enabled for this profile and every required capability is approved.
3. Read the displayed runtime status and retry once after a transient failure.
4. Confirm any declared external application, such as Discord, is running.
5. Collect Cove's desktop log and remove tokens, private URLs, and plugin storage values before sharing it.

For provider addons and community scrapers, use [Addons and sources](addons-and-sources.md). Plugin developers should use the repository's full [plugin reference](https://github.com/coveninja/cove/blob/master/docs/PLUGINS.md).

