# Desktop plugins

Cove desktop plugins are optional JavaScript packages with a versioned manifest,
explicit capabilities, native settings, and an isolated worker. They do not
replace Stremio-compatible addons or Nuvio scrapers. Provider results are
additive and attributed; metadata augmentation can fill only supported fields;
playback observers receive a URL-free activity snapshot.

The plugin API is currently version `1` and is desktop-only. Android and TV use
an unavailable repository and do not expose plugin settings.

## Trust and isolation

Official packages come from signed release assets in `coveninja/cove-plugins`.
Cove verifies the Ed25519 catalog and package signatures, catalog/package
manifest equality, SHA-256 digest, declared size, Cove compatibility, archive
paths, file count, and extracted size before installation. Updates are staged
and activate only after the worker stops. Newly requested capabilities require
another approval.

Each enabled plugin runs in a separate 128 MiB child JVM. GraalJS host class
lookup, filesystem access, native access, process creation, and thread creation
are disabled. The host/worker protocol is line-framed, bounded, timed, and
closed when Cove shuts down.

Permissions, enablement, settings, and storage are profile-scoped. A worker
cannot start until all capabilities declared by its installed manifest are
approved. Developer packages are visibly unsigned but still use the sandbox and
permission gate.

## Package format

A package is a ZIP with `plugin.json` at its root and the declared JavaScript
entrypoint. Paths must be normalized, relative, and stay inside the package. A
minimal manifest is:

```json
{
  "schema_version": 1,
  "api_version": 1,
  "id": "org.example.cove.sample",
  "name": "Sample plugin",
  "version": "1.0.0",
  "description": "Adds a small Cove integration.",
  "publisher": "Example",
  "entrypoint": "main.js",
  "minimum_cove_version": "1.2.1",
  "capabilities": ["playback.observe"],
  "allowed_hosts": [],
  "settings": []
}
```

Ids are lowercase dotted, dashed, or underscored identifiers with at least two
segments. Plugin and minimum-Cove versions use `major.minor.patch` with an
optional leading `v`. The entrypoint defaults to `main.js`.

`allowed_hosts` contains hostnames, not URL patterns. It requires
`network.http`. Declaring `network.lan` also requires `network.http`.
`discord_application_id` is allowed only with `discord.presence` and must be the
numeric application id assigned by Discord.

## Capabilities

| Capability | Allows |
|---|---|
| `playback.observe` | Receive URL-free playback activity changes |
| `playback.transport` | Pause/resume, absolute or relative seek, and stop through the host broker |
| `media.streams` | Return attributed direct or torrent source candidates |
| `media.subtitles` | Return subtitle sources |
| `media.timestamps` | Return intro, recap, credit, or preview timestamps |
| `metadata.augment` | Fill supported overview, tagline, artwork, or link fields |
| `network.http` | Use the bounded `cove.fetch` broker for declared hosts |
| `network.lan` | Permit private targets when Cove's device LAN preference also allows them |
| `storage.profile` | Read and write the plugin's bounded active-profile storage |
| `ui.settings` | Declare native settings controls |
| `discord.presence` | Set or clear activity through Cove's local Discord IPC broker |

The manifest is the maximum permission request. The active profile's approved
set is the effective permission set. Broker operations fail when the matching
capability was not declared and approved.

## Native settings schema

Settings require `ui.settings`. Each definition has a unique `key`, `type`,
`label`, optional `description`, and type-compatible `default`.

| Type | Additional fields | Behavior |
|---|---|---|
| `boolean` | Boolean default | Native switch |
| `string` | String default | Text field, at most 8,192 characters |
| `number` | Optional finite `minimum` and `maximum` | Bounded numeric field |
| `select` | Non-empty `options` of `{value,label}` | Native single-choice control |
| `action` | No default | Button that invokes `onAction` |

Setting changes are validated before storage, then passed to the running worker
through `settingsChanged`. An action sends `{ "key": "..." }` to `onAction` and
does not persist a value.

## Guest lifecycle and hooks

The entrypoint is evaluated as CommonJS. Export hooks from `module.exports`:

```js
"use strict";

module.exports = {
  activate() {},
  deactivate() {},
  settingsChanged(settings) {},
  onAction({ key }) {},
  onPlaybackChanged(activity) {},
  provideStreams(media) { return []; },
  provideSubtitles(media) { return []; },
  provideTimestamps(media) { return null; },
  augmentMetadata(media) { return {}; }
};
```

Unimplemented hooks resolve to `null`. `activate` runs after the sandbox and
brokers are initialized. A failure prevents the worker becoming Running.
`deactivate` is best-effort during an orderly shutdown; external state must also
be safe if the process exits without it.

Provider hooks receive a media request with TMDB id, media type, optional IMDb
id, title, year, season, and episode. The host caps result counts, sanitizes URLs
and text, attributes stream results to the plugin, and ignores malformed or
overlong values.

