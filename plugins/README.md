# Cove plugin catalog source

This directory is the seed for the separate `coveninja/cove-plugins` catalog
repository. Cove consumes only that repository's signed release assets; it never
executes JavaScript fetched directly from a branch. The complete manifest,
capability, guest API, and testing contract is in
[Desktop plugins](../docs/PLUGINS.md).

## Seed layout

- `discord/` contains the Discord Presence source and manifest template.
- `tools/package-plugin.sh` creates a deterministic ZIP with `plugin.json` at its root.
- `tools/sign-asset.sh` creates a base64 Ed25519 signature for one asset.
- `tools/generate-catalog.sh` builds the catalog from packaged ZIPs.
- `catalog-release.workflow.yml` is the protected-tag publication workflow seed.

Copy these files into the root of the catalog repository and move
`catalog-release.workflow.yml` to `.github/workflows/release.yml`.

The Discord reference plugin remains a template because Discord assigns the
application ID in its developer portal. Configure the catalog repository with:

- `PLUGIN_SIGNING_KEY_BASE64`: a base64-encoded PKCS#8 DER Ed25519 private key secret.
- `PLUGIN_SIGNING_KEY_ID`: the matching public-key identifier, for example `plugins-2026-1`.
- `DISCORD_APPLICATION_ID`: a repository variable containing the Discord application ID used for Rich Presence.
- A Discord Rich Presence image asset named `cove`.

Generate the signing key once and keep its private material outside the source
repository:

```sh
openssl genpkey -algorithm ED25519 -out plugin-private.pem
openssl pkey -in plugin-private.pem -outform DER -out plugin-private.der
base64 -w0 plugin-private.der
openssl pkey -in plugin-private.pem -pubout -outform DER -out plugin-public.der
base64 -w0 plugin-public.der
```

Store the private DER value only in `PLUGIN_SIGNING_KEY_BASE64`. Add the public
value to Cove's `PLUGIN_TRUSTED_PUBLIC_KEYS` repository variable as
`key-id=base64-x509-public-key` before building a desktop release that should
trust the catalog.

## Build locally

Package the Discord reference with an application id:

```sh
plugins/tools/package-plugin.sh plugins/discord /tmp/cove-discord.zip 1234567890123456
```

For another plugin directory containing `plugin.json`, omit the third argument.
The packager normalizes timestamps and ZIP ordering so identical inputs produce
stable archive bytes.

Sign a package, then generate and sign a catalog:

```sh
plugins/tools/sign-asset.sh \
  /tmp/cove-discord.zip \
  /path/to/plugin-private.der \
  /tmp/cove-discord.zip.sig

plugins/tools/generate-catalog.sh \
  plugins-2026-1 \
  https://github.com/coveninja/cove-plugins/releases/download/v1.0.0 \
  /tmp/cove-plugin-catalog-v1.json \
  /tmp/cove-discord.zip

plugins/tools/sign-asset.sh \
  /tmp/cove-plugin-catalog-v1.json \
  /path/to/plugin-private.der \
  /tmp/cove-plugin-catalog-v1.json.sig
```

The catalog generator records the exact manifest, release URLs, byte count, and
SHA-256 digest of each ZIP. Do not modify a package after generating the catalog.

## Test before publishing

Enable **Profile → Plugins → Developer mode** and install the local ZIP by its
absolute path. Developer packages are unsigned, visibly marked, and still
require capability approval. Test enable/disable, permission removal, profile
switching, every setting and action, external-service absence, worker retry, and
clean shutdown.

Run the plugin backend tests from `app/`:

```sh
./gradlew :backend:desktopTest --tests '*PluginPackagesTest' --no-daemon
./gradlew :backend:desktopTest --tests '*DesktopPluginManagerTest' --no-daemon
./gradlew :backend:desktopTest --tests '*PluginProcessTest' --no-daemon
```

## Publish and rotate keys

Publish only from a protected, verified `v*` tag. The workflow builds and signs
every package, generates and signs `cove-plugin-catalog-v1.json`, then uploads
the immutable assets to one GitHub release.

For key rotation, first ship a Cove desktop release containing both the old and
new public entries. Only after that bridge version is available should the
catalog switch its signing secret and key id. Remove the old key from a later
Cove release after supported clients have received the new trust entry.

Never place the private key in Cove, the catalog branch, a plugin ZIP, build
logs, or a release asset.
