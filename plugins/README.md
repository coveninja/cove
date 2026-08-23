# Cove plugin catalog source

This directory is the seed for the separate `coveninja/cove-plugins` catalog repository. Cove itself only consumes that repository's signed release assets; it never executes code fetched directly from a branch.

The Discord reference plugin is intentionally a template because Discord assigns the application ID in its developer portal. Copy this directory's contents to the catalog repository, move `catalog-release.workflow.yml` to `.github/workflows/release.yml`, and configure:

- `PLUGIN_SIGNING_KEY_BASE64`: a base64-encoded PKCS#8 DER Ed25519 private key secret.
- `PLUGIN_SIGNING_KEY_ID`: the matching public-key identifier, for example `plugins-2026-1`.
- `DISCORD_APPLICATION_ID`: the Discord application ID used for Rich Presence.
- A Discord Rich Presence image asset named `cove`.

Publish only from a protected tag. Add the matching `key-id=base64-x509-public-key` value to Cove's `PLUGIN_TRUSTED_PUBLIC_KEYS` repository variable before building a desktop release.

For local development, package the plugin and install it from Cove's **Settings → Plugins → Developer plugins** section:

```sh
plugins/tools/package-plugin.sh plugins/discord /tmp/cove-discord.zip 1234567890123456
```

Developer packages are unsigned, visibly marked as such, and still require explicit capability approval.
