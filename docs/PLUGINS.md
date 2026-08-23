# Desktop plugins

Cove desktop plugins are optional JavaScript packages with a versioned manifest. They do not replace the Stremio or Nuvio addon paths. Media-provider results are additive and attributed; metadata plugins can only fill missing fields; playback integrations receive a URL-free activity snapshot and can request only the declared transport operations.

## Trust and isolation

Official packages come from signed release assets in `coveninja/cove-plugins`. Cove verifies the Ed25519 signature, catalog/package manifest equality, SHA-256 digest, declared size, compatibility version, archive paths, file count, and extracted size before installation. Updates are staged and activate only after the worker stops; newly requested capabilities require another approval.

Each enabled plugin runs in a separate 128 MiB JVM with GraalJS host class lookup, filesystem access, native access, process creation, and thread creation disabled. Network access is brokered, limited to declared hosts, capped at 5 MiB per response, and rejects private addresses unless both the plugin and Cove's LAN preference allow them. Profile storage is brokered and capped at 1 MiB.

Permissions and settings are per profile. A plugin cannot start until every declared capability has been individually approved. The native settings schema supports boolean, string, number, select, and action controls.

## Package format

A package is a ZIP with `plugin.json` at its root and the declared JavaScript entrypoint. Manifest schema and API version are currently `1`. Supported capabilities are:

- `playback.observe`, `playback.transport`
- `media.streams`, `media.subtitles`, `media.timestamps`, `metadata.augment`
- `network.http`, `network.lan`, `storage.profile`
- `ui.settings`, `discord.presence`

The guest exports hooks from `module.exports`: `activate`, `deactivate`, `onPlaybackChanged`, `provideStreams`, `provideSubtitles`, `provideTimestamps`, `augmentMetadata`, `settingsChanged`, and `onAction`. Unimplemented hooks resolve to `null`.

The guest API exposes `cove.settings`, `cove.storage`, `cove.fetch`, narrow `cove.player` transport methods, and `cove.discord`. Broker calls are rejected unless their corresponding capability was granted.

## Discord Presence reference

The reference plugin uses Discord's local IPC handshake and `SET_ACTIVITY`; it does not scrape Discord, inject into its process, or transmit playback details over an arbitrary web endpoint. It clears activity when playback ends, the active profile changes, the plugin is disabled, or Cove shuts down. Title, episode detail, playback state, remaining-time display, and TMDB artwork can be configured per profile. Artwork is restricted to TMDB's public image CDN; provider and playback URLs are never included in the activity snapshot.

The catalog publisher must supply a Discord application ID and an application image asset named `cove`. See [the catalog seed](../plugins/README.md) for release steps.