`onPlaybackChanged` receives activity, title and TMDB identity, optional episode
details, phase, pause state, position, duration, speed, reconnect state, and
public artwork. It never receives the selected stream URL, torrent hash,
provider headers, or account credentials.

## Guest API

### Settings and storage

```js
const one = cove.settings.get("key");
const all = cove.settings.all();

const cached = cove.storage.get("last-item");
cove.storage.set("last-item", { id: 1 });
cove.storage.delete("last-item");
```

Storage requires `storage.profile`, is isolated by plugin and profile, and is
capped at 1 MiB. Settings are read-only inside the guest.

### Network

`cove.fetch(url, options)` returns a small Fetch-like response with `ok`,
`status`, `url`, `redirected`, `headers`, `text()`, and `json()`. It requires
`network.http`; every redirect is revalidated and the destination hostname must
remain declared. Supported methods are GET, POST, PUT, PATCH, DELETE, and HEAD.

Responses are capped at 5 MiB and requests time out. The broker strips control
of `Host`, `Content-Length`, `Connection`, `Cookie`, and `Authorization`.
Private, loopback, and metadata addresses are rejected unless `network.lan` is
approved and Cove's LAN preference is enabled.

### Playback transport

```js
cove.player.setPaused(true);
cove.player.seek(120);
cove.player.seekRelative(-10);
cove.player.stop();
```

These methods require `playback.transport`. They submit a command to Cove's
active playback session; they do not expose the native player object.

### Discord

`cove.discord.setActivity(activity)` and `cove.discord.clear()` require
`discord.presence`. Cove owns Discord's local IPC connection and validates the
activity shape before sending it.

## Media result rules

A stream result can contain a direct `url` or torrent `infoHash`, plus optional
name, title, headers, file index, byte size, and seeders. Return only one playable
addressing form per candidate. The host fills plugin attribution and rejects
unsafe or empty results.

Subtitle results use the shared `SubtitleSource` contract. Timestamp results use
the shared `MediaTimestamps` contract; values are clamped and later merged with
other providers. Metadata augmentation supports overview, tagline, poster,
backdrop, and links. It cannot overwrite authoritative fields outside that
contract.

## Local development

Start from the Discord seed or create a ZIP with `plugin.json` and the
entrypoint at its root. Package the reference plugin with:

```sh
plugins/tools/package-plugin.sh \
  plugins/discord \
  /tmp/cove-discord.zip \
  1234567890123456
```

In Cove, enable **Profile → Plugins → Developer mode**, enter the absolute ZIP
path, install it, review capabilities, and enable it for the test profile. Local
packages are unsigned; do not reuse developer mode as a distribution channel.

Focused backend tests are:

```sh
cd app
./gradlew :backend:desktopTest --tests '*PluginPackagesTest' --no-daemon
./gradlew :backend:desktopTest --tests '*DesktopPluginManagerTest' --no-daemon
./gradlew :backend:desktopTest --tests '*PluginProcessTest' --no-daemon
```

Also exercise activation, permission denial, settings changes, provider timeout,
worker failure/retry, profile switching, staged updates, and clean shutdown with
an ephemeral package.

## Publishing a signed catalog

The catalog is a separate signed JSON document containing the key id,
publication timestamp, and entries with manifest, package URL, signature URL,
declared size, and SHA-256 digest. Cove executes only verified release assets;
it never executes JavaScript fetched directly from a branch.

Use an offline Ed25519 private key, publish from protected tags, and embed the
matching public key in a Cove desktop release before publishing packages signed
by it. A package key rotation needs the same bridge-release discipline as the
application updater. See [the catalog seed](../plugins/README.md) for the exact
workflow inputs.

## Discord Presence reference

The reference plugin uses Discord's local IPC handshake and `SET_ACTIVITY`; it
does not scrape Discord, inject into its process, or transmit playback details
to an arbitrary web endpoint. It clears activity when playback ends, the active
profile changes, the plugin is disabled, or Cove shuts down.

Title, episode detail, playback state, remaining-time display, and TMDB artwork
are configurable per profile. Artwork is restricted to TMDB's public image CDN;
provider and playback URLs are never included. The catalog publisher must supply
a Discord application ID and create an application image asset named `cove`.

## Troubleshooting

- **Permission required:** approve every capability declared by the installed manifest.
- **Failed at start:** inspect the first plugin error in `cove.log`; validate the entrypoint and `activate`.
- **Waiting:** check the displayed status, then retry once after the external dependency is ready.
- **Network denied:** declare the exact hostname and approve `network.http`; use `network.lan` only for an intentional private target.
- **Provider returns nothing:** validate the media request and return shape, then check host sanitization limits.
- **Update staged:** stop the current worker by disabling the plugin or exiting Cove so the verified version can activate.
- **Discord is empty:** confirm Discord is running, the application id and `cove` asset exist, and `discord.presence` is approved.

Remove secrets, private URLs, and profile storage values before sharing plugin
logs or packages.
